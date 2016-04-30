# Docker Akka HTTP 

`akka-http-docker` is a Docker client that is based on Akka HTTP. It's aim is to provide a Scala and Akka Streams-centric approach to interacting with Docker.

### Building

Use SBT:

    $ sbt package
    
### Using

The API will be simple in order to express the maximum amount of information with the least amount of static. To list all containers, and map the IDs of those containers to another processing chain, you would do the following:

```scala
    Docker().containers() onComplete {
      case Success(c) => c.map(_.id)
      case Failure(ex) => log.error(ex.getMessage, ex)
    }
```

### License

Apache 2.0
