package com.jbrisbin.docker

import org.hamcrest.MatcherAssert._
import org.hamcrest.Matchers._
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
    val containers = Await.result(Docker().containers(), Duration.Inf)
    log.debug("containers: {}", containers)

    assertThat("Containers exist", containers.nonEmpty)
  }

  @Test
  def canCreateContainer() = {
    val container = Await.result(Docker().create(
      CreateContainer(
        Image = "alpine",
        Labels = Some(Map("test" -> "true"))
      )
    ), Duration.Inf)
    log.debug("container: {}", container)

    assertThat("Container was created", container.Id, notNullValue())
  }

  @Test
  def canListImages() = {
    val images = Await.result(Docker().images(), Duration.Inf)
    log.debug("images: {}", images)

    assertThat("Images exist", images.nonEmpty)
  }

}
