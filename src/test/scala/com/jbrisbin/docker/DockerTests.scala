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
    val containers = Await.result(Docker.containers(), Duration.Inf)

    containers.foreach(c => {
      log.info(s"container: ${c.image}, command: ${c.command}")
    })
  }

  @Test
  def canListImages() = {
    val images = Await.result(Docker.images(), Duration.Inf)

    images.foreach(i => {
      log.info(s"image: ${i.id}, labels: ${i.labels}")
    })
  }

}
