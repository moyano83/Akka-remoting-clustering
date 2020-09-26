package part1.remoting

import akka.actor.{Actor, ActorLogging, ActorSystem, Address, AddressFromURIString, Deploy, PoisonPill, Props, Terminated}
import akka.remote.RemoteScope
import akka.routing.FromConfig
import com.typesafe.config.ConfigFactory

object L3_DeployingActorsRemotely_LocalApp extends App{

  val system = ActorSystem("LocalActorSystem",
    ConfigFactory.load("part1/remoting/deployingActorsRemotely.conf").getConfig("localApp"))
  val simpleActor = system.actorOf(Props[SimpleActor],"remoteActor")
  // The name of the actor is checked in the config for remote deployment, if it doesn't exists it deploys it locally.
  // The 'Props' passed to 'actorOf' will be sent to the remote system. The remote system will create the actor there with
  // the props passed, and it returns an actorRef. The Props object needs to be serializable (do not use lambdas). The
  // actor class needs to be in the remote JVM classpath.
  simpleActor ! "Hello remote actor"
  // Expected something like akka://RemoteActorSystem@localhost:2552/user/remoteActor, but instead we have something like:
  // akka://RemoteActorSystem@localhost:2552/remote/akka/LocalActorSystem@localhost:2551/user/remoteActor#1868587468
  // This is because there is a guardian in the remote system. For that if we do actor selection, we need that whole address.
  println(simpleActor)

  // You can also do programmatic deployment
  val remoteSystemAddress:Address = AddressFromURIString("akka://RemoteActorSystem@localhost:2552")
  val remotelyDeployedActor = system.actorOf(Props[SimpleActor].withDeploy(Deploy(scope = RemoteScope(remoteSystemAddress))))
  remotelyDeployedActor ! "Hi again remote actor"

  // How to use routers with remote routees
  // A pool router is a router that creates its own children, in this case remotely
  val poolRouter = system.actorOf(FromConfig.props(Props[SimpleActor]), "myRouterWithRemoteChildren")
  (1 to 10).map(i => s"Message ${i}").foreach(poolRouter ! _)

  // Watching remote actors
  class ParentActor extends Actor with ActorLogging{
    override def receive: Receive = {
      case "create" =>
        log.info("Creating remote child")
        val child = context.actorOf(Props[SimpleActor], "remoteChild")
        context.watch(child) // register this actor for the deat watch
      case Terminated(ref) => log.warning(s"Child ref ${ref} terminated")
    }
  }

  val parentActor = system.actorOf(Props[ParentActor], "watcher")
  parentActor ! "create"
  Thread.sleep(1000)
  val child =
    system.actorSelection("akka://RemoteActorSystem@localhost:2552/remote/akka/LocalActorSystem@localhost:2551/user/watcher/remoteChild")

  // The remotely deployed actor was killed and the local watcher was notified, but who sends the terminated message?
  // If we stop the remote app, after a few seconds we will see an "associated terminated" message, this is because the
  // remote actor system was detected unreachable. This mechanism is called the Phi accrual failure detector. This works
  // like this:
  // - Actor system sends heart beat messages once a connection is established (most common cases is on sending messages
  // or on deploying a remote actor)
  // - If heart beat times out, its reach score (PHI) increases.
  // - If this score pases a threshold (defaults to 10), the connection is quarantined (means unreachable)
  // - If this happens, the local actor system sends Terminated messages to Death Watchers of remote actors
  // - The remote actor system must be restarted to reestablish connection
  child ! PoisonPill

}

object L3_DeployingActorsRemotely_RemoteApp extends App {

  val system = ActorSystem("RemoteActorSystem",
    ConfigFactory.load("part1/remoting/deployingActorsRemotely.conf").getConfig("remoteApp"))
}
