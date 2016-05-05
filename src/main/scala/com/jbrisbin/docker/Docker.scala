package com.jbrisbin.docker

import java.net.URI
import java.nio.charset.Charset

import akka.NotUsed
import akka.actor.ActorDSL._
import akka.actor.{ActorRef, ActorSystem, Props, Status}
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Path./
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.ActorMaterializer
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl._
import akka.util.ByteString
import com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
import org.json4s.jackson.JsonMethods
import org.json4s.{DefaultFormats, Formats, Serialization, jackson}
import org.slf4j.LoggerFactory

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

/**
  * Docker client that uses the HTTP remote API to interact with the Docker daemon.
  *
  * @see [[https://docs.docker.com/engine/reference/api/docker_remote_api_v1.22]]
  */
class Docker(dockerHost: URI) {

  val logger = LoggerFactory.getLogger(classOf[Docker])
  val charset = Charset.defaultCharset().toString

  implicit val system = ActorSystem("docker")
  implicit val materializer = ActorMaterializer()

  implicit val formats: Formats = DefaultFormats
  implicit val serialization: Serialization = jackson.Serialization

  import system.dispatcher

  lazy val sslctx = ConnectionContext.https(SSL.createSSLContext)
  lazy val docker = Http().outgoingConnectionHttps(dockerHost.getHost, dockerHost.getPort, sslctx)

  val mapper = new ObjectMapper()
  mapper.registerModule(DefaultScalaModule)

  JsonMethods.mapper.configure(SerializationFeature.INDENT_OUTPUT, true)

  /**
    * Create a Docker container described by the [[CreateContainer]].
    *
    * @param container describes how the container should be configured
    * @return the results of an [[inspect()]], namely a [[Future]] of [[Container]]
    */
  def create(container: CreateContainer): Future[ContainerInfo] = {
    var params = Map[String, String]()

    container.Name match {
      case "" | null =>
      case n => params += ("name" -> n)
    }

    val req = RequestBuilding.Post(
      Uri(path = /("containers") / "create", queryString = Some(Uri.Query(params).toString())),
      container
    )
    request(req)
      .flatMap(e => e.to[Map[String, AnyRef]])
      .map(m => {
        m("Warnings") match {
          case null =>
          case w => w.asInstanceOf[Seq[String]].foreach(logger.warn)
        }
        m("Id").asInstanceOf[String]
      })
      .flatMap(inspect)
  }

  /**
    * Removes containers identified by their [[Container]] object.
    *
    * Used to chain this operation into a longer series of calls, where the list of [[Container]] is obtained from the daemon, probably using some filters applied.
    *
    * @param containers executes a DELETE for every [[Container]] in the source stream
    * @param volumes    whether to delete volumes or not
    * @param force      whether to force a shutdown of the container or not
    * @return a boolean indicating overall success of all deletions
    */
  def remove(containers: Source[Container, _],
             volumes: Boolean = false,
             force: Boolean = false): Future[Boolean] = {
    var params = Map[String, String]()

    volumes match {
      case true => params += ("v" -> "true")
      case _ =>
    }
    force match {
      case true => params += ("force" -> "true")
      case _ =>
    }

    containers
      .map(c => {
        RequestBuilding.Delete(
          Uri(path = /("containers") / c.Id, queryString = Some(Uri.Query(params).toString()))
        )
      })
      .via(docker)
      .runFold(true)((last, resp) => resp.status match {
        case Success(_) => true
        case e@(ClientError(_) | ServerError(_)) => {
          logger.error(e.reason())
          false
        }
        case _ => false
      })
  }

  /**
    * Inspect a container and gather metadata about it.
    *
    * @param container the name or ID of the container to inspect
    * @return a [[Container]] that describes the container
    */
  def inspect(container: String): Future[ContainerInfo] = {
    request(RequestBuilding.Get(Uri(path = /("containers") / container / "json")))
      .flatMap(e => e.to[ContainerInfo])
  }

  /**
    * Start a container.
    *
    * @param container  the name or ID of the container to start
    * @param detachKeys configure the keys recognized to detach
    * @return an [[ActorRef]] to which messages can be sent to interact with the running container
    */
  def start(container: String, detachKeys: Option[String] = None): Future[ActorRef] = {
    var params = Map[String, String]()

    detachKeys match {
      case Some(k) => params += ("detachKeys" -> k)
      case None =>
    }

    val req = RequestBuilding.Post(
      Uri(path = /("containers") / container / "start", queryString = Some(Uri.Query(params).toString()))
    )
    request(req)
      .map(ignored => startContainerActor(container))
  }

  /**
    * Stop a container.
    *
    * @param container the name or ID of the container to stop
    * @param timeout   timeout after which the container will be killed
    * @return `true`, indicating the container was stopped, `false` indicating it was *already* stopped, or a failure of the [[Future]] which indicates an error while trying to stop the container
    */
  def stop(container: String, timeout: Option[Int] = None): Future[Boolean] = {
    var params = Map[String, String]()

    timeout match {
      case Some(t) => params += ("t" -> t.toString)
      case None =>
    }

    val req = RequestBuilding.Post(
      Uri(path = /("containers") / container / "stop", queryString = Some(Uri.Query(params).toString()))
    )
    requestFirst(req)
      .map(resp => resp.status match {
        case NoContent => true
        case NotModified => false
        case e@(ClientError(_) | ServerError(_)) => throw new IllegalStateException(e.reason())
      })
  }

