package com.jbrisbin.docker

import java.net.URI

import akka.NotUsed
import akka.actor.ActorDSL._
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Path./
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.ActorMaterializer
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl._
import com.fasterxml.jackson.databind.SerializationFeature
import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
import org.json4s.jackson.JsonMethods
import org.json4s.{DefaultFormats, Formats, Serialization, jackson}
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
class Docker(dockerHost: URI) {

  val logger = LoggerFactory.getLogger(classOf[Docker])

  implicit val system = ActorSystem("docker")
  implicit val materializer = ActorMaterializer()

  implicit val formats: Formats = DefaultFormats
  implicit val serialization: Serialization = jackson.Serialization

  import system.dispatcher

  lazy val sslctx = ConnectionContext.https(SSL.createSSLContext)
  lazy val docker = Http().outgoingConnectionHttps(dockerHost.getHost, dockerHost.getPort, sslctx)

  JsonMethods.mapper.configure(SerializationFeature.INDENT_OUTPUT, true)

  def create(container: CreateContainer): Future[Container] = {
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

  def remove(container: String,
             volumes: Boolean = false,
             force: Boolean = false): Future[Either[Boolean, String]] = {
    var params = Map[String, String]()

    volumes match {
      case true => params += ("v" -> "true")
      case _ =>
    }
    force match {
      case true => params += ("force" -> "true")
      case _ =>
    }

    val req = RequestBuilding.Delete(
      Uri(path = /("containers") / container, queryString = Some(Uri.Query(params).toString()))
    )
    requestFirst(req)
      .map(resp => resp.status match {
        case NoContent => Left(true)
        case e@(ClientError(_) | ServerError(_)) => Right(e.reason())
      })
  }

  def inspect(container: String): Future[Container] = {
    request(RequestBuilding.Get(Uri(path = /("containers") / container / "json")))
      .flatMap(e => e.to[Container])
  }

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

  def exec(container: String, ex: Exec): Source[ExecOutput, Unit] = {
    Source.actorPublisher[ExecOutput](Props(new ExecActor(this, container, ex))).mapMaterializedValue(_ ! ex)
  }

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
      case m => params += ("filters" -> JsonMethods.mapper.writeValueAsString(m))
    }

    val req = RequestBuilding.Get(Uri(
      path = /("containers") / "json",
      queryString = Some(Uri.Query(params).toString())
    ))
    request(req)
      .flatMap(e => e.to[List[Container]])
  }

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

  def run(run: Run): ActorRef = {
    null
  }

  private[docker] def requestStream(req: HttpRequest): Source[HttpResponse, NotUsed] = {
    Source.single(req).via(docker)
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
          logger.error("Request failed: {}", req)
          Future.failed(new RuntimeException(e.reason))
        }
        case e => Future.failed(new RuntimeException(s"Unknown error $e"))
      })
  }

  private[docker] def startContainerActor(containerId: String): ActorRef = {
    actor(new Act with ActorPublisher[ExecOutput] {
      become {
        case None => context stop self

        case ex: Exec =>
          // TODO: Run a Fold here and gather output to send back to complete the ask Future
          exec(containerId, ex).runForeach(sender() ! _)

        case msg => logger.warn("unknown message: {}", msg)
      }
    })
  }

}

object Docker {

  private val instance = Docker(System.getenv().getOrDefault("DOCKER_HOST", "https://127.0.0.1:2376"))

  def apply(): Docker = instance

  def apply(dockerHost: String) = new Docker(URI.create(dockerHost))

}
