package part1.remoting

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Address, AddressFromURIString, PoisonPill, Props}
import akka.routing.FromConfig
import com.typesafe.config.ConfigFactory

object WordCountDomainRemote {

  case class Initialize(nWorkers: Int)

  case class WordCountTask(text: String)

  case class WordCountResult(count: Int)

  case object EndWordCount

}

class WordCountWorkerRemote extends Actor with ActorLogging {

  import WordCountDomainRemote._

  override def receive: Receive = {
    case WordCountTask(text) =>
      log.info(s"Processing ${text}")
      sender() ! WordCountResult(text.split(" ").length)
  }
}

class WordCountMasterRemote extends Actor with ActorLogging {

  import WordCountDomainRemote._

  val router = context.actorOf(FromConfig.props(Props[WordCountWorker]), "WorkerRouter")

  override def receive: Receive = onlineWithRouter(0,0)

  def onlineWithRouter(remainingTasks:Int, totalCount:Int):Receive = {
    case text: String =>
      val sentences = text.split("\\. ")
      // Send sentences to the worker router
      sentences.foreach(sentence => router ! WordCountTask(sentence))
      context.become(onlineWithRouter(remainingTasks + sentences.length, totalCount))
    case WordCountResult(n) =>
      if (remainingTasks == 1) {
        log.info(s"Finished tasks, total result is ${totalCount + n}")
        context.stop(self)
      } else {
        context.become(onlineWithRouter(remainingTasks - 1, totalCount + n))
      }
  }

  def directSelectionWithoutRouter(nWorkers:Int) = {
    case Initialize(n) =>
      // TODO deploy the workers remotely on the workers APP
      log.info("Master initializing")
      val remoteSystemAddress:Address = AddressFromURIString("akka://WorkersSystem@localhost:2552")
      val workers = (0 to n).map(i => context.actorOf(Props[WordCountWorkerRemote], s"worker${i}"))

      context.become(online(workers.toList, 0, 0))
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

object MasterRemoteApp extends App{
  import WordCountDomainRemote._
  val config = ConfigFactory.parseString("""
      |akka.remote.artery.canonical.port = 2551
      |""".stripMargin)
    .withFallback(ConfigFactory.load("part1/remoting/deployingActorsRemotelyExercise.conf"))

  val actorSystem = ActorSystem("MasterSystem", config)
  val master = actorSystem.actorOf(Props[WordCountMasterRemote], "wordCountMaster")

  master ! Initialize(5)
  Thread.sleep(1000)
  scala.io.Source.fromFile("src/main/resources/txt/lipsum.txt").getLines().foreach(line => master ! line)
}

object WorkersRemoteApp extends App{
  val config = ConfigFactory.parseString("""
                                           |akka.remote.artery.canonical.port = 2552
                                           |""".stripMargin)
    .withFallback(ConfigFactory.load("part1/remoting/deployingActorsRemotelyExercise.conf"))

  val actorSystem = ActorSystem("WorkersSystem", config)

}
