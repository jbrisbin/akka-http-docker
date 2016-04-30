package com.jbrisbin.docker

import java.time.Instant

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
sealed trait DockerMessage {
}

case class Port(`private`: Int, `public`: Int, `type`: String = "tcp")

case class Container(id: String,
                     names: Seq[String],
                     image: String,
                     command: String,
                     created: Instant,
                     status: String,
                     ports: Seq[Port]) extends DockerMessage

case class Containers(all: Boolean = false,
                      limit: Int = 0,
                      since: String = null,
                      before: String = null,
                      size: Boolean = false,
                      filters: Map[String, Seq[String]] = Map.empty) extends DockerMessage

case class Image(id: String,
                 parentId: String,
                 repoTags: Seq[String],
                 repoDigests: Seq[String],
                 created: Instant,
                 size: BigInt,
                 virtualSize: BigInt,
                 labels: Map[String, String])

case class Run(image: String) extends DockerMessage
