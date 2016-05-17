package com.jbrisbin.docker

import java.net.URI
import java.nio.ByteOrder
import java.nio.charset.Charset
import javax.net.ssl.SSLContext

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
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
import org.json4s.jackson.JsonMethods
import org.json4s.{DefaultFormats, Formats, Serialization, jackson}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

/**
  * Docker client that uses the HTTP remote API to interact with the Docker daemon.
  *
  * @see [[https://docs.docker.com/engine/reference/api/docker_remote_api_v1.22]]
  */
class Docker(dockerHost: URI, sslctx: SSLContext)
            (implicit val system: ActorSystem,
             implicit val materializer: ActorMaterializer,
             implicit val execCtx: ExecutionContext) {

  implicit val formats: Formats = DefaultFormats
  implicit val serialization: Serialization = jackson.Serialization

  val logger = LoggerFactory.getLogger(classOf[Docker])
  val charset = Charset.defaultCharset().toString
  val mapper = {
    val m = new ObjectMapper()
    m.registerModule(DefaultScalaModule)
    m
  }
  val docker = Http().outgoingConnectionHttps(dockerHost.getHost, dockerHost.getPort, ConnectionContext.https(sslctx))

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
      Uri(path = /("containers") / "create", queryString = Some(Uri.Query(params).toString)),
      container
    )
    request(req)
      .mapAsync(1)(_.to[Map[String, AnyRef]])
      .map(m => {
        m.get("Warnings") match {
          case None | Some(null) =>
          case Some(w) => w.asInstanceOf[Seq[String]].foreach(logger.warn)
        }
        m("Id").asInstanceOf[String]
      })
      .mapAsync(1)(inspect)
      .runWith(Sink.head)
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

    if (volumes)
      params += ("v" -> "true")

    if (force)
      params += ("force" -> "true")

    containers
      .map(c => {
        RequestBuilding.Delete(
          Uri(path = /("containers") / c.Id, queryString = Some(Uri.Query(params).toString))
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
      .mapAsync(1)(_.to[ContainerInfo])
      .runWith(Sink.head)
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
      Uri(path = /("containers") / container / "start", queryString = Some(Uri.Query(params).toString))
    )
    request(req)
      .runWith(Sink.head)
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

    timeout foreach {
      t => params += ("t" -> t.toString)
    }

    val req = RequestBuilding.Post(
      Uri(path = /("containers") / container / "stop", queryString = Some(Uri.Query(params).toString))
    )
    requestStream(req)
      .runWith(Sink.head)
      .map(_.status match {
        case NoContent => true
        case NotModified => false
        case e@(ClientError(_) | ServerError(_)) => throw new IllegalStateException(e.reason())
        case _ => ???
      })
  }

  /**
    * Execute an operation in the context of a container.
    *
    * @param container the name or ID of the container
    * @param ex        description of the execution to perform
    * @return a [[Source]] which will have output published to it
    */
  def exec(container: String, ex: Exec): Source[ExecOutput, NotUsed] = {
    request(RequestBuilding.Post(Uri(path = /("containers") / container / "exec"), ex))
      .mapAsync(1)(_.to[Map[String, AnyRef]])
      .flatMapConcat(m => {
        logger.debug("created exec: {}", m)

        m.get("Warnings") foreach {
          _.asInstanceOf[Seq[String]].foreach(logger.warn)
        }
        val execId = m("Id").asInstanceOf[String]

        requestStream(RequestBuilding.Post(Uri(path = /("exec") / execId / "start"), ExecStart(Detach = ex.Detach)))
          .flatMapConcat(resp =>
            resp.status match {
              case OK =>
                resp
                  .entity
                  .dataBytes
                  .via(Framing.lengthField(4, 4, 1024 * 1024, ByteOrder.BIG_ENDIAN))
                  .map({
                    case b if b.isEmpty => ???
                    case b if b(0) == 1 => StdOut(b.drop(8))
                    case b if b(0) == 2 => StdErr(b.drop(8))
                    case _ => ???
                  }: PartialFunction[ByteString, ExecOutput])
              case e@(ClientError(_) | ServerError(_)) => throw new IllegalStateException(e.reason)
              case status => throw new IllegalStateException(s"Unknown status $status")
            })
      })
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

    if (all)
      params += ("all" -> "true")
    if (limit > 0)
      params += ("limit" -> limit.toString)
    if (before ne null)
      params += ("before" -> before)
    if (since ne null)
      params += ("since" -> since)
    if (size)
      params += ("size" -> "true")
    if (filters.nonEmpty)
      params += ("filters" -> mapper.writeValueAsString(filters))

    val req = RequestBuilding.Get(Uri(
      path = /("containers") / "json",
      queryString = Some(Uri.Query(params).toString)
    ))
    request(req)
      .mapAsync(1)(_.to[List[Container]])
      .runWith(Sink.head)
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

    if (all)
      params += ("all" -> "true")
    if (filter ne null)
      params += ("filter" -> filter)
    if (filters.nonEmpty)
      params += ("filters" -> JsonMethods.mapper.writeValueAsString(filters))

    val req = RequestBuilding.Get(Uri(
      path = /("images") / "json",
      queryString = Some(Uri.Query(params).toString)
    ))
    request(req)
      .mapAsync(1)(_.to[List[Image]])
      .runWith(Sink.head)
  }

  private[docker] def requestStream(req: HttpRequest): Source[HttpResponse, NotUsed] = {
    Source.single(req)
      .map(req => {
        logger.debug(req.toString)
        req
      })
      .via(docker)
  }

  private[docker] def request(req: HttpRequest): Source[Unmarshal[ResponseEntity], NotUsed] = {
    requestStream(req)
      .mapAsync(1)(resp => resp.status match {
        case OK | Created | NoContent => Future.successful(Unmarshal(resp.entity))
        case e@(ClientError(_) | ServerError(_)) => {
          logger.error("Request failed: {}", resp)
          resp
            .entity
            .dataBytes
            .runFold(ByteString.empty)(_ ++ _)
            .map(msg => throw new IllegalStateException(msg.decodeString(charset)))
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
              case (stdout, stderr) if stdout.bytes.nonEmpty => replyTo ! stdout.bytes
              case (stdout, stderr) if stderr.bytes.nonEmpty => {
                val msg = stderr.bytes.decodeString(charset)
                replyTo ! Status.Failure(new IllegalStateException(msg))
              }
              case _ => ???
            }

        case msg => logger.warn("unknown message: {}", msg)
      }
    }), containerId)
  }

}

object Docker {
  val dockerHostEnv = System.getenv("DOCKER_HOST")

  def apply(dockerHost: String = dockerHostEnv, sslctx: SSLContext = SSL.createSSLContext)
           (implicit system: ActorSystem, materializer: ActorMaterializer, execCtx: ExecutionContext): Docker = {
    new Docker(URI.create(dockerHost), sslctx)
  }
}
