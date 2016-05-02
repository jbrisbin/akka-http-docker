package com.jbrisbin.docker

import java.nio.charset.Charset
import java.util.concurrent.{CompletableFuture, TimeUnit}

import akka.actor.ActorDSL._
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.hamcrest.MatcherAssert._
import org.hamcrest.Matchers._
import org.junit.Test
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
class DockerTests {

  val log = LoggerFactory.getLogger(classOf[DockerTests])

  val charset = Charset.defaultCharset().toString
  val testLabels = Some(Map("test" -> "true"))

  implicit val timeout = Timeout(30 seconds)

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
        Image = "alpine",
        Labels = testLabels
      )
    ), timeout.duration)
    log.debug("container: {}", container)

    assertThat("Container was created", container.Id, notNullValue())
  }

  @Test
  def canStartContainer() = {
    val docker = Docker()

    implicit val system = ActorSystem("tests")
    implicit val materializer = ActorMaterializer()

    import system.dispatcher

    // Create and start a container
    val container = Await.result(
      docker
        .create(CreateContainer(Cmd = Seq("/bin/cat"), Image = "alpine", Labels = testLabels, Tty = true))
        .flatMap(c => docker.start(c.Id)),
      timeout.duration
    )
    log.debug("container: {}", container)

    val f: CompletableFuture[Boolean] = new CompletableFuture[Boolean]()
    actor(new Act {
      whenStarting {
        container ! ExecCreate(Seq("ls", "-la", "/bin/busybox"))
      }
      become {
        case StdOut(bytes) => f.complete(true)
        case StdErr(bytes) => f.completeExceptionally(new RuntimeException(bytes.decodeString(charset)))
        case msg => f.completeExceptionally(new RuntimeException(s"Unknown message $msg"))
      }
    })

    assertThat("Exec completes successfully", f.get(timeout.duration.toMillis, TimeUnit.MILLISECONDS))
  }

  @Test
  def canListImages() = {
    val images = Await.result(Docker().images(), timeout.duration)
    log.debug("images: {}", images)

    assertThat("Images exist", images.nonEmpty)
  }

}
