package com.jbrisbin.docker

import java.nio.charset.Charset

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Unit tests for Docker client.
  */
class DockerTests extends WordSpec with Matchers with BeforeAndAfterEach {

  val log = LoggerFactory.getLogger(classOf[DockerTests])

  val charset = Charset.defaultCharset().toString
  val testLabels = Some(Map("test" -> "true"))

  implicit val system = ActorSystem("tests")
  implicit val materializer = ActorMaterializer()
  implicit val timeout: Timeout = Timeout(30 seconds)

  import system.dispatcher

  val docker = Docker()

  override def afterEach(): Unit = {
    val cs = Await.result(
      docker.containers(all = true, filters = Map("label" -> Seq("test=true"))),
      timeout.duration
    )
    Await.result(
      for {
        containers <- docker.containers(all = true, filters = Map("label" -> Seq("test=true")))
        done <- docker.remove(containers = Source(containers), volumes = true, force = true)
      } yield done,
      timeout.duration
    )
  }

  "Docker" should {
    "list containers" in {
      val containers = Await.result(docker.containers(all = true), timeout.duration)
      log.debug("containers: {}", containers)

      containers should not be empty
    }
  }

  "Filter containers" in {
    Await.result(
      docker
        .create(CreateContainer(
          Image = "alpine",
          Cmd = Some(Seq("/bin/sh")),
          Labels = testLabels
        )), timeout.duration
    )
    val containers = Await.result(
      docker.containers(all = true, filters = Map("label" -> Seq("test"))),
      timeout.duration
    )
    log.debug("containers: {}", containers)

    containers should not be empty
  }

  "Create a container" in {
    val container = Await.result(docker.create(
      CreateContainer(
        Name = "runtest",
        Image = "alpine",
        Cmd = Some(Seq("/bin/sh")),
        Labels = testLabels
      )
    ), timeout.duration)
    log.debug("container: {}", container)

    container.Id should not be (null)
  }

  "Execute in a container" in {
    // Create and start a container
    val container = Await.result(
      docker
        .create(CreateContainer(
          Name = "runtest",
          Cmd = Some(Seq("/bin/cat")),
          Image = "alpine",
          Labels = testLabels,
          Tty = true
        ))
        .flatMap(c => docker.start(c.Id)),
      timeout.duration
    )
    log.debug("container: {}", container)

    // Interact with container via Source
    val src = docker.exec("runtest", Exec(Seq("ls", "-la", "/bin/busybox")))
      .map {
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
      }

    Await.result(src.runFold(true)(_ | _), timeout.duration) should be(true)
    Await.result(docker.stop("runtest"), timeout.duration) should be(true)
  }

  "Stream output" in {
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

    count should be > 0
  }

  "List images" in {
    val images = Await.result(docker.images(), timeout.duration)
    log.debug("images: {}", images)

    images should not be empty
  }
  
}
