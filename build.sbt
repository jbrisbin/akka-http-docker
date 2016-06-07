import net.virtualvoid.sbt.graph.Plugin._

lazy val akkaHttpDocker = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    exportJars := true,
    scalaVersion := "2.11.8",

    organization := "com.jbrisbin.docker",
    name := "akka-http-docker",
    version := "0.1.0-SNAPSHOT",

    publishMavenStyle := true,
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    pomIncludeRepository := { _ => false },
    pomExtra := (
                <url>https://github.com/jbrisbin/akka-http-docker/</url>
                  <licenses>
                    <license>
                      <name>Apache-2.0</name>
                      <url>https://opensource.org/licenses/Apache-2.0</url>
                      <distribution>repo</distribution>
                    </license>
                  </licenses>
                  <scm>
                    <url>git@github.com:jbrisbin/akka-http-docker.git</url>
                    <connection>scm:git:git@github.com:jbrisbin/akka-http-docker.git</connection>
                  </scm>
                  <developers>
                    <developer>
                      <id>jbrisbin</id>
                      <name>Jon Brisbin</name>
                      <url>http://jbrisbin.com</url>
                    </developer>
                  </developers>
                ),

    resolvers ++= Seq(
      Resolver.bintrayRepo("hseeberger", "maven")
    ),

    libraryDependencies ++= {
      val scalaLoggingVersion = "2.1.2"
      val jacksonVersion = "2.7.3"
      val akkaVersion = "2.4.5"
      val junitVersion = "4.12"
      val scalaTestVersion = "3.0.0-M15"

      Seq(
        // Logging
        "com.typesafe.scala-logging" %% "scala-logging-slf4j" % scalaLoggingVersion,

        // Jackson JSON
        "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion,
        "org.json4s" %% "json4s-jackson" % "3.3.0",

        // Akka
        "com.typesafe.akka" %% "akka-stream" % akkaVersion,
        "com.typesafe.akka" %% "akka-http-core" % akkaVersion,
        "de.heikoseeberger" %% "akka-http-json4s" % "1.6.0",

        // SSL
        "org.apache.httpcomponents" % "httpclient" % "4.5.2",
        "org.bouncycastle" % "bcpkix-jdk15on" % "1.54",

        // Testing
        "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
        "ch.qos.logback" % "logback-classic" % "1.1.7" % "test"
      )
    },

    ivyScala := ivyScala.value map {
      _.copy(overrideScalaVersion = true)
    },

    graphSettings
  )
