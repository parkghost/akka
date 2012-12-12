/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps
import scala.concurrent.duration._
import org.scalatest.BeforeAndAfterEach
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.Props
import akka.actor.Terminated
import akka.cluster.StandardMetrics.Cpu
import akka.cluster.StandardMetrics.HeapMemory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.routing.FromConfig
import akka.testkit._
import akka.routing.CurrentRoutees
import akka.routing.RouterRoutees
import akka.actor.PoisonPill

object StressMultiJvmSpec extends MultiNodeConfig {

  // Note that this test uses default configuration,
  // not MultiNodeClusterSpec.clusterConfig
  commonConfig(ConfigFactory.parseString("""
    akka.test.cluster-stress-spec {
      nr-of-nodes-factor = 1
      nr-of-nodes = 13
      nr-of-seed-nodes = 3
      nr-of-nodes-joining-to-seed-initally = 2
      nr-of-nodes-joining-one-by-one-small = 2
      nr-of-nodes-joining-one-by-one-large = 2
      nr-of-nodes-joining-to-one = 2
      nr-of-nodes-joining-to-seed = 2
      nr-of-nodes-leaving-one-by-one-small = 1
      nr-of-nodes-leaving-one-by-one-large = 2
      nr-of-nodes-leaving = 2
      nr-of-nodes-shutdown-one-by-one-small = 1
      nr-of-nodes-shutdown-one-by-one-large = 2
      nr-of-nodes-shutdown = 2
      work-batch-size = 100
      work-batch-interval = 2s
      normal-throughput-duration = 30s
      high-throughput-duration = 10s
    }

    akka.actor.provider = akka.cluster.ClusterActorRefProvider
    akka.cluster {
      auto-join = off
      auto-down = on
      publish-stats-interval = 0 s # always, when it happens
    }
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = INFO
    akka.remote.log-remote-lifecycle-events = off

    akka.actor.deployment {
      /master-node-1/workers {
        router = round-robin
        nr-of-instances = 100
        cluster {
          enabled = on
          max-nr-of-instances-per-node = 1
          allow-local-routees = off
        }
      }
      /master-node-2/workers {
        router = round-robin
        nr-of-instances = 100
        cluster {
          enabled = on
          routees-path = "/user/worker"
          allow-local-routees = off
        }
      }
      /master-node-3/workers = {
        router = adaptive
        nr-of-instances = 100
        cluster {
          enabled = on
          max-nr-of-instances-per-node = 1
          allow-local-routees = off
        }
      }
    }
    """))

  // FIXME configurable number of nodes
  for (n ← 1 to 13) role("node-" + n)

  case class ClusterResult(
    address: Address,
    duration: Duration,
    clusterStats: ClusterStats,
    nodeMetrics: Option[NodeMetrics])

  class ClusterResultAggregator(title: String, expectedResults: Int) extends Actor with ActorLogging {
    var results = Vector.empty[ClusterResult]

    def receive = {
      case r: ClusterResult ⇒
        results :+= r
        if (results.size == expectedResults) {
          log.info("[{}] completed in [{}] ms\n{}\n{}", title, maxDuration.toMillis, totalClusterStats, formatMetrics)
          context stop self
        }
    }

    def maxDuration = results.map(_.duration).max

    def totalClusterStats = results.map(_.clusterStats).foldLeft(ClusterStats()) { (acc, s) ⇒
      ClusterStats(
        receivedGossipCount = acc.receivedGossipCount + s.receivedGossipCount,
        mergeConflictCount = acc.mergeConflictCount + s.mergeConflictCount,
        mergeCount = acc.mergeCount + s.mergeCount,
        mergeDetectedCount = acc.mergeDetectedCount + s.mergeDetectedCount)
    }

    def formatMetrics: String = {
      import akka.cluster.Member.addressOrdering
      formatMetricsHeader + "\n" +
        results.sortBy(_.address).map(r ⇒ r.address + "\t" + formatMetricsLine(r.nodeMetrics)).mkString("\n")
    }

    def formatMetricsHeader: String = "Node\tHeap (MB)\tCPU (%)\tLoad"

    def formatMetricsLine(nodeMetricsOption: Option[NodeMetrics]): String = nodeMetricsOption match {
      case None ⇒ "N/A\tN/A\tN/A"
      case Some(nodeMetrics) ⇒
        (nodeMetrics match {
          case HeapMemory(address, timestamp, used, committed, max) ⇒
            (used.doubleValue / 1024 / 1024).formatted("%.2f")
          case _ ⇒ ""
        }) + "\t" +
          (nodeMetrics match {
            case Cpu(address, timestamp, loadOption, cpuOption, processors) ⇒
              format(cpuOption) + "\t" + format(loadOption)
            case _ ⇒ "N/A\tN/A"
          })
    }

    def format(opt: Option[Double]) = opt match {
      case None    ⇒ "N/A"
      case Some(x) ⇒ x.formatted("%.2f")
    }
  }

  class Master(val batchSize: Int, val batchInterval: FiniteDuration) extends Actor {
    val workers = context.actorOf(Props[Worker].withRouter(FromConfig), "workers")
    var idCounter = 0L
    def nextId(): JobId = {
      idCounter += 1
      idCounter
    }
    var sendCounter = 0L
    var ackCounter = 0L
    var outstanding = Map.empty[JobId, JobState]
    var startTime = 0L

    import context.dispatcher
    val resendTask = context.system.scheduler.schedule(3.seconds, 3.seconds, self, RetryTick)

    override def postStop(): Unit = {
      resendTask.cancel()
      super.postStop()
    }

    def receive = {
      case Begin ⇒
        startTime = System.nanoTime
        self ! SendBatch
        context.become(working)
      case RetryTick ⇒
    }

    def working: Receive = {
      case Ack(id) ⇒
        outstanding -= id
        ackCounter += 1
        if (outstanding.size == batchSize / 2)
          if (batchInterval == Duration.Zero) self ! SendBatch
          else context.system.scheduler.scheduleOnce(batchInterval, self, SendBatch)
      case SendBatch ⇒
        if (outstanding.size < batchSize)
          sendJobs()
      case RetryTick ⇒ resend()
      case End ⇒
        done(sender)
        context.become(ending(sender))
    }

    def ending(replyTo: ActorRef): Receive = {
      case Ack(id) ⇒
        outstanding -= id
        ackCounter += 1
        done(replyTo)
      case SendBatch ⇒
      case RetryTick ⇒ resend()
    }

    def done(replyTo: ActorRef): Unit = if (outstanding.isEmpty) {
      val duration = (System.nanoTime - startTime).nanos
      replyTo ! WorkResult(duration, sendCounter, ackCounter)
      context stop self
    }

    def sendJobs(): Unit = {
      0 until batchSize foreach { _ ⇒
        // FIXME payload size should be configurable
        send(Job(nextId(), "payload"))
      }
    }

    def resend(): Unit = {
      outstanding.values foreach { jobState ⇒
        if (jobState.deadline.isOverdue)
          send(jobState.job)
      }
    }

    def send(job: Job): Unit = {
      outstanding += job.id -> JobState(Deadline.now + 5.seconds, job)
      sendCounter += 1
      workers ! job
    }
  }

  class Worker extends Actor {
    override def postStop(): Unit = {
      super.postStop()
    }
    def receive = {
      case Job(id, payload) ⇒ sender ! Ack(id)
    }
  }

  case object Begin
  case object End
  case object RetryTick
  case object ReportTick
  case object SendBatch
  type JobId = Long
  case class Job(id: JobId, payload: String)
  case class Ack(id: JobId)
  case class JobState(deadline: Deadline, job: Job)
  case class WorkResult(duration: Duration, sendCount: Long, ackCount: Long) {
    def droppedCount: Long = sendCount - ackCount
    def messagesPerSecond: Double = ackCount * 1000.0 / duration.toMillis
  }

}

