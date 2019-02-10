import java.util.UUID
import CountActor.VoteResult
import ListActor.VoteTime
import VoteActor.VoteVoice
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSelection, ActorSystem, Props}
import com.typesafe.config.{Config, ConfigFactory}
import scala.collection.mutable
import scala.util.Random

class ListActor(counterUrl:String) extends Actor with ActorLogging {
  import Conf._
  var name: String = DEFAULT_NAME
  var vote:VoteTime = _
  var counterActor:ActorSelection = _
  override def preStart(): Unit = counterActor = context.actorSelection(counterUrl)
  override def receive: Receive = {
    case msg:String if msg.toLowerCase.startsWith(NAME_ORDER) => name = msg.replace(NAME_ORDER,"").trim
    case msg:String if msg.toLowerCase.startsWith(START_ORDER) =>
      val list = msg.replace(START_ORDER,"")
        .split(" ").map(_.trim).filter(!_.isEmpty).map(people => (people.hashCode, people)).toList
      vote = VoteTime(UUID.randomUUID(), name, list = list)
      log.info("Create A new VoteTime, sending to CounterActor now...")
      log.info(vote.toString)
      counterActor ! vote
    case msg:String if msg.toLowerCase.startsWith(CLEAR_ORDER) =>
      name = DEFAULT_NAME; vote = null
    case msg:String if msg.toLowerCase.startsWith(VOTE_ORDER) =>
      log.info("Send Vote Information to " + sender())
      sender() ! vote
    case _ =>
  }
}
object ListActor {
  case class Second(second:Int)
  implicit class IntWithSecond(int:Int) { def seconds = Second(int) }
  case class VoteTime(uuid:UUID, name:String, voteUntil:Second = 100 seconds, list: List[(Int,String)])
}

class VoteActor(countUrl:String, listUrl:String) extends Actor {
  import Conf._
  var myVote:VoteVoice = _
  var countActor:ActorSelection = _
  var listActor:ActorSelection = _
  override def preStart(): Unit = {
    countActor = context.actorSelection(countUrl)
    listActor = context.actorSelection(listUrl)
  }
  override def receive: Receive = {
    case TO_VOTE_ORDER => listActor ! VOTE_ORDER
    case time: VoteTime if time.list.nonEmpty =>
      import context.dispatcher
      import scala.concurrent.duration._
      val durationTime = Random.nextInt(time.voteUntil.second)
      val message = VoteVoice(time.uuid, time.name, time.list(Random.nextInt(time.list.length))._1)
      context.system.scheduler.scheduleOnce(Random.nextInt(durationTime + 1) seconds, () => countActor ! message)
  }
}
object VoteActor {
  case class VoteVoice(uuid: UUID, name:String, vote:Int)
}

class CountActor extends Actor with ActorLogging {
  var list:VoteResult = _
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = log.info("Counter Actor Start now...")
  override def receive: Receive = {
    case voteTime: VoteTime =>
      log.info("Receive Vote Request, clean List, and Ready for VoteVoice Now")
      list = VoteResult(voteTime.uuid, voteTime.name, voteTime.list, voteTime.list.map(people => (0, people._1, people._2)).toBuffer)
      import context.dispatcher
      import scala.concurrent.duration._
      context.system.scheduler.scheduleOnce((voteTime.voteUntil.second + Conf.NET_LAG) seconds, () => {
        log.info("Stop Receive Vote, It is end now...")
        list = null
      })
    case VoteVoice(uuid, _, vote) => list match {
      case null =>
      case _ if list.uuid == uuid =>
        //log.info("Receive A new Vote +++++++++++++++")
        val people = list.result.filter(_._2 == vote).toList.head
        list.result -= people
        list.result += ((people._1 + 1, people._2, people._3))
        //log.info(s"Total Size now is ${list.result.map(_._1).sum}")
        context.actorOf(Props[BoardCastActor]) ! list
    }
  }
}
object CountActor {
  case class VoteResult(uuid:UUID, name:String, list: List[(Int,String)], result:mutable.Buffer[(Int,Int,String)])
}

class BoardCastActor extends Actor with ActorLogging {
  override def receive: Receive = {
    case list: VoteResult =>
      val size = list.result.map(_._1).sum
      size % Conf.EACH_COLLECT match {
        case 0 =>
          log.info(s"Current Result is: ")
          val sortedList = list.result.sortBy(_._1).reverse
          log.info("\n" + sortedList.mkString("\n"))
          log.info(s"Total Size now is $size")
        case _ =>
      }
    case _ =>
  }
}

object Conf {
  val DEFAULT_NAME = "Game"
  val START_ORDER = "start"
  val NAME_ORDER = "name"
  val CLEAR_ORDER = "clear"
  val VOTE_ORDER = "vote"
  val TO_VOTE_ORDER = "toVote"
  val NET_LAG = 50
  val EACH_COLLECT = 1
  val (counterSystemName, counterName, counterHost,counterPort) = ("CounterSystem","Counter","127.0.0.1",2333)
  val (listSystemName, listName, listHost,listPort) = ("ListSystem","Lister","127.0.0.1",2334)
  val (voteSystemName, voteName, voterHost,voterPort) = ("VoterSystem","Voter","127.0.0.1",2335)
  val counterUrl = s"akka.tcp://$counterSystemName@$counterHost:$counterPort/user/$counterName"
  val listUrl = s"akka.tcp://$listSystemName@$listHost:$listPort/user/$listName"
  def getConfig(host:String,port:Int): Config = {
    ConfigFactory.parseString(
      s"""
         |akka.actor.provider="akka.remote.RemoteActorRefProvider"
         |akka.remote.netty.tcp.hostname=$host
         |akka.remote.netty.tcp.port=$port
     """.stripMargin)
  }
}
object CounterSystem extends App {
  val father = ActorSystem(Conf.counterSystemName, Conf.getConfig(Conf.counterHost, Conf.counterPort))
  father.actorOf(Props[CountActor],Conf.counterName)
}
object ListSystem extends App {
  val mother = ActorSystem(Conf.listSystemName, Conf.getConfig(Conf.listHost, Conf.listPort))
  private val lister = mother.actorOf(Props(new ListActor(Conf.counterUrl)),Conf.listName)
  lister ! Conf.CLEAR_ORDER
  lister ! s"${Conf.NAME_ORDER} 最佳员工评选"
  lister ! s"${Conf.START_ORDER} 张三 李四 王五 马六 刘七 孙八 王九"
}
object VoterSystem extends App {
  val god = ActorSystem(Conf.voteSystemName, Conf.getConfig(Conf.voterHost, Conf.voterPort))
  val buffer = mutable.ArrayBuffer[ActorRef]()
  1.to(5).foreach(index => {
    val ref = god.actorOf(Props(new VoteActor(Conf.counterUrl, Conf.listUrl)),s"${Conf.voteName}-$index")
buffer += ref
})
buffer.foreach(ref => ref ! Conf.TO_VOTE_ORDER)
}
