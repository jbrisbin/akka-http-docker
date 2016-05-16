package com.jbrisbin.docker

import java.nio.charset.Charset

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import org.hamcrest.MatcherAssert._
import org.hamcrest.Matchers._
import org.junit.{After, Test}
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

  implicit val system = ActorSystem("tests")
  implicit val materializer = ActorMaterializer()
  implicit val timeout: Timeout = Timeout(30 seconds)

  import system.dispatcher

  val docker = Docker()

  @After
  def cleanup(): Unit = {
    Await.result(
      for {
        containers <- docker.containers(all = true, filters = Map("label" -> Seq("test=true")))
        done <- docker.remove(containers = Source(containers), volumes = true, force = true)
      } yield done,
      timeout.duration
    )
  }

  @Test
  def canListContainers() = {
    val containers = Await.result(docker.containers(all = true), timeout.duration)
    log.debug("containers: {}", containers)

    assertThat("Containers exist", containers.nonEmpty)
  }

  @Test
  def canFilterContainerList() = {
    Await.result(
      docker
        .create(CreateContainer(
          Image = "alpine",
          Cmd = Some(Seq("/bin/sh")),
          Labels = testLabels
        )),
      timeout.duration
    )
    val containers = Await.result(
      docker.containers(all = true, filters = Map("label" -> Seq("test"))),
      timeout.duration
    )
    log.debug("containers: {}", containers)

    assertThat("Containers exist", containers.nonEmpty)
  }

  @Test
  def canCreateContainer() = {
    val container = Await.result(
      docker
        .create(
          CreateContainer(
            Name = "runtest",
            Image = "alpine",
            Cmd = Some(Seq("/bin/sh")),
            Labels = testLabels
          )
        ),
      timeout.duration
    )
    log.debug("container: {}", container)

    assertThat("Container was created", container.Id, notNullValue())
  }

  @Test
  def canExecInContainer(): Unit = {
    // Create and start a container
    val create = CreateContainer(
      Cmd = Some(Seq("/bin/cat")),
      Image = "alpine",
      Labels = testLabels,
      Tty = true
    )
    val success = Await.result(
      for {
        ci <- docker.create(create)
        ref <- docker.start(ci.Id)
        exec <- docker.exec(ci.Id, Exec(Seq("ls", "-la", "/bin/busybox"))).map {
          case StdOut(bytes) => {
            val str = bytes.decodeString(charset)
            log.debug(str)
            true
          }
          case StdErr(bytes) => {
            val str = bytes.decodeString(charset)
            log.error(str)
            false
          }
        }.runFold(true)(_ | _)
        stop <- docker.stop(ci.Id)
      } yield {
        exec
      },
      timeout.duration
    )

    assertThat("Exec completes successfully", success)
  }

  //  @Ignore
  @Test
  def canStreamOutput(): Unit = {
    val res = Await.result(
      docker
        .create(CreateContainer(
          Image = "alpine",
          Tty = true,
          Cmd = Some(Seq("/bin/cat")),
          Labels = testLabels
        ))
        .flatMap(c => docker.start(c.Id))
        .flatMap(c => c ? Exec(Seq("ls", "-la", "/bin"))),
      timeout.duration
    )
    //log.debug("result: {}", res)

    val count = res match {
      case stdout: ByteString => {
        stdout
          .decodeString(charset)
          .split("\n")
          .filter(s => s.contains("uname"))
          .foldRight(0)((line, count) => count + 1)
      }
    }

    assertThat("uname command was found with ls", count > 0)
  }

  @Test
  def canListImages() = {
    val images = Await.result(docker.images(), timeout.duration)
    log.debug("images: {}", images)

    assertThat("Images exist", images.nonEmpty)
  }

}
