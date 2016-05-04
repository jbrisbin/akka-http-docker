package com.jbrisbin.docker

import akka.util.ByteString

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
sealed trait DockerMessage {
}

// Requests
case class HostConfig(Binds: Option[Seq[String]] = None,
                      Links: Option[Seq[String]] = None,
                      Memory: Option[BigInt] = None,
                      MemorySwap: Option[BigInt] = None,
                      MemoryReservation: Option[BigInt] = None,
                      KernelMemory: Option[BigInt] = None,
                      CpuShares: Option[BigInt] = None,
                      CpuPeriod: Option[BigInt] = None,
                      PortBindings: Option[Map[String, Seq[Map[String, String]]]] = None,
                      PublishAllPorts: Boolean = true)

case class CreateContainer(Name: String = "",
                           Hostname: Option[String] = None,
                           Domainname: String = "",
                           User: String = "",
                           AttachStdin: Boolean = false,
                           AttachStdout: Boolean = true,
                           AttachStderr: Boolean = true,
                           Tty: Boolean = false,
                           OpenStdin: Boolean = false,
                           StdinOnce: Boolean = false,
                           Env: Option[Seq[String]] = None,
                           Cmd: Option[Seq[String]] = None,
                           Entrypoint: Option[String] = None,
                           Image: String,
                           Labels: Option[Map[String, String]] = None,
                           Volumes: Map[String, String] = Map.empty,
                           WorkingDir: String = "",
                           NetworkDisabled: Boolean = false,
                           MacAddress: String = "",
                           ExposedPorts: Map[String, Port] = Map.empty,
                           StopSignal: String = "SIGTERM",
                           HostConfig: Option[HostConfig] = None)

case class Run(image: String) extends DockerMessage

case class Stop() extends DockerMessage

case class Exec(Cmd: Seq[String],
                AttachStdin: Boolean = false,
                AttachStdout: Boolean = true,
                AttachStderr: Boolean = true,
                DetachKeys: Option[String] = None,
                Tty: Boolean = true) extends DockerMessage

case class ExecStart(Id: String = null, Detach: Boolean = false, Tty: Boolean = false) extends DockerMessage

trait ExecOutput extends DockerMessage

case class StdOut(bytes: ByteString) extends ExecOutput

case class StdErr(bytes: ByteString) extends ExecOutput

case class Complete() extends ExecOutput

// Responses
case class Port(PrivatePort: Int, PublicPort: Int, Type: String = "tcp")

case class Network(IPAMConfig: Option[Map[String, String]],
                   Links: Option[Seq[String]],
                   Aliases: String,
                   NetworkID: String,
                   EndpointID: String,
                   Gateway: String,
                   IPAddress: String,
                   IPPrefixLen: Int,
                   IPv6Gateway: String,
                   GlobalIPv6Address: String,
                   GlobalIPv6PrefixLen: Int,
                   MacAddress: String)

case class NetworkSettings(Networks: Option[Map[String, Network]])

case class Container(Id: String = null,
                     Names: Seq[String] = Seq.empty,
                     Image: String = null,
                     Cmd: Seq[String] = Seq.empty,
                     Created: Long = 0,
                     Status: String = null,
                     Ports: Seq[Port] = Seq.empty,
                     Labels: Option[Map[String, String]] = None,
                     SizeRw: Option[Long] = None,
                     SizeRootFs: Option[Long] = None,
                     HostConfig: Map[String, String] = Map.empty,
                     NetworkSettings: NetworkSettings) extends DockerMessage

case class Image(Id: String,
                 ParentId: String,
                 RepoTags: Seq[String],
                 RepoDigests: Seq[String],
                 Created: Long,
                 Size: BigInt,
                 VirtualSize: BigInt,
                 Labels: Option[Map[String, String]])
