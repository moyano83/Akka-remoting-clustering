package part2.clustering

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Address, Props, ReceiveTimeout}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.dispatch.{PriorityGenerator, UnboundedPriorityMailbox}
import akka.pattern.pipe
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Random

object ClusteringExampleDomain {

  case class ProcessFile(fileName: String)

  case class ProcessLine(text: String, aggregator: ActorRef)

  case class ProcessLineResult(countWords: Int)

}

class ClusterWordCountPriorityMailBox(settings:ActorSystem.Settings, config:Config) extends UnboundedPriorityMailbox(
  PriorityGenerator{
    case _:MemberEvent => 0
    case _ => 1
  }
)

class Master extends Actor with ActorLogging {

  import ClusteringExampleDomain._

  implicit val timeout = Timeout(3 seconds)
  val cluster = Cluster(context.system)

  var workers: Map[Address, ActorRef] = Map()
  var pendingRemoval: Map[Address, ActorRef] = Map() // When I have an unreachable member, I need to quarentine it

  override def preStart(): Unit =
    cluster.subscribe(self,
      initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent],
      classOf[UnreachableMember]
    )

  override def postStop(): Unit = cluster.unsubscribe(self)

  override def receive: Receive = handleClusterEvents.orElse(handleWorkerRegistration).orElse(handleJob)

  def handleClusterEvents: Receive = {
    case MemberUp(member) if member.hasRole("worker") =>
      log.info(s"Member is up ${member.address}")
      if (pendingRemoval.contains(member.address)) {
        pendingRemoval = pendingRemoval - member.address
      } else {
        val workerSelection = context.actorSelection(s"${member.address}/user/worker")
        workerSelection.resolveOne().map(ref => (member.address, ref)) pipeTo self
      }
    case UnreachableMember(member) if member.hasRole("worker") =>
      log.info(s"Member is unreachable ${member.address}")
      val workerOption = workers.get(member.address)
      workerOption.foreach { ref =>
        pendingRemoval = pendingRemoval + (member.address -> ref)
      }
    case MemberRemoved(member, previousStatus) =>
      log.info(s"Member removed ${member.address}, removed after ${previousStatus}")
      workers = workers - member.address
    case m: MemberEvent =>
      log.info(s"Another member event: ${m}")
  }

  def handleWorkerRegistration: Receive = {
    case pair: (Address, ActorRef) =>
      log.info(s"Registering worker ${pair}")
      workers = workers + pair
  }

  def handleJob: Receive = {
    case ProcessFile(fileName) =>
      val aggregator = context.actorOf(Props[Aggregator], "aggregator")
      scala.io.Source.fromFile(fileName).getLines().foreach { line =>
        // This is going to fill the actor mailbox with 4000 ProcessLine messages, so in order to process the member
        // messages like the one send by the new workers, we need to prioritize those types of messages
        self ! ProcessLine(line, aggregator)
      }
    case ProcessLine(line, aggregator) =>
      val workerIndex = Random.nextInt((workers -- pendingRemoval.keys).size)
      // This effectively picks up a random worker from the available ones
      val worker: ActorRef = (workers -- pendingRemoval.keys).values.toSeq(workerIndex)
      worker ! ProcessLine(line, aggregator)
      // We introduce a bit of delay so we can include the elastic inclusion of new workers
      Thread.sleep(10)
  }
}

class Aggregator extends Actor with ActorLogging {

  import ClusteringExampleDomain._

  context.setReceiveTimeout(3 seconds)

  override def receive: Receive = online(0)


  def online(totalCount: Int): Receive = {
    case ProcessLineResult(count) => context.become(online(totalCount + count))
    case ReceiveTimeout =>
      // This reports the total count if we don't receive any message in less than 3 seconds
      log.info(s"Total count ${totalCount}")
      context.setReceiveTimeout(Duration.Undefined)
  }
}

class Worker extends Actor with ActorLogging {

  import ClusteringExampleDomain._

  override def receive: Receive = {
    case ProcessLine(text, aggregator) =>
      log.info(s"Processing ${text}")
      aggregator ! ProcessLineResult(text.split(" ").length)
  }
}

object SeedNode extends App {

  import ClusteringExampleDomain._

  def createNode(port: Int, role: String, props: Props, actorName: String) = {
    val config = ConfigFactory.parseString(
      s"""
         |akka.cluster.roles = ["${role}"]
         |akka.remote.artery.canonical.port = ${port}
         |""".stripMargin).withFallback(ConfigFactory.load("part2/clustering/clusteringExample.conf"))

    val system = ActorSystem("ClusteringExample", config)
    system.actorOf(props, actorName)
  }

  val master = createNode(2551, "master", Props[Master], "master")
  val worker1 = createNode(2552, "worker", Props[Worker], "worker")
  val worker2 = createNode(2553, "worker", Props[Worker], "worker")

  Thread.sleep(10000) // We give the cluster time to spin up itself
  master ! ProcessFile("src/main/resources/txt/lipsum.txt")
}

/**
 * This App is to be run after starting the SeedNode one, to prove the elastic incorporation of new workers to the cluster
 */
object AdditionalWorker extends App {
  val config = ConfigFactory.parseString(
    s"""
       |akka.cluster.roles = ["worker"]
       |akka.remote.artery.canonical.port = 2554
       |""".stripMargin).withFallback(ConfigFactory.load("part2/clustering/clusteringExample.conf"))

  val system = ActorSystem("ClusteringExample", config)
  system.actorOf(Props[Worker], "worker")

}