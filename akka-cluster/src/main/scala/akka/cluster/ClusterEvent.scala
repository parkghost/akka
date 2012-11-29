/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps
import scala.collection.immutable.SortedSet
import scala.collection.immutable
import collection.immutable
import collection.immutable.{ VectorBuilder, SortedSet }
import akka.actor.{ Actor, ActorLogging, ActorRef, Address }
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus._
import akka.event.EventStream
import akka.actor.AddressTerminated
import java.lang.Iterable
import akka.japi.Util.immutableSeq
import akka.util.Collections.EmptyImmutableSeq

/**
 * Domain events published to the event bus.
 * Subscribe with:
 * {{{
 *   Cluster(system).subscribe(actorRef, classOf[ClusterDomainEvent])
 * }}}
 */
object ClusterEvent {
  /**
   * Marker interface for cluster domain events.
   */
  sealed trait ClusterDomainEvent

  /**
   * Current snapshot state of the cluster. Sent to new subscriber.
   */
  case class CurrentClusterState(
    members: immutable.SortedSet[Member] = immutable.SortedSet.empty,
    unreachable: Set[Member] = Set.empty,
    convergence: Boolean = false,
    seenBy: Set[Address] = Set.empty,
    leader: Option[Address] = None) extends ClusterDomainEvent {

    /**
     * Java API
     * Read only
     */
    def getMembers: java.lang.Iterable[Member] = {
      import scala.collection.JavaConverters._
      members.asJava
    }

    /**
     * Java API
     * Read only
     */
    def getUnreachable: java.util.Set[Member] =
      scala.collection.JavaConverters.setAsJavaSetConverter(unreachable).asJava

    /**
     * Java API
     * Read only
     */
    def getSeenBy: java.util.Set[Address] =
      scala.collection.JavaConverters.setAsJavaSetConverter(seenBy).asJava

    /**
     * Java API
     * @return address of current leader, or null if none
     */
    def getLeader: Address = leader orNull
  }

  /**
   * Marker interface for member related events.
   */
  sealed trait MemberEvent extends ClusterDomainEvent {
    def member: Member
  }

  /**
   * A new member joined the cluster. Only published after convergence.
   */
  case class MemberJoined(member: Member) extends MemberEvent {
    if (member.status != Joining) throw new IllegalArgumentException("Expected Joining status, got: " + member)
  }

  /**
   * Member status changed to Up. Only published after convergence.
   */
  case class MemberUp(member: Member) extends MemberEvent {
    if (member.status != Up) throw new IllegalArgumentException("Expected Up status, got: " + member)
  }

  /**
   * Member status changed to Leaving. Only published after convergence.
   */
  case class MemberLeft(member: Member) extends MemberEvent {
    if (member.status != Leaving) throw new IllegalArgumentException("Expected Leaving status, got: " + member)
  }

  /**
   * Member status changed to Exiting. Only published after convergence.
   */
  case class MemberExited(member: Member) extends MemberEvent {
    if (member.status != Exiting) throw new IllegalArgumentException("Expected Exiting status, got: " + member)
  }

  /**
   * Member status changed to Down. Only published after convergence.
   */
  case class MemberDowned(member: Member) extends MemberEvent {
    if (member.status != Down) throw new IllegalArgumentException("Expected Down status, got: " + member)
  }

  /**
   * Member completely removed from the cluster. Only published after convergence.
   */
  case class MemberRemoved(member: Member) extends MemberEvent {
    if (member.status != Removed) throw new IllegalArgumentException("Expected Removed status, got: " + member)
  }

  /**
   * Leader of the cluster members changed. Only published after convergence.
   */
  case class LeaderChanged(leader: Option[Address]) extends ClusterDomainEvent {
    /**
     * Java API
     * @return address of current leader, or null if none
     */
    def getLeader: Address = leader orNull
  }

  /**
   * INTERNAL API
   * A member is considered as unreachable by the failure detector.
   */
  case class UnreachableMember(member: Member) extends ClusterDomainEvent

  /**
   * INTERNAL API
   *
   * Current snapshot of cluster node metrics. Published to subscribers.
   */
  case class ClusterMetricsChanged(nodeMetrics: Set[NodeMetrics]) extends ClusterDomainEvent {
    /**
     * Java API
     */
    def getNodeMetrics: java.lang.Iterable[NodeMetrics] =
      scala.collection.JavaConverters.asJavaIterableConverter(nodeMetrics).asJava
  }

  /**
   * INTERNAL API
   * The nodes that have seen current version of the Gossip.
   */
  private[cluster] case class SeenChanged(convergence: Boolean, seenBy: Set[Address]) extends ClusterDomainEvent

  /**
   * INTERNAL API
   */
  private[cluster] case class CurrentInternalStats(stats: ClusterStats) extends ClusterDomainEvent

  /**
   * INTERNAL API
   */
  private[cluster] def diff(oldGossip: Gossip, newGossip: Gossip): immutable.Seq[ClusterDomainEvent] =
    if (newGossip eq oldGossip) immutable.Seq.empty
    else {
      val newMembers = newGossip.members -- oldGossip.members

      val membersGroupedByAddress = (newGossip.members.toList ++ oldGossip.members.toList).groupBy(_.address)
      val changedMembers = membersGroupedByAddress collect {
        case (_, newMember :: oldMember :: Nil) if newMember.status != oldMember.status ⇒ newMember
      }

      val memberEvents = (newMembers ++ changedMembers) map { m ⇒
        if (m.status == Joining) MemberJoined(m)
        else if (m.status == Up) MemberUp(m)
        else if (m.status == Leaving) MemberLeft(m)
        else if (m.status == Exiting) MemberExited(m)
        else throw new IllegalStateException("Unexpected member status: " + m)
      }

      val allNewUnreachable = newGossip.overview.unreachable -- oldGossip.overview.unreachable
      val (newDowned, newUnreachable) = allNewUnreachable partition { _.status == Down }
      val downedEvents = newDowned map MemberDowned
      val unreachableEvents = newUnreachable map UnreachableMember

      val unreachableGroupedByAddress =
        (newGossip.overview.unreachable.toList ++ oldGossip.overview.unreachable.toList).groupBy(_.address)
      val unreachableDownMembers = unreachableGroupedByAddress collect {
        case (_, newMember :: oldMember :: Nil) if newMember.status == Down && newMember.status != oldMember.status ⇒
          newMember
      }
      val unreachableDownedEvents = unreachableDownMembers map MemberDowned

      val removedEvents = (oldGossip.members -- newGossip.members -- newGossip.overview.unreachable) map { m ⇒
        MemberRemoved(m.copy(status = Removed))
      }

      val leaderEvents =
        if (newGossip.leader != oldGossip.leader) Seq(LeaderChanged(newGossip.leader))
        else Seq.empty

      val newConvergence = newGossip.convergence
      val newSeenBy = newGossip.seenBy
      val seenEvents =
        if (newConvergence != oldGossip.convergence || newSeenBy != oldGossip.seenBy) Seq(SeenChanged(newConvergence, newSeenBy))
        else Seq.empty

      (new VectorBuilder[ClusterDomainEvent]() ++= memberEvents ++= unreachableEvents ++= downedEvents ++= unreachableDownedEvents ++=
        removedEvents ++= leaderEvents ++= seenEvents).result()
    }
}

/**
 * INTERNAL API.
 * Responsible for domain event subscriptions and publishing of
 * domain events to event bus.
 */
private[cluster] final class ClusterDomainEventPublisher extends Actor with ActorLogging {
  import InternalClusterAction._

  var latestGossip: Gossip = Gossip()
  var latestConvergedGossip: Gossip = Gossip()

  def receive = {
    case PublishChanges(newGossip)            ⇒ publishChanges(newGossip)
    case currentStats: CurrentInternalStats   ⇒ publishInternalStats(currentStats)
    case PublishCurrentClusterState(receiver) ⇒ publishCurrentClusterState(receiver)
    case Subscribe(subscriber, to)            ⇒ subscribe(subscriber, to)
    case Unsubscribe(subscriber, to)          ⇒ unsubscribe(subscriber, to)
    case PublishEvent(event)                  ⇒ publish(event)
    case PublishStart                         ⇒ publishStart()
    case PublishDone                          ⇒ publishDone(sender)
  }

  def eventStream: EventStream = context.system.eventStream

  def publishCurrentClusterState(receiver: Option[ActorRef]): Unit = {
    // The state is a mix of converged and latest gossip to mimic what you
    // would have seen if you where listening to the events.
    val state = CurrentClusterState(
      members = latestConvergedGossip.members,
      unreachable = latestGossip.overview.unreachable,
      convergence = latestGossip.convergence,
      seenBy = latestGossip.seenBy,
      leader = latestConvergedGossip.leader)
    receiver match {
      case Some(ref) ⇒ ref ! state
      case None      ⇒ publish(state)
    }
  }

  def subscribe(subscriber: ActorRef, to: Class[_]): Unit = {
    publishCurrentClusterState(Some(subscriber))
    eventStream.subscribe(subscriber, to)
  }

  def unsubscribe(subscriber: ActorRef, to: Option[Class[_]]): Unit = to match {
    case None    ⇒ eventStream.unsubscribe(subscriber)
    case Some(c) ⇒ eventStream.unsubscribe(subscriber, c)
  }

  def publishChanges(newGossip: Gossip): Unit = {
    val oldGossip = latestGossip
    // keep the latestGossip to be sent to new subscribers
    latestGossip = newGossip
    val convergedGossip = latestConvergedGossip
    if (newGossip.convergence) latestConvergedGossip = newGossip
    // first publish the converged diff if there is one
    diff(convergedGossip, latestConvergedGossip) foreach { event ⇒
      event match {
        case _: LeaderChanged | _: MemberEvent ⇒
          publish(event)
        case _ ⇒
        // all other events are taken care of below
      }
    }
    // then publish the diff between the last two gossips
    diff(oldGossip, newGossip) foreach { event ⇒
      event match {
        case _: LeaderChanged | _: MemberEvent ⇒
        // only on convergence
        case UnreachableMember(m) ⇒
          publish(event)
          // notify DeathWatch about unreachable node
          publish(AddressTerminated(m.address))
        case _ ⇒
          // all other events
          publish(event)
      }
    }
  }

  def publishInternalStats(currentStats: CurrentInternalStats): Unit = publish(currentStats)

  def publish(event: AnyRef): Unit = eventStream publish event

  def publishStart(): Unit = clearState()

  def publishDone(receiver: ActorRef): Unit = {
    clearState()
    receiver ! PublishDoneFinished
  }

  def clearState(): Unit = {
    latestGossip = Gossip()
    latestConvergedGossip = Gossip()
  }
}