  /**
    * Execute an operation in the context of a container.
    *
    * @param container the name or ID of the container
    * @param ex        description of the execution to perform
    * @return a [[Source]] which will have output published to it
    */
  def exec(container: String, ex: Exec): Source[ExecOutput, Unit] = {
    Source.actorPublisher[ExecOutput](Props(new ExecActor(this, container, ex))).mapMaterializedValue(_ ! ex)
  }

  /**
    * List available containers.
    *
    * @param all     `true` to list all containers. Defaults to `false`.
    * @param limit   Maximum number of containers to return. Defaults to `0`, which means everything.
    * @param since   Only show containers created since the given container.
    * @param before  Only show containers created before the given container.
    * @param size    Show the size of the container as well.
    * @param filters Filter the list by the provided filters.
    * @return A [[Future]] that will eventually contain the `List` of [[Container]] that satisfies the query.
    */
  def containers(all: Boolean = false,
                 limit: Int = 0,
                 since: String = null,
                 before: String = null,
                 size: Boolean = false,
                 filters: Map[String, Seq[String]] = Map.empty): Future[List[Container]] = {
    var params = Map[String, String]()

    all match {
      case true => params += ("all" -> "true")
      case _ =>
    }
    limit match {
      case 0 =>
      case l => params += ("limit" -> l.toString)
    }
    before match {
      case null =>
      case b => params += ("before" -> b)
    }
    since match {
      case null =>
      case s => params += ("since" -> s)
    }
    size match {
      case true => params += ("size" -> "true")
      case _ =>
    }
    filters match {
      case m if m.isEmpty =>
      case m => params += ("filters" -> mapper.writeValueAsString(m))
    }

    val req = RequestBuilding.Get(Uri(
      path = /("containers") / "json",
      queryString = Some(Uri.Query(params).toString())
    ))
    request(req)
      .flatMap(e => e.to[List[Container]])
  }

  /**
    * List available images.
    *
    * @param all
    * @param filter
    * @param filters
    * @return
    */
  def images(all: Boolean = false,
             filter: String = null,
             filters: Map[String, Seq[String]] = Map.empty): Future[List[Image]] = {
    var params = Map[String, String]()

    all match {
      case true => params += ("all" -> "true")
      case _ =>
    }
    filter match {
      case null =>
      case f => params += ("filter" -> f)
    }
    filters match {
      case m if m.isEmpty =>
      case m => params += ("filters" -> JsonMethods.mapper.writeValueAsString(m))
    }

    val req = RequestBuilding.Get(Uri(
      path = /("images") / "json",
      queryString = Some(Uri.Query(params).toString())
    ))
    request(req)
      .flatMap(e => e.to[List[Image]])
  }

  /**
    * Run an image. Not yet implemented.
    *
    * @param run
    * @return
    */
  def run(run: Run): ActorRef = {
    null
  }

  private[docker] def requestStream(req: HttpRequest): Source[HttpResponse, NotUsed] = {
    Source.single(req)
      .map(req => {
        logger.debug(req.toString())
        req
      })
      .via(docker)
  }

  private[docker] def requestFirst(req: HttpRequest): Future[HttpResponse] = {
    requestStream(req).runWith(Sink.head)
  }

  private[docker] def request(req: HttpRequest): Future[Unmarshal[ResponseEntity]] = {
    requestFirst(req)
      .flatMap(resp => resp.status match {
        case OK | Created => Future.successful(Unmarshal(resp.entity))
        case NoContent => Future.successful(null)
        case e@(ClientError(_) | ServerError(_)) => {
          logger.error("Request failed: {}", resp)
          val msg = Await.result(resp.entity.dataBytes.runFold(ByteString.empty)((buff,bytes) => buff ++ bytes), Duration.Inf)
          Future.failed(new RuntimeException(msg.decodeString(charset)))
        }
        case e => Future.failed(new RuntimeException(s"Unknown error $e"))
      })
  }

  private[docker] def startContainerActor(containerId: String): ActorRef = {
    system.actorOf(Props(new Act with ActorPublisher[ExecOutput] {
      become {
        case Stop() => {
          val replyTo = sender()

          stop(containerId) onComplete {
            case util.Success(_) =>
              replyTo ! containerId
            case util.Failure(_) =>
              replyTo ! Status.Failure(new IllegalStateException(s"Container $containerId not stopped"))
          }
          context stop self
        }

        case ex: Exec =>
          val replyTo = sender()

          exec(containerId, ex)
            .runFold((StdOut(ByteString()): StdOut, StdErr(ByteString()): StdErr))((acc, output) => {
              output match {
                case StdOut(bytes) => (StdOut(acc._1.bytes ++ bytes), acc._2)
                case StdErr(bytes) => (acc._1, StdErr(acc._2.bytes ++ bytes))
              }
            })
            .onSuccess {
              case (stdout, stderr) if stderr.bytes.isEmpty => replyTo ! stdout.bytes
              case (stdout, stderr) if stdout.bytes.isEmpty => {
                val msg = stderr.bytes.decodeString(charset)
                replyTo ! Status.Failure(new IllegalStateException(msg))
              }
            }

        case msg => logger.warn("unknown message: {}", msg)
      }
    }), containerId)
  }

}

object Docker {

  private val instance = Docker(System.getenv("DOCKER_HOST"))

  def apply(): Docker = instance

  def apply(dockerHost: String) = {
    val uri = URI.create(dockerHost match {
      case null | "" => "https://localhost:2376"
      case h => h
    })
    new Docker(uri)
  }

}
