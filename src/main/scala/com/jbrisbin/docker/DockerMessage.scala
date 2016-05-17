package com.jbrisbin.docker

import akka.util.ByteString

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
sealed trait DockerMessage {
}

// Requests
final case class HostConfig(Binds: Option[Seq[String]] = None,
                            Links: Option[Seq[String]] = None,
                            Memory: Option[BigInt] = None,
                            MemorySwap: Option[BigInt] = None,
                            MemoryReservation: Option[BigInt] = None,
                            KernelMemory: Option[BigInt] = None,
                            CpuShares: Option[BigInt] = None,
                            CpuPeriod: Option[BigInt] = None,
                            PortBindings: Option[Map[String, Seq[Map[String, String]]]] = None,
                            PublishAllPorts: Boolean = true)

final case class CreateContainer(Name: String = "",
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

final case class Run(image: String) extends DockerMessage

final case class Stop() extends DockerMessage

final case class Exec(Cmd: Seq[String],
                      AttachStdin: Boolean = false,
                      AttachStdout: Boolean = true,
                      AttachStderr: Boolean = true,
                      DetachKeys: Option[String] = None,
                      Detach: Boolean = false,
                      Tty: Boolean = true) extends DockerMessage

final case class ExecStart(Detach: Boolean = false,
                           Tty: Boolean = false) extends DockerMessage

trait ExecOutput extends DockerMessage

final case class StdOut(bytes: ByteString) extends ExecOutput

final case class StdErr(bytes: ByteString) extends ExecOutput

final case class Complete() extends ExecOutput

// Responses
final case class Port(PrivatePort: Int, PublicPort: Int, Type: String = "tcp")

final case class Network(IPAMConfig: Option[Map[String, String]],
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

final case class NetworkSettings(Networks: Option[Map[String, Network]])

final case class Container(Id: String = null,
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

final case class ContainerState(Status: String = null,
                                Running: Boolean = false,
                                Paused: Boolean = false,
                                Restarting: Boolean = false,
                                OOMKilled: Boolean = false,
                                Dead: Boolean = false,
                                Pid: Int = 0,
                                ExitCode: Int = 0,
                                Error: String = null,
                                StartedAt: String = null,
                                FinishedAt: String = null) extends DockerMessage

final case class ContainerConfig(Hostname: String = null,
                                 Domainname: String = null,
                                 User: String = null,
                                 AttachStdin: Boolean = false,
                                 AttachStdout: Boolean = true,
                                 AttachStderr: Boolean = true,
                                 ExposedPorts: Map[String, Port] = Map.empty,
                                 Tty: Boolean = true,
                                 OpenStdin: Boolean = false,
                                 StdinOnce: Boolean = false,
                                 Env: Seq[String] = Seq.empty,
                                 Cmd: Seq[String] = Seq.empty,
                                 Image: String = null,
                                 Volumes: Map[String, String] = Map.empty) extends DockerMessage

final case class ContainerInfo(Id: String = null,
                               Name: String = null,
                               Path: String = null,
                               Args: Seq[String] = Seq.empty,
                               Created: Long = 0,
                               State: ContainerState = null,
                               Image: String = null,
                               RestartCount: Int = 0,
                               Driver: String = null,
                               MountLabel: String = null,
                               ProcessLabel: String = null,
                               AppArmorProfile: String = null,
                               ExecIDs: Option[AnyRef] = None,
                               HostConfig: HostConfig = null,
                               Mounts: Seq[Map[String, AnyRef]] = null,
                               Config: ContainerConfig = null,
                               NetworkSettings: NetworkSettings = null) extends DockerMessage

final case class Image(Id: String,
                       ParentId: String,
                       RepoTags: Seq[String],
                       RepoDigests: Seq[String],
                       Created: Long,
                       Size: BigInt,
                       VirtualSize: BigInt,
                       Labels: Option[Map[String, String]])
