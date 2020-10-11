package part2.clustering

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{InitialStateAsEvents, MemberEvent, MemberRemoved, MemberUp}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import part2.clustering.ChatDomain.{ChatMessage, EnterRoom, UserMessage}

import scala.concurrent.duration._
class L3_ClusterChat {

}

object ChatDomain{
  case class ChatMessage(nickname:String, contents:String)
  case class UserMessage(contents:String)
  case class EnterRoom(fullAddress:String, nickname:String)
}

object ChatActor{
  def props(nickName:String, port:Int) = Props(new ChatActor(nickName, port))
}
class ChatActor(nickname:String, port:Int) extends Actor with ActorLogging{
  //TODO:
  // 1 - Initialize Cluster object
  // 2 - subscribe to cluster events in preStart
  // 3 - unsubscribe to events in postStop
  // 4 - send a Special EnterRoom message to the chat actor deployed on the new node (with actor selection)
  // 5 - Remove the member from your data structure on member remove event
  // 6 - Add the member to your data structure in EnterRoom Event
  // 7 - On UserMessage broadcast the content as chat message to the rest of the cluster members

  implicit val timeout = Timeout(3 seconds)
  val cluster = Cluster(context.system)

  override def preStart(): Unit = {
    cluster.subscribe(
      self,
      InitialStateAsEvents,
      classOf[MemberEvent] // We don't care about other events
    )
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }
  override def receive: Receive = {
    withChatRoom(Map())
  }

  /**
   * The initializard receive method of the actor
   * @param chatActors association between addresses and nicknames
   * @return a receive handler
   */
  def withChatRoom(chatActors:Map[String, String]):Receive = {
    case MemberUp(member) =>
      log.info(s"Welcome to new member ${member.address}")
      val remoteChatActorSelection = getActorSelection(member.address.toString)
      remoteChatActorSelection ! EnterRoom(s"${self.path.address}@localhost:${port}", nickname)
    case MemberRemoved(member, _) =>
      val remoteNickname = chatActors(member.address.toString)
      log.info(s"${remoteNickname} left the room")
      context.become(withChatRoom(chatActors - member.address.toString))
    case EnterRoom(address, remoteNickname) =>
      if (nickname != remoteNickname) {
        log.info(s"Received EnterRoom message with address [${address}] and nickName [${remoteNickname}]")
        context.become(withChatRoom(chatActors + (address -> nickname)))
      }
    case UserMessage(msg) => chatActors.keys.foreach { remoteAddressAsString =>
      getActorSelection(remoteAddressAsString) ! ChatMessage(nickname, msg)
    }
    case ChatMessage(nickname, contents) => log.info(s"Remote ${nickname} said: ${contents}")
  }
  def getActorSelection(memberAddress:String) = context.actorSelection(s"${memberAddress}/user/chatActor")
}

class ChatApp(nickname:String, port:Int) extends App{
  import ChatDomain._
  val config = ConfigFactory.parseString(s"akka.remote.artery.canonical.port=${port}")
    .withFallback(ConfigFactory.load("part2/clustering/clusterChat.conf"))

  val system = ActorSystem("testClusterChat", config)
  val chatActor = system.actorOf(ChatActor.props(nickname, port), "chatActor")

  scala.io.Source.stdin.getLines().foreach{
    line => chatActor ! UserMessage(line)
  }
}

object Alice extends ChatApp("Alice", 2551)
object Bob extends ChatApp("Bob", 2552)
object Peter extends ChatApp("Peter", 2553)