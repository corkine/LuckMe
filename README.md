> 这是一个使用 Akka 和 Scala 实现的一个分布式投票程序。这个程序 Demo 是因为偶然在知乎看到 360 前端某程序员写的一个小的用 JavaScript 实现的抽奖程序，觉得挺好玩，因此自己也照葫芦画瓢实现了一个后端风格的抽奖程序。因为最近刚学过 Scala，正在学 Akka，因此拿来练练手。

# 灵感来源

这篇使用 JS 实现的抽奖程序在这里：[嘿！这真的是一个正经的抽奖程序！](https://zhuanlan.zhihu.com/p/55646130)

这是它的效果图：

![v2-e1bec8afdc058053898458744dca6af4_b](http://static.mazhangjing.com/v2-e1bec8afdc058053898458744dca6af4_b.gif)


# 投票系统

## 设计概述

这个系统是按照投票系统来设计的，因为是随机投票，因此也可以用来抽奖。以下是 Akka 的设计模型，其中包括了发票者 ListActor、投票者 VoteActor、统计者 CountActor、念票者 BoardCastActor。

Akka 的消息分为字符串类型的常量命令（用圆圈包裹），以及选举、选票、结果三大消息对象。
- 发票者 ListActor 定义的消息有
    - START：开始新投票
    - NAME：命名此投票
    - CLEAR 清空当前投票
    - VoteTime 一次选举
- 投票者 VoteActor 定义的消息有
    - DO VOTE：准备拿票
    - VOTE：去拿票
    - VoteVoice 一张选票
- 统计者 CountActor 定义的消息有
    - VoteResult 当前接受到的选票
- 唱票者 BoardCastActor 没有定义消息

示意图如下所示：

![akka_vote](http://static.mazhangjing.com/akka_vote.png)


红色是发票者 ListActor 的流程，其接受 START 命令开始，接着向统计者提供当前的选举证明，统计者启动一个定时器，然后只接受在定时器作用内的选票。

蓝色是投票者 VoteActor 的流程，其接受 DO VOTE 命令准备投票，之后启动 VOTE 消息，发送给 发票者请求空白选票，之后发票者提供 VoteTime 选举对象，之后随即选择一个被投票者，将其封装到 VoteVoice 中，填好选票，发送给统计者。

统计者收到来自投票人 VoteActor 的选票后，检查其是否属于当此选举，如果属于，且时间合适，则添加到数据池中，由 BoardCastActor 唱票者打印出当前的统计信息。

## 代码展示

```scala
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
```

## 运行截图

```
//VoteActor 准备获取选票
[INFO] [02/10/2019 16:42:44.295] [main] [akka.remote.Remoting] Remoting now listens on addresses: [akka.tcp://VoterSystem@127.0.0.1:2335]
//ListActor 选票派发 500000 人完毕（耗时 1 分钟）
[INFO] [02/10/2019 16:43:46.753] [ListSystem-akka.actor.default-dispatcher-13] [akka.tcp://ListSystem@127.0.0.1:2334/user/Lister] Send Vote Information to Actor[akka.tcp://VoterSystem@127.0.0.1:2335/user/Voter-490958#1690311017]
//CountActor 开始接收选票
[akka.tcp://CounterSystem@127.0.0.1:2333/user/Counter] Receive Vote Request, clean List, and Ready for VoteVoice Now
//CountActor 结束选票完毕
[INFO] [02/10/2019 16:47:31.646] [CounterSystem-akka.actor.default-dispatcher-18] [akka.tcp://CounterSystem@127.0.0.1:2333/user/Counter/$Fe6b] 
(71774,937010,王九)
(71563,774889,张三)
(71480,745778,孙八)
(71445,671467,刘七)
(71412,937065,王五)
(71321,842061,李四)
(71005,1246337,马六)
[INFO] [02/10/2019 16:47:31.646] [CounterSystem-akka.actor.default-dispatcher-18] [akka.tcp://CounterSystem@127.0.0.1:2333/user/Counter/$Fe6b] Total Size now is 500000
```

CPU 占用 290%（4核心4线程）：

![cm_image 2019-02-10 16.33.25](http://static.mazhangjing.com/cm_image%202019-02-10%2016.33.25.png)


内存占用 4GB（8GB DDR4 2666）:

![cm_image 2019-02-10 16.33.35](http://static.mazhangjing.com/cm_image%202019-02-10%2016.33.35.png)


完成 500000 人选票收集，每隔 100 人唱票一次，所有选票在前 100 s内随机投出，投出后 JVM 有 500s 的时间收集数据，结果耗时约 4min —— 240s 完成了所有线程的数据收集工作（线程数采用 Akka 默认的线程设置）。 

总的而言，这是一次很无聊的摸鱼，温习了下 Akka 相关知识，发现 Akka 系统的设计感很重，一定要先做好设计工作，再写代码，否则，消息传着传着就不知道传给谁了。此外，这 130 多行代码的 Scala 实现的分布式投票系统 Demo，相比较 JavaScript 130 多行代码，难度要大很多，因为用了大量的中间件系统（Akka、Netty、Socket），人家 JS 实现的，直接调用了 JS 和 CSS 的原生动画，封装较少，虽然没什么可比性，但是，分布式和微服务真的很灵活，对于发票者、统计者，可以分别部署在不同的 Linux 机器上，而投票者，可以直接部署在大量的 Http 服务器上，使用 Spring Boot 异步提供 Http 支持，其调用投票者 Actor 通过 Akka.tcp 通信。