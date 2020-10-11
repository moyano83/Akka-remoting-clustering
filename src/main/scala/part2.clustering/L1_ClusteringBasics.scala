package part2.clustering

import akka.actor.{Actor, ActorLogging, ActorSystem, Address, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{InitialStateAsEvents, MemberEvent, MemberJoined, MemberRemoved, MemberUp, UnreachableMember}
import com.typesafe.config.ConfigFactory

class ClusterSubscriber extends Actor with ActorLogging{

  val cluster = Cluster(context.system)

  override def preStart(): Unit = {
    cluster.subscribe(
      self, // The actor system will send the events below (MemberEvent, UnreachableMember) to this actor whenever a new node join the cluster
      // If this actor joins the cluster later, the actor would receive the same
      // messages as it has started at the spin up of the cluster
      initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent],
      classOf[UnreachableMember]
    )
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  override def receive: Receive = {
    case MemberJoined(member) => log.info(s"New member: ${member.address}")
    case MemberUp(member) if member.hasRole("numberCruncher") => log.info(s"Welcome to the number cruncher: ${member.address}")
    case MemberUp(member) => log.info(s"Welcome to the cluster: ${member.address}")
    case MemberRemoved(member, previousStatus) => log.warning(s"Member ${member.address} left. Previous status was ${previousStatus}")
    case UnreachableMember(member) => log.info(s"Unreachable member: ${member.address}")
    case m : MemberEvent => log.info(s"Received event ${m}")
  }
}

object L1_ClusteringBasics extends App {

  def startCluster(ports: List[Int]) = ports.foreach { port =>
      val config = ConfigFactory.parseString(
        s"""
           |akka.remote.artery.canonical.port = ${port}

           |""".stripMargin).withFallback(ConfigFactory.load("part2/clustering/clusteringBasics.conf"))
      val system = ActorSystem("clusteringtest", config) // The actor systems in the cluster must have the same name
      system.actorOf(Props[ClusterSubscriber], "ClusterSubscriber")
    }

  startCluster(List(2551, 2552, 0))
}

object ClusteringBasicsManualRegistration extends App{
  val system = ActorSystem(
    "clusteringtest",
    ConfigFactory
      .load("part2/clustering/clusteringBasics.conf")
      .getConfig("manualRegistration")
  )

  val cluster = Cluster(system) // Cluster.get(system)

  def joinExistingCluster =
    cluster.joinSeedNodes(List(
      Address("akka", "clusteringtest", "localhost", 2551), // akka://clusteringtest@localhost:2551
      Address("akka", "clusteringtest", "localhost", 2552)  // equivalent with AddressFromURIString("akka://clusteringtest@localhost:2552")
    ))

  def joinExistingNode =
    cluster.join(Address("akka", "clusteringtest", "localhost", 50466))

  def joinMyself =
    cluster.join(Address("akka", "clusteringtest", "localhost", 2555))

  joinExistingCluster

  system.actorOf(Props[ClusterSubscriber], "manualClusterSubscriber")
}
