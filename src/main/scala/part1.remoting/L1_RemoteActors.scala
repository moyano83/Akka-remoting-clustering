package part1.remoting

import akka.actor.{Actor, ActorIdentity, ActorLogging, ActorSystem, Identify, Props}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object L1_RemoteActors extends App {
  // Actor system 1 default loads akka
  val localSystem = ActorSystem("LocalSystem", ConfigFactory.load("part1/remoting/remoteActors.conf"))
  val localSimpleActor = localSystem.actorOf(Props[SimpleActor], "LocalSimpleActor")
  localSimpleActor ! "Hello local actor"

  // Send a message to the remote actor from this local JVM
  // Method  1: Actor selection
  val remoteActorSelector = localSystem.actorSelection("akka://RemoteSystem@localhost:2552/user/RemoteSimpleActor")
  remoteActorSelector ! "Hello from the \"local\" system"

  // Method 2: Resolve the actor selection to an actor ref
  // This uses a variant of method number 3 behind the scenes
  import localSystem.dispatcher

  implicit val timeout = Timeout(3 seconds)
  val remoteActorRefFuture = remoteActorSelector.resolveOne()
  remoteActorRefFuture.onComplete {
    case Success(ref) => ref ! "I resolved this actor within the future"
    case Failure(exception) => println(s"God ${exception.getLocalizedMessage}")
  }

  // Method 3: Actor identification
  // - The actor resolver would ask for an actor selection from the local actor system
  // - The actor resolver would send an special message called Identify(x) with a small value inside this message
  // - The remote actor would automatically respond with an ActorIdentity(x, actorRef)
  // - The actor resolver can now use the remote "actorRef"

  class ActorResolver extends Actor with ActorLogging {

    override def preStart(): Unit = {
      val selection = context.actorSelection("akka://RemoteSystem@localhost:2552/user/RemoteSimpleActor")
      selection ! Identify(42)
    }

    override def receive: Receive = {
      case ActorIdentity(42, Some(ref)) => ref ! s"Thanks for identifying yourself ${ref.path}}"
    }
  }

  val resolver = localSystem.actorOf(Props[ActorResolver], "actorResolver")

}

object RemoteActors extends App {
  // Actor system 1 default loads akka
  val remoteSystem = ActorSystem("RemoteSystem",
    ConfigFactory.load("part1/remoting/remoteActors.conf").getConfig("remoteSystem"))
  val remoteSimpleActor = remoteSystem.actorOf(Props[SimpleActor], "RemoteSimpleActor")
  remoteSimpleActor ! "Hello remote actor"
}
