package part3.advancedclustering

import java.util.{Date, UUID}

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion.Passivate
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import com.typesafe.config.ConfigFactory

import scala.util.Random
import scala.concurrent.duration._


case class OysterCard(id:String, amount:Double)
case class EntryAttempt(oysterCard: OysterCard, date:Date = new Date)
case object EntryAccepted
case class EntryRejected(reason:String)
case object TerminateValidator
/////////////////////////////////////////////////////
/////////// ACTORS
/////////////////////////////////////////////////////
class Turnstile(validator:ActorRef) extends Actor with ActorLogging{
  override def receive: Receive = {
    case o: OysterCard => validator ! EntryAttempt(o)
    case EntryAccepted => log.info("Entry Accepted")
    case EntryRejected(reason) => log.warning(s"Entry rejected: ${reason}")
  }
}
object Turnstile{
  def props(ref:ActorRef) = Props(new Turnstile(ref))
}

class OysterCardValidator extends Actor with  ActorLogging{
  /**
   * Imagine this card stores an enormous amount of data, all trips from the last time it was persisted
   */
  override def preStart(): Unit = {
    super.preStart()
    log.info("Validator started")
  }

  context.setReceiveTimeout(10 seconds)

  override def receive: Receive = {
    case EntryAttempt(oyster @ OysterCard(id, amount), _) =>
      log.info(s"Validating card ${id}")
      if(amount <= 2.5) {
        log.info("GREEN: Entry accepted")
        sender() ! EntryAccepted
      }
      sender() !  EntryRejected(s"card [${id}]: Payment rejected, not enough funds")
    // PASSIVATION: It is used for idle entities which are using memory, you need to send a Passivate(YourSpecialMessage) to
    // the parent. You usually send this message with a receiveTimeout message, and when received, the shardRegion sends back
    // a YourSpecialMessage and sends no more messages. This is a signal for the entity to safely shut down
    case ReceiveTimeout =>
      context.parent ! Passivate(TerminateValidator)
    // When received this message is safe to stop the actor because the ShardRegion won't send me any more messages
    case TerminateValidator => context.stop(self)
  }
}

/////////////////////////////////////////////////////
/////////// SHARDING LOGIC
/////////////////////////////////////////////////////
object TurnstileSettings{
  val numberOfShards = 10 // Uses 10 times the number of nodes in your cluster
  val numberOfEntities = 100 // Uses 10 times the number of shards

  // This value needs to be a PF from message to  [String, Msg]
  val extractEntityId:ShardRegion.ExtractEntityId = {
    case attempt@ EntryAttempt(OysterCard(id, amount), date) =>
      val entityId = id.hashCode.abs % numberOfEntities // this function generates the same entity for the same messages
      (entityId.toString, attempt)
  }
  // Similarly, we need to define a PF from a message to a String (the shard Id)
  val extractShardId: ShardRegion.ExtractShardId = {
    case EntryAttempt(OysterCard(id, _), _) => (id.hashCode.abs % numberOfShards).toString

    // This message is processed when the node is rebalanced, so we can programatically recreate entities. For the
    // entityId we need to return the shard entity that owns it. For Every message, the extractEntityId and extractShardId
    // should always be consistent, meaning there must be no two messages m1 and m2 for which
    // m1.extractEntityId == m2.extractEntityId but m1.extractShardId != m2.extractShardId
    // With this remember entity feature (the PF below):
    // entityId -> shardId, then FORALL messages M, if extractEntityId(M) == id, then extractShardId(M) must be shardId
    case ShardRegion.StartEntity(entityId) => (entityId.hashCode.abs % numberOfShards).toString
  }
}

/////////////////////////////////////////////////////
/////////// CLUSTER NODES
/////////////////////////////////////////////////////
class TubeStation(port:Int, numberOfTurnstiles:Int) extends App{
  val config = ConfigFactory
    .parseString(s"akka.remote.artery.canonical.port=${port}")
    .withFallback(ConfigFactory
      .load("part3/advancedclustering/clusterShardingExample.conf")
    )
  val system = ActorSystem("clusterSharding", config)

  // Setting up the cluster sharding:
  // This expression returns the reference of the shard region that is going to be deployed on this node
  val validatorShardRegion:ActorRef = ClusterSharding.apply(system).start(
    typeName = "OysterCardValidator",
    entityProps = Props[OysterCardValidator],
    settings = ClusterShardingSettings(system).withRememberEntities(true), // This is to enable the remember entities
    // features that we created in the TurnstileSettings class.
    TurnstileSettings.extractEntityId,
    TurnstileSettings.extractShardId
  )

  val turnstiles = (1 to numberOfTurnstiles).map(_ => system.actorOf(Turnstile.props(validatorShardRegion)))
  Thread.sleep(10000) // Give the cluster time to start
  for(_ <- 1 to 10000){
    val randomTurnstileIndex = Random.nextInt(numberOfTurnstiles)
    val randomTurnstile = turnstiles(randomTurnstileIndex)

    randomTurnstile ! OysterCard(UUID.randomUUID().toString, Random.nextDouble() * 10) // Returns a double between 0 and 10
    Thread.sleep(2000) // we mimic the turnstile behaviour
  }
}

object PicadillyCircus extends TubeStation(2551, 15)
object CharingCross extends TubeStation(2551, 10)
object Westmister extends TubeStation(2552, 5)
object Brixton extends TubeStation(2552, 25)

// When a node crashes:
// 1 - The coordinator will distribute the dead shards into the existing nodes
// 2 - New entities will be created on demand on the existing nodes
// 3 - Messages to the dead shards will be buffered until consensus is reached on the cluster

// When the node that crashes contains the shard coordinator:
// 1 - The coordinator is moved onto another node
// 2 - During the handover, the messages are buffered
// 3 - All messages requiring the coordinator are buffered (i.e. where is shard XX?)

// When a shard is rebalanced, it can recreate the entities that it has, but the entity states are not recovered

