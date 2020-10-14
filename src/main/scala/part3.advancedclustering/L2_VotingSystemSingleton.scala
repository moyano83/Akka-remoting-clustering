package part3.advancedclustering

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill, Props, ReceiveTimeout}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.util.Random

case class Person(id: String, age: Int)

object Person {
  def generate() = Person(UUID.randomUUID().toString, 16 + Random.nextInt(80))
}

case class Vote(person: Person, candidate: String)

case object VoteAccepted

case class VoteRejected(reason: String)

class VotingAggregator extends Actor with ActorLogging {
  val CANDIDATES = (1 to 4).map(i=>s"CANDIDATE${i}")

  context.setReceiveTimeout(20 seconds)

  override def receive: Receive = online(Set(), Map())

  def online(personVoted: Set[String], polls: Map[String, Int]): Receive = {
    case Vote(Person(id, age), candidate) =>
      if (personVoted.contains(id)) sender() ! VoteRejected("The person has already voted")
      else if (age < 18) sender() ! VoteRejected("The person is under the age of voting")
      else if (!CANDIDATES.contains(candidate)) sender() ! VoteRejected("Invalid candidate")
      else {
        log.info(s"Registering vote for candidate ${candidate}")
        sender() ! VoteAccepted
        val votes = polls.getOrElse(candidate, 0)
        context.become(online(personVoted + id, polls + (candidate -> (votes + 1))))
      }
    case ReceiveTimeout =>
      log.info(s"Here are the vote results: ${polls}")
      context.setReceiveTimeout(Duration.Undefined)
      context.become(offline())
  }

  def offline(): Receive = {
    case v: Vote =>
      log.warning("Receive vote outside voting window")
      sender() ! VoteRejected("Vote received after polls close")
    case m => log.warning("Will not process any more messages")
  }
}

class VotingStation(votingAggregator: ActorRef) extends Actor with ActorLogging {
  override def receive: Receive = {
    case v: Vote => votingAggregator ! v
    case VoteAccepted => log.info("Vote was accepted")
    case VoteRejected(reason) => log.warning(s"Vote was rejected due to ${reason}")
  }
}

object VotingStation{
  def props(proxy:ActorRef) = Props(new VotingStation(proxy))
}

object CentralElectionSystem extends App {
  def startNode(port: Int) = {
    val config = ConfigFactory.parseString(s"akka.remote.artery.canonical.port=${port}")
      .withFallback(ConfigFactory.load("part3/advancedclustering/votingSystemSingleton.conf"))

    val system = ActorSystem("votingSystem", config)

    system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = Props[VotingAggregator],
        terminationMessage = PoisonPill,
        settings = ClusterSingletonManagerSettings(system)
      ), "votingAggregator")
  }

  (2551 to 2553).foreach(startNode)
}

class VotingStationApp(port: Int) extends App {
  val config = ConfigFactory.parseString(s"akka.remote.artery.canonical.port=${port}")
    .withFallback(ConfigFactory.load("part3/advancedclustering/votingSystemSingleton.conf"))

  val system = ActorSystem("votingSystem", config)

  // TODO: Set up communication to the cluster singleton
  val proxyAggregator = system.actorOf(
    ClusterSingletonProxy.props(
      singletonManagerPath = "/user/votingAggregator", //We need to pass the path to the actor we want to communicate with
      settings = ClusterSingletonProxySettings(system) // Cluster settings, derived from the actor system
    ),
    "votingAggregatorProxy"
  )
  val votingStation = system.actorOf(VotingStation.props(proxyAggregator))
  // TODO: Read from stdin and
  scala.io.Source.stdin.getLines().foreach(line => proxyAggregator ! Vote(Person.generate(), line))
}

object London extends VotingStationApp(2561)

object Leeds extends VotingStationApp(2562)

object Liverpool extends VotingStationApp(2563)

// NOTE!!! When setting a stateful actor as a singleton, whenever the actor gets moved the state is lost, so you need to
// create it as a persistent actor to not lose the state.