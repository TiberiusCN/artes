package artes

import akka.actor.typed._
import akka.actor.typed.receptionist._
import akka.actor.typed.scaladsl._
import akka.cluster.typed._
import cats.implicits._
import com.typesafe.config.ConfigFactory
import java.nio.file.Path
import java.util.ServiceLoader
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util._

trait ClusterMessage

trait Spawner {
  def config: String
  def kill(): Unit
  def stop(j: Int): Unit
  def spawn(context: ActorContext[Nothing], system: ActorRef[SystemCommand]): Unit
}

trait SystemCommand
object SystemCommand {
  case class AddDependency(name: String, jar: Array[Byte]) extends SystemCommand
  case class JoinCluster(host: String, port: Int) extends SystemCommand
  case class Shutdown(timeout: Int) extends SystemCommand
  case class Finished(j: Int) extends SystemCommand
}

object Main extends App {
  var path = System.getProperty("os.name") match {
    case "Linux" => Path.of("~/.local/share/artes/actors")
    case "Windows" => Path.of("%APPDATA%\\artes\\actors")
    case j => throw new Exception(s"Unknown OS: $j")
  }
  var port = 9999
  var bind = "127.0.0.1"
  var join = Option.empty[(String, Int)]
  @tailrec private def scan(args: List[String]): Unit = args match {
    case "--actors" :: path :: tail =>
      this.path = Path.of(path)
      scan(tail)
    case "--port" :: port :: tail =>
      this.port = port.toInt
      scan(tail)
    case "--bind" :: bind :: tail =>
      this.bind = bind
      scan(tail)
    case "--join" :: ip :: port :: tail =>
      this.join = (ip, port.toInt).some
      scan(tail)
    case unknown :: tail =>
      if (unknown == "--help" || unknown == "-h") {
        println("--actors <path> - path to writable directory for extensions")
        println("--port <port> - port for cluster bind (9999)")
        println("--bind <ip> - cluster network interface (127.0.0.1)")
        println("--join <ip> <port> - connect to cluster")
        System.exit(0)
      } else throw new Exception(s"unknown arg: $unknown")
    case List() =>
  }
  scan(args.toList)
  path.toFile.mkdirs()

  private def spawners() = {
    val clazz = this.getClass.getClassLoader.loadClass("artes.Spawner")
    import scala.jdk.CollectionConverters._
    val i = ServiceLoader.load(clazz).asScala.iterator
    var b = true
    var out = List[Spawner]()
    while (b) {
      scala.util.Try {
        i.nextOption().map(_.asInstanceOf[Spawner]) match {
          case Some(j) => out = j :: out
          case None => b = false
        }
      }
    }
    out
  }
  private def run() = {
    val config = s"""
      akka {
        loglevel = "WARNING"
        actor {
          provider = "cluster"
          serialization-bindings {
            "artes.ClusterMessage" = jackson-cbor
          }
        }
        remote.artery {
          canonical {
            hostname = "$bind"
            port = $port
          }
        }
        cluster {}
      }"""

    val spawners = this.spawners()
    val configs = spawners
      .map(_.config)
      .map(ConfigFactory.parseString)
      .foldLeft(ConfigFactory.parseString(config)) { case (acc, j) =>
        acc.withFallback(j)
      }

    ActorSystem[Nothing](Behaviors.setup[Nothing] { ctx =>
      implicit val ec = ctx.executionContext
      val cluster = Cluster(ctx.system)

      case class Kill() extends SystemCommand
      var remains = Set[Int]()

      println(s"actors: ${spawners.size}")
      if (spawners.size > 0) {
        val spawn = ctx.spawnAnonymous(Behaviors.setup[SystemCommand] { ctx =>
          Behaviors.withTimers { timers =>
            Behaviors.receiveMessage {
              case SystemCommand.Shutdown(timeout) =>
                spawners.zipWithIndex.foreach(j => j._1.stop(j._2))
                timers.startSingleTimer(Kill(), timeout.seconds)
                Behaviors.same
              case SystemCommand.JoinCluster(host, port) =>
                cluster.manager ! Join(akka.actor.Address("akka", "lambda", host.some, port.some))
                Behaviors.same
              case SystemCommand.Finished(j) =>
                remains -= j
                if (remains.isEmpty) Behaviors.stopped else Behaviors.same
              case SystemCommand.AddDependency(name, jar) =>
                Using(new java.io.FileOutputStream(path.resolve(name).toFile))(_.write(jar)) match {
                  case Failure(f) => ctx.log.error(f.getMessage())
                  case _ =>
                }
                Behaviors.same
              case Kill() =>
                remains.foreach(j => spawners(j).kill())
                scala.concurrent.Future {
                  Thread.sleep(10)
                  System.exit(-1)
                }
                Behaviors.empty
            }
          }
        })

        this.join.foreach { j =>
          spawn ! SystemCommand.JoinCluster(j._1, j._2)
        }

        spawners.zipWithIndex.foreach { spawner =>
          Try {
            spawner._1.spawn(ctx, spawn)
            remains += spawner._2
          } match {
            case Failure(f) => ctx.log.error(f.getMessage())
            case _ =>
          }
        }
        println(s"active actors: ${remains.size}")
        if (remains.isEmpty) Behaviors.stopped
        else Behaviors.empty
      } else Behaviors.stopped
    }, "lambda", configs)
  }
  run()
}
