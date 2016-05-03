package com.jbrisbin.docker

import java.nio.charset.Charset

import akka.actor.ActorDSL._
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Framing, Source}
import akka.util.{ByteString, Timeout}
import org.hamcrest.MatcherAssert._
import org.hamcrest.Matchers._
import org.junit.{After, Test}
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
class DockerTests {

  val log = LoggerFactory.getLogger(classOf[DockerTests])

  val charset = Charset.defaultCharset().toString
  val testLabels = Some(Map("test" -> "true"))

  implicit val system = ActorSystem("tests")
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(30 seconds)

  import system.dispatcher

  @After
  def cleanup(): Unit = {
    Await.result(Docker().remove("runtest", volumes = true, force = true), timeout.duration)
  }

  @Test
  def canListContainers() = {
    val containers = Await.result(Docker().containers(), timeout.duration)
    log.debug("containers: {}", containers)

    assertThat("Containers exist", containers.nonEmpty)
  }

  @Test
  def canCreateContainer() = {
    val container = Await.result(Docker().create(
      CreateContainer(
        Name = "runtest",
        Image = "alpine",
        Labels = testLabels
      )
    ), timeout.duration)
    log.debug("container: {}", container)

    assertThat("Container was created", container.Id, notNullValue())
  }

  @Test
  def canStartContainer(): Unit = {
    val docker = Docker()

    // Create and start a container
    val container = Await.result(
      docker
        .create(CreateContainer(
          Name = "runtest",
          Cmd = Seq("/bin/cat"),
          Image = "alpine",
          Labels = testLabels,
          Tty = true
        ))
        .flatMap(c => docker.start(c.Id)),
      timeout.duration
    )
    log.debug("container: {}", container)

    // Interact with container via Actor
    val p: Promise[Boolean] = Promise()
    actor(new Act {
      whenStarting {
        container ! Exec(Seq("ls", "-la", "/bin/busybox"))
      }
      become {
        case StdOut(bytes) => {
          log.debug(bytes.decodeString(charset))
          p.completeWith(Future.successful(true))
        }
        case StdErr(bytes) => p.failure(new RuntimeException(bytes.decodeString(charset)))
        case msg => p.failure(new RuntimeException(s"Unknown message $msg"))
      }
    })

    assertThat("Exec completes successfully", Await.result(p.future, timeout.duration))
    assertThat("Container was stopped", Await.result(docker.stop("runtest"), timeout.duration))
  }

  @Test
  def canListImages() = {
    val images = Await.result(Docker().images(), timeout.duration)
    log.debug("images: {}", images)

    assertThat("Images exist", images.nonEmpty)
  }

}
