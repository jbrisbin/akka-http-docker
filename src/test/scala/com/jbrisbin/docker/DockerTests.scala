package com.jbrisbin.docker

import org.junit.Test
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration.Duration

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
class DockerTests {

  val log = LoggerFactory.getLogger(classOf[DockerTests])

  @Test
  def canListContainers() = {
    val containers = Await
      .result(Docker.containers(Containers(filters = Map("status" -> Seq("running")))), Duration.Inf)

    containers.foreach(c => {
      log.info(s"image: ${c.image}, command: ${c.command}")
    })
  }

}
