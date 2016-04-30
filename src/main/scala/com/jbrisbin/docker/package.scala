package com.jbrisbin

import java.time.Instant

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
package object docker {

  implicit def map2image(m: Map[String, AnyRef]): Image = {
    Image(
      id = m("Id").asInstanceOf[String],
      parentId = m("ParentId").asInstanceOf[String],
      repoTags = m.getOrElse("RepoTags", Seq.empty).asInstanceOf[Seq[String]] match {
        case null => Seq.empty
        case s => s
      },
      repoDigests = m.getOrElse("RepoDigests", Seq.empty).asInstanceOf[Seq[String]],
      created = Instant.ofEpochSecond(m.getOrElse("Created", 0).asInstanceOf[BigInt].toLong),
      size = m("Size").asInstanceOf[BigInt].toLong,
      virtualSize = m("VirtualSize").asInstanceOf[BigInt].toLong,
      labels = m.getOrElse("Labels", Map.empty[String, String]).asInstanceOf[Map[String, String]] match {
        case null => Map.empty
        case m => m
      }
    )
  }

  implicit def map2container(m:Map[String,AnyRef]):Container = {
    Container(
      id = m("Id").asInstanceOf[String],
      names = m.getOrElse("Names", Seq.empty).asInstanceOf[Seq[String]],
      image = m("Image").asInstanceOf[String],
      command = m("Command").asInstanceOf[String],
      created = Instant.ofEpochSecond(m.getOrElse("Created", 0).asInstanceOf[BigInt].toLong),
      status = m("Status").asInstanceOf[String],
      ports = m.getOrElse("Ports", Seq.empty).asInstanceOf[List[Map[String, AnyRef]]]
    )
  }

  implicit private def maps2ports(l: List[Map[String, AnyRef]]): Seq[Port] = {
    l.map(m => {
      Port(`private` = m("PrivatePort").asInstanceOf[BigInt].toInt,
        `public` = m("PublicPort").asInstanceOf[BigInt].toInt,
        `type` = m("Type").asInstanceOf[String])
    })
  }

}