class StressMultiJvmNode1 extends StressSpec
class StressMultiJvmNode2 extends StressSpec
class StressMultiJvmNode3 extends StressSpec
class StressMultiJvmNode4 extends StressSpec
class StressMultiJvmNode5 extends StressSpec
class StressMultiJvmNode6 extends StressSpec
class StressMultiJvmNode7 extends StressSpec
class StressMultiJvmNode8 extends StressSpec
class StressMultiJvmNode9 extends StressSpec
class StressMultiJvmNode10 extends StressSpec
class StressMultiJvmNode11 extends StressSpec
class StressMultiJvmNode12 extends StressSpec
class StressMultiJvmNode13 extends StressSpec

abstract class StressSpec
  extends MultiNodeSpec(StressMultiJvmSpec)
  with MultiNodeClusterSpec with BeforeAndAfterEach {

  import StressMultiJvmSpec._
  import ClusterEvent._

  var step = 0
  var usedRoles = 0

  override def beforeEach(): Unit = { step += 1 }

  object Settings {
    val testConfig = system.settings.config.getConfig("akka.test.cluster-stress-spec")

    val numberOfNodesFactor = testConfig.getInt("nr-of-nodes-factor")
    val totalNumberOfNodes = testConfig.getInt("nr-of-nodes") * numberOfNodesFactor ensuring (
      _ >= 10, "nr-of-nodes must be >= 10")
    val numberOfSeedNodes = testConfig.getInt("nr-of-seed-nodes") // not multiplied by numberOfNodesFactor
    val numberOfNodesJoiningToSeedNodesInitially =
      testConfig.getInt("nr-of-nodes-joining-to-seed-initally") * numberOfNodesFactor
    val numberOfNodesJoiningOneByOneSmall =
      testConfig.getInt("nr-of-nodes-joining-one-by-one-small") * numberOfNodesFactor
    val numberOfNodesJoiningOneByOneLarge =
      testConfig.getInt("nr-of-nodes-joining-one-by-one-large") * numberOfNodesFactor
    val numberOfNodesJoiningToOneNode =
      testConfig.getInt("nr-of-nodes-joining-to-one") * numberOfNodesFactor
    val numberOfNodesJoiningToSeedNodes =
      testConfig.getInt("nr-of-nodes-joining-to-seed") * numberOfNodesFactor
    val numberOfNodesLeavingOneByOneSmall =
      testConfig.getInt("nr-of-nodes-leaving-one-by-one-small") * numberOfNodesFactor
    val numberOfNodesLeavingOneByOneLarge =
      testConfig.getInt("nr-of-nodes-leaving-one-by-one-large") * numberOfNodesFactor
    val numberOfNodesLeaving =
      testConfig.getInt("nr-of-nodes-leaving") * numberOfNodesFactor
    val numberOfNodesShutdownOneByOneSmall =
      testConfig.getInt("nr-of-nodes-shutdown-one-by-one-small") * numberOfNodesFactor
    val numberOfNodesShutdownOneByOneLarge =
      testConfig.getInt("nr-of-nodes-shutdown-one-by-one-large") * numberOfNodesFactor
    val numberOfNodesShutdown =
      testConfig.getInt("nr-of-nodes-shutdown") * numberOfNodesFactor

    val workBatchSize = testConfig.getInt("work-batch-size")
    val workBatchInterval = Duration(testConfig.getMilliseconds("work-batch-interval"), MILLISECONDS)
    val normalThroughputDuration = Duration(testConfig.getMilliseconds("normal-throughput-duration"), MILLISECONDS)
    val highThroughputDuration = Duration(testConfig.getMilliseconds("high-throughput-duration"), MILLISECONDS)

    require(numberOfSeedNodes + numberOfNodesJoiningToSeedNodesInitially + numberOfNodesJoiningOneByOneSmall +
      numberOfNodesJoiningOneByOneLarge + numberOfNodesJoiningToOneNode + numberOfNodesJoiningToSeedNodes <= totalNumberOfNodes,
      s"specified number of joining nodes <= ${totalNumberOfNodes}")

    // don't shutdown the 3 nodes hosting the master actors
    require(numberOfNodesLeavingOneByOneSmall + numberOfNodesLeavingOneByOneLarge + numberOfNodesLeaving +
      numberOfNodesShutdownOneByOneSmall + numberOfNodesShutdownOneByOneLarge + numberOfNodesShutdown <= totalNumberOfNodes - 3,
      s"specified number of leaving/shutdown nodes <= ${totalNumberOfNodes - 3}")
  }

  import Settings._

  val seedNodes = roles.take(Settings.numberOfSeedNodes)

  override def cluster: Cluster = {
    createWorker
    super.cluster
  }

  // always create one worker when the cluster is started
  lazy val createWorker: Unit =
    system.actorOf(Props[Worker], "worker")

  def createResultAggregator(title: String, expectedResults: Int): Unit = {
    runOn(roles.head) {
      system.actorOf(Props(new ClusterResultAggregator(title, expectedResults)), "result" + step)
    }
    enterBarrier("result-aggregator-created-" + step)
  }

  def clusterResultAggregator: ActorRef = system.actorFor(node(roles.head) / "user" / ("result" + step))

  def awaitClusterResult: Unit = {
    runOn(roles.head) {
      val r = clusterResultAggregator
      watch(r)
      expectMsgPF(remaining) { case Terminated(`r`) ⇒ true }
    }
    enterBarrier("cluster-result-done-" + step)
  }

  def joinOneByOne(numberOfNodes: Int): Unit = {
    0 until numberOfNodes foreach { _ ⇒
      joinOne()
      usedRoles += 1
      step += 1
    }
  }

  def joinOne(): Unit = within(5.seconds + 2.seconds * (usedRoles + 1)) {
    val currentRoles = roles.take(usedRoles + 1)
    createResultAggregator(s"join one to ${usedRoles} nodes cluster", currentRoles.size)
    runOn(currentRoles: _*) {
      reportResult {
        runOn(currentRoles.last) {
          cluster.join(roles.head)
        }
        awaitUpConvergence(currentRoles.size, timeout = remaining)
      }

    }
    awaitClusterResult
    enterBarrier("join-one-" + step)
  }

  def joinSeveral(numberOfNodes: Int, toSeedNodes: Boolean): Unit =
    within(10.seconds + 2.seconds * (usedRoles + numberOfNodes)) {
      val currentRoles = roles.take(usedRoles + numberOfNodes)
      val joiningRoles = currentRoles.takeRight(numberOfNodes)
      val title = s"join ${numberOfNodes} to ${if (toSeedNodes) "seed nodes" else "one node"}, in ${usedRoles} nodes cluster"
      createResultAggregator(title, currentRoles.size)
      runOn(currentRoles: _*) {
        reportResult {
          runOn(joiningRoles: _*) {
            if (toSeedNodes) cluster.joinSeedNodes(seedNodes.toIndexedSeq map address)
            else cluster.join(roles.head)
          }
          awaitUpConvergence(currentRoles.size, timeout = remaining)
        }

      }
      awaitClusterResult
      enterBarrier("join-several-" + step)
    }

  def removeOneByOne(numberOfNodes: Int, shutdown: Boolean): Unit = {
    0 until numberOfNodes foreach { _ ⇒
      removeOne(shutdown)
      usedRoles -= 1
      step += 1
    }
  }

  def removeOne(shutdown: Boolean): Unit = within(10.seconds + 2.seconds * (usedRoles - 1)) {
    val currentRoles = roles.take(usedRoles - 1)
    createResultAggregator(s"${if (shutdown) "shutdown" else "remove"} one from ${usedRoles} nodes cluster", currentRoles.size)
    val removeRole = roles(usedRoles - 1)
    runOn(removeRole) {
      if (!shutdown) cluster.leave(myself)
    }
    runOn(currentRoles: _*) {
      reportResult {
        runOn(roles.head) {
          if (shutdown) testConductor.shutdown(removeRole, 0).await
        }
        awaitUpConvergence(currentRoles.size, timeout = remaining)
      }
    }
    awaitClusterResult
    enterBarrier("remove-one-" + step)
  }

  def removeSeveral(numberOfNodes: Int, shutdown: Boolean): Unit =
    within(10.seconds + 3.seconds * (usedRoles - numberOfNodes)) {
      val currentRoles = roles.take(usedRoles - numberOfNodes)
      val removeRoles = roles.slice(currentRoles.size, usedRoles)
      val title = s"${if (shutdown) "shutdown" else "leave"} ${numberOfNodes} in ${usedRoles} nodes cluster"
      createResultAggregator(title, currentRoles.size)
      runOn(removeRoles: _*) {
        if (!shutdown) cluster.leave(myself)
      }
      runOn(currentRoles: _*) {
        reportResult {
          runOn(roles.head) {
            if (shutdown) removeRoles.foreach { r ⇒ testConductor.shutdown(r, 0).await }
          }
          awaitUpConvergence(currentRoles.size, timeout = remaining)
        }
      }
      awaitClusterResult
      enterBarrier("remove-several-" + step)
    }

  def reportResult[T](thunk: ⇒ T): T = {
    val startTime = System.nanoTime

    val returnValue = thunk

    val duration = (System.nanoTime - startTime).nanos
    val nodeMetrics = clusterView.clusterMetrics.collectFirst {
      case m if (m.address == cluster.selfAddress) ⇒ m
    }
    clusterResultAggregator ! ClusterResult(cluster.selfAddress, duration, clusterView.latestStats, nodeMetrics)
    returnValue
  }

  def master: ActorRef = system.actorFor("/user/master-" + myself.name)

  def exerciseRouters(title: String, duration: FiniteDuration, batchInterval: FiniteDuration, expectNoDroppedMessages: Boolean): Unit =
    within(duration + 10.seconds) {
      createResultAggregator(title, usedRoles)

      val (masterRoles, otherRoles) = roles.take(usedRoles).splitAt(3)
      runOn(masterRoles: _*) {
        reportResult {
          val m = system.actorOf(Props(new Master(workBatchSize, batchInterval)), "master-" + myself.name)
          m ! Begin
          import system.dispatcher
          system.scheduler.scheduleOnce(highThroughputDuration) {
            m.tell(End, testActor)
          }
          val workResult = awaitWorkResult
          workResult.sendCount must be > (0L)
          workResult.ackCount must be > (0L)
          if (expectNoDroppedMessages)
            workResult.droppedCount must be(0)

          enterBarrier("routers-done-" + step)
        }
      }
      runOn(otherRoles: _*) {
        reportResult {
          enterBarrier("routers-done-" + step)
        }
      }

      awaitClusterResult
    }

  def awaitWorkResult: WorkResult = {
    val m = master
    val workResult = expectMsgType[WorkResult]
    log.info("{} result, [{}] msg/s, dropped [{}] of [{}] msg", m.path.name,
      workResult.messagesPerSecond.formatted("%.2f"),
      workResult.droppedCount, workResult.sendCount)
    watch(m)
    expectMsgPF(remaining) { case Terminated(`m`) ⇒ true }
    workResult
  }

  "A cluster under stress" must {

    "join seed nodes" taggedAs LongRunningTest in {

      val otherNodesJoiningSeedNodes = roles.slice(numberOfSeedNodes, numberOfSeedNodes + numberOfNodesJoiningToSeedNodesInitially)
      val size = seedNodes.size + otherNodesJoiningSeedNodes.size

      createResultAggregator("join seed nodes", size)

      runOn((seedNodes ++ otherNodesJoiningSeedNodes): _*) {
        reportResult {
          cluster.joinSeedNodes(seedNodes.toIndexedSeq map address)
          awaitUpConvergence(size)
        }
      }

      awaitClusterResult

      usedRoles += size
      enterBarrier("after-" + step)
    }

    "start routers that are running while nodes are joining" taggedAs LongRunningTest in {
      runOn(roles.take(3): _*) {
        system.actorOf(Props(new Master(workBatchSize, workBatchInterval)), "master-" + myself.name) ! Begin
      }
      enterBarrier("after-" + step)
    }

    "join nodes one-by-one to small cluster" taggedAs LongRunningTest in {
      joinOneByOne(numberOfNodesJoiningOneByOneSmall)
      enterBarrier("after-" + step)
    }

    "join several nodes to one node" taggedAs LongRunningTest in {
      joinSeveral(numberOfNodesJoiningToOneNode, toSeedNodes = false)
      usedRoles += numberOfNodesJoiningToOneNode
      enterBarrier("after-" + step)
    }

    "join several nodes to seed nodes" taggedAs LongRunningTest in {
      joinSeveral(numberOfNodesJoiningToOneNode, toSeedNodes = true)
      usedRoles += numberOfNodesJoiningToSeedNodes
      enterBarrier("after-" + step)
    }

    "join nodes one-by-one to large cluster" taggedAs LongRunningTest in {
      joinOneByOne(numberOfNodesJoiningOneByOneLarge)
      enterBarrier("after-" + step)
    }

    "end routers that are running while nodes are joining" taggedAs LongRunningTest in within(30.seconds) {
      runOn(roles.take(3): _*) {
        val m = master
        m.tell(End, testActor)
        val workResult = awaitWorkResult
        workResult.droppedCount must be(0)
        workResult.sendCount must be > (0L)
        workResult.ackCount must be > (0L)
      }
      enterBarrier("after-" + step)
    }

    "use routers with normal throughput" taggedAs LongRunningTest in {
      exerciseRouters("use routers with normal throughput", normalThroughputDuration,
        batchInterval = workBatchInterval, expectNoDroppedMessages = true)
      enterBarrier("after-" + step)
    }

    "use routers with high throughput" taggedAs LongRunningTest in {
      exerciseRouters("use routers with high throughput", highThroughputDuration,
        batchInterval = Duration.Zero, expectNoDroppedMessages = true)
      enterBarrier("after-" + step)
    }

    "start routers that are running while nodes are removed" taggedAs LongRunningTest in {
      runOn(roles.take(3): _*) {
        system.actorOf(Props(new Master(workBatchSize, workBatchInterval)), "master-" + myself.name) ! Begin
      }
      enterBarrier("after-" + step)
    }

    "leave nodes one-by-one from large cluster" taggedAs LongRunningTest in {
      removeOneByOne(numberOfNodesLeavingOneByOneLarge, shutdown = false)
      enterBarrier("after-" + step)
    }

    "shutdown nodes one-by-one from large cluster" taggedAs LongRunningTest in {
      removeOneByOne(numberOfNodesShutdownOneByOneLarge, shutdown = true)
      enterBarrier("after-" + step)
    }

    "leave several nodes" taggedAs LongRunningTest in {
      removeSeveral(numberOfNodesLeaving, shutdown = false)
      usedRoles -= numberOfNodesLeaving
      enterBarrier("after-" + step)
    }

    "shutdown several nodes" taggedAs LongRunningTest in {
      removeSeveral(numberOfNodesShutdown, shutdown = true)
      usedRoles -= numberOfNodesShutdown
      enterBarrier("after-" + step)
    }

    "leave nodes one-by-one from small cluster" taggedAs LongRunningTest in {
      removeOneByOne(numberOfNodesLeavingOneByOneSmall, shutdown = false)
      enterBarrier("after-" + step)
    }

    "shutdown nodes one-by-one from small cluster" taggedAs LongRunningTest in {
      removeOneByOne(numberOfNodesShutdownOneByOneSmall, shutdown = true)
      enterBarrier("after-" + step)
    }

    "end routers that are running while nodes are removed" taggedAs LongRunningTest in within(30.seconds) {
      runOn(roles.take(3): _*) {
        // FIXME Something is wrong with shutdown, the master/workers are not terminated
        //        val m = master
        //        m.tell(End, testActor)
        //        val workResult = awaitWorkResult
        //        workResult.sendCount must be > (0L)
        //        workResult.ackCount must be > (0L)
      }
      enterBarrier("after-" + step)
    }

  }
}
