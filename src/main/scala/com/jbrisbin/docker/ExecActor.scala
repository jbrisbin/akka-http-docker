package com.jbrisbin.docker

import java.nio.ByteOrder

import akka.actor.{Actor, ActorLogging, ActorSystem}
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path._
import akka.stream.ActorMaterializer
import akka.stream.actor.{ActorPublisher, ActorPublisherMessage}
import akka.stream.scaladsl.Framing
import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
import org.json4s.{DefaultFormats, Formats, Serialization, jackson}

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
class ExecActor(docker: Docker,
                containerId: String,
                ex: Exec)(implicit val system: ActorSystem,
                          implicit val materializer: ActorMaterializer)
  extends Actor with ActorLogging with ActorPublisher[DockerMessage] {

  implicit val formats: Formats = DefaultFormats
  implicit val serialization: Serialization = jackson.Serialization

  import system.dispatcher

  def receive = {
    case ActorPublisherMessage.Request(n) => {
      val req = RequestBuilding.Post(
        Uri(path = /("containers") / containerId / "exec"),
        ex
      )
      val f = docker.request(req)
        .flatMap(e => e.to[Map[String, AnyRef]])
        .map(m => {
          log.debug("created exec: {}", m)

          m.get("Warnings") match {
            case None =>
            case Some(w) => w.asInstanceOf[Seq[String]].foreach(log.warning)
          }
          val execId = m("Id").asInstanceOf[String]

          docker.requestFirst(RequestBuilding.Post(Uri(path = /("exec") / execId / "start"), ExecStart()))
            .map(resp => resp.status match {
              case OK => resp.entity.dataBytes
                .via(Framing.lengthField(4, 4, 1024 * 1024, ByteOrder.BIG_ENDIAN))
                .runForeach(bytes => {
                  val outputType = bytes(0) match {
                    case 1 => StdOut
                    case 2 => StdErr
                  }
                  onNext(outputType(bytes.slice(8, bytes.length)))
                })
                .onComplete(ignored => onCompleteThenStop())
              case e@(ClientError(_) | ServerError(_)) =>
                onErrorThenStop(new IllegalStateException(e.reason()))
            })
        })

      f.onFailure {
        case ex => onErrorThenStop(ex)
      }
    }
  }

}
