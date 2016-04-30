package com.jbrisbin.docker

import java.net.URI

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpRequest, ResponseEntity, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
import org.json4s.{DefaultFormats, Formats, Serialization, jackson}

import scala.concurrent.Future

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
class Docker(dockerHost: URI) {

  implicit val system = ActorSystem("docker")
  implicit val materializer = ActorMaterializer()

  implicit val formats: Formats = DefaultFormats
  implicit val serialization: Serialization = jackson.Serialization

  import system.dispatcher

  lazy val sslctx = ConnectionContext.https(SSL.createSSLContext)
  lazy val docker = Http().outgoingConnectionHttps(dockerHost.getHost, dockerHost.getPort, sslctx)

  val mapper = new ObjectMapper()
  mapper.registerModule(new DefaultScalaModule())

  def containers(containers: Containers = Containers()): Future[List[Container]] = {
    var params = Map[String, String]()

    containers.all match {
      case true => params += ("all" -> "true")
      case _ =>
    }
    containers.limit match {
      case l if l > 0 => params += ("limit" -> l.toString)
      case _ =>
    }
    containers.before match {
      case null =>
      case b => params += ("before" -> b)
    }
    containers.since match {
      case null =>
      case s => params += ("since" -> s)
    }
    containers.size match {
      case true => params += ("size" -> "true")
      case _ =>
    }
    containers.filters match {
      case m if m.isEmpty =>
      case m => params += ("filters" -> mapper.writeValueAsString(m))
    }

    val req = RequestBuilding.Get(Uri(
      path = Uri.Path("/containers/json"),
      queryString = Some(Uri.Query(params).toString())
    ))
    request(req)
      .flatMap(e => e.to[List[Map[String, AnyRef]]])
      .map(l => l.map(m => map2container(m)))
  }

  def images(images: Images = Images()): Future[List[Image]] = {
    var params = Map[String, String]()

    images.all match {
      case true => params += ("all" -> "true")
      case _ =>
    }
    images.filter match {
      case null =>
      case f => params += ("filter" -> f)
    }
    images.filters match {
      case m if m.isEmpty =>
      case m => params += ("filters" -> mapper.writeValueAsString(m))
    }

    val req = RequestBuilding.Get(Uri(
      path = Uri.Path("/images/json"),
      queryString = Some(Uri.Query(params).toString())
    ))
    request(req)
      .flatMap(e => e.to[List[Map[String, AnyRef]]])
      .map(l => l.map(m => map2image(m)))
  }

  def run(run: Run): ActorRef = {
    null
  }

  private def request(req: HttpRequest): Future[Unmarshal[ResponseEntity]] = {
    Source.single(req)
      .via(docker)
      .runWith(Sink.head)
      .flatMap(resp => resp.status match {
        case OK => Future.successful(Unmarshal(resp.entity))
        case e@(ClientError(_) | ServerError(_)) => Future.failed(throw new RuntimeException(e.reason))
        case e => Future.failed(new RuntimeException(s"Unknown error $e"))
      })
  }

}

object Docker {

  private val instance = Docker(System.getenv().getOrDefault("DOCKER_HOST", "https://127.0.0.1:2376"))

  def apply(): Docker = instance

  def apply(dockerHost: String) = new Docker(URI.create(dockerHost))

}
