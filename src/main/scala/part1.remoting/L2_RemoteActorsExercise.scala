package part1.remoting

import akka.actor.{Actor, ActorIdentity, ActorLogging, ActorRef, ActorSystem, Identify, PoisonPill, Props}
import com.sun.corba.se.impl.orb.ORBConfiguratorImpl.ConfigParser
import com.typesafe.config.ConfigFactory

object WordCountDomain {

  case class Initialize(nWorkers: Int)

  case class WordCountTask(text: String)

  case class WordCountResult(count: Int)

  case object EndWordCount

}

class WordCountWorker extends Actor with ActorLogging {

  import WordCountDomain._

  override def receive: Receive = {
    case WordCountTask(text) =>
      log.info(s"Processing ${text}")
      sender() ! WordCountResult(text.split(" ").length)
  }
}

class WordCountMaster extends Actor with ActorLogging {

  import WordCountDomain._

  override def receive: Receive = {
    case Initialize(n) =>
      (1 to n).map(i => context.actorSelection(s"akka://WorkersSystem@localhost:2552/user/Worker_${i}")).foreach {
        _ ! Identify("worker")
      }
      context.become(initializing(n, Nil))
  }

  def initializing(left:Int, actors:List[ActorRef]) :Receive = {
    case ActorIdentity(_, Some(ref)) =>
      log.info("Received message ActorIdentity")
      val listActors = ref :: actors
      val remaining = left -1
      if(remaining == 0) context.become(initializing(remaining, listActors))
      else context.become(online(listActors, 0, 0))
  }

  def online(workers:List[ActorRef], remainingTasks:Int, totalCount:Int):Receive = {
    case text:String =>
      val sentences = text.split("\\. ")
      // This iterates over the list of workers, when the iterator is exhausted it starts again
      // So this sends sentences to workers in turn
      Iterator.continually(workers).flatten.zip(sentences.iterator).foreach{ pair =>
        val (worker, sentence) = pair
        worker ! WordCountTask(sentence)
      }
      context.become(online(workers, remainingTasks + sentences.length, totalCount))
    case WordCountResult(n) =>
      if(remainingTasks == 1){
        log.info(s"Finished tasks, total result is ${totalCount + n}")
        workers.foreach(_ ! PoisonPill)
        context.stop(self)
      }else{
        context.become(online(workers, remainingTasks - 1, totalCount + n))
      }
  }
}

object MasterApp extends App{
  import WordCountDomain._
  val config = ConfigFactory.parseString("""
      |akka.remote.artery.canonical.port = 2551
      |""".stripMargin)
    .withFallback(ConfigFactory.load("part1/remoting/remoteActorsExercise.conf"))

  val actorSystem = ActorSystem("MasterSystem", config)
  val master = actorSystem.actorOf(Props[WordCountMaster], "wordCountMaster")

  master ! Initialize(5)
  Thread.sleep(1000)
  scala.io.Source.fromFile("src/main/resources/txt/lipsum.txt").getLines().foreach(line => master ! line)
}

object WorkersApp extends App{
  import WordCountDomain._
  val config = ConfigFactory.parseString("""
                                           |akka.remote.artery.canonical.port = 2552
                                           |""".stripMargin)
    .withFallback(ConfigFactory.load("part1/remoting/remoteActorsExercise.conf"))

  val actorSystem = ActorSystem("WorkersSystem", config)
  val master = actorSystem.actorOf(Props[WordCountMaster], "wordCountMaster")

  (1 to 5).map(elem => actorSystem.actorOf(Props[WordCountWorker], s"Worker_${elem}"))
  Thread.sleep(1000)
  scala.io.Source.fromFile("src/main/resources/txt/lipsum.txt").getLines().foreach(line => master ! line)
}
