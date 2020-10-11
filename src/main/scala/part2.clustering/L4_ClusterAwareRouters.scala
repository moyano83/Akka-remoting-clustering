package part2.clustering

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.routing.FromConfig
import com.typesafe.config.ConfigFactory

case class SimpleTask(contents: String)
case object StartWork

class MasterWithRouter extends Actor with ActorLogging{

  val router = context.actorOf(FromConfig.props(Props[SimpleRoutee]), "clusterAwareRouter")

  override def receive(): Receive = {
    case StartWork =>
      log.info("Starting work!!")
      (1 to 100).foreach(id =>{
        router ! SimpleTask(s"Simple task ${id}")
      })
  }
}

class SimpleRoutee extends Actor with ActorLogging{
  override def receive(): Receive = {
    case SimpleTask(content) =>
      log.info(s"Received ${content}")
  }
}

object RouteesApp extends App{
  def startRouteeNode(port:Int) = {
    val config = ConfigFactory.parseString(s"akka.remote.artery.canonical.port=${port}")
      .withFallback(ConfigFactory.load("part2/clustering/clusterAwareRouters.conf"))
    val system = ActorSystem("testRouters", config)
  }

  // Note that this doesn't create any actors, they'll be created by the router and the tasks would be send in order to
  // the routees. The path of the actors created would be something like this:
  // akka://testRouters@localhost:2552/remote/akka/testRouters@localhost:2555/user/master/clusterAwareRouter/c9
  startRouteeNode(2551)
  startRouteeNode(2552)
}

object RouteesGroupApp extends App{
  def startRouteeNode(port:Int) = {
    val config = ConfigFactory.parseString(s"akka.remote.artery.canonical.port=${port}")
      .withFallback(ConfigFactory.load("part2/clustering/clusterAwareRouters.conf"))
    val system = ActorSystem("testRouters", config)
    // In this case we need to instantiate the actors as they won't be created by the router itself
    system.actorOf(Props[SimpleRoutee], "worker")
  }
  startRouteeNode(2551)
  startRouteeNode(2552)
}

object MasterApp extends App{
  val mainConfig = ConfigFactory.load("part2/clustering/clusterAwareRouters.conf")
  // val config= mainConfig.getConfig("masterWithRouterApp").withFallback(mainConfig)
  val config= mainConfig.getConfig("masterWithGroupRouterApp").withFallback(mainConfig)
  val system = ActorSystem("testRouters", config)

  val masterActor = system.actorOf(Props[MasterWithRouter], "master")
  Thread.sleep(10000) // we introduce delay to let the cluster initialize
  masterActor ! StartWork
}