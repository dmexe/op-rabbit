# Op-Rabbit

##### An opinionated RabbitMQ library.

# Intro

Op-Rabbit is a high-level, opinionated, composable, fault-tolerant library for interacting with RabbitMQ:

- Recovery:
    - Consumers automatically reconnect and subscribe if the connection is lost
    - Messages published can optionally 
- Integration
    - Connection settings pulled from Typesafe config library
    - Asyncronous, concurrent consumption using Scala native Futures or the new Akka Streams project.
    - Common pattern for serialization allows easy integration with serialization libraries such play-json or json4s
    - Common pattern for exception handling to publish errors to Airbrake, Syslog, or all of the above
- Modular
    - Composition favored over inheritance enabling flexible and high code reuse.
- Modeled
    - Queue binding, exchange binding modeled with case classes
    - Publishing mechansims also modeled
- Reliability
    - Builds on the excellent [Akka RabbitMQ client](https://github.com/thenewmotion/akka-rabbitmq) library for easy recovery.
    - Built-in consumer error recovery strategy in which messages are re-delivered to the message queue and retried (not implemented for akka-streams integration as retry mechanism affects message order)
    - With a single message, pause all consumers if service health check fails (IE: database unavailable); easily resume the same.
- Graceful shutdown
    - Consumers and streams can immediately unsubscribe, but stay alive long enough to wait for any messages to finish being processed.
- Tested
    - Extensive integration tests

## Installation

Add the SpinGo OSS repository and include in the dependencies of your chosing:

```scala
resolvers ++= Seq(
  "SpinGo OSS" at "http://spingo-oss.s3.amazonaws.com/repositories/releases"
)

val opRabbitVersion = "1.0.0-SNAPSHOT"

libraryDependencies ++= Seq(
  "com.spingo" %% "op-rabbit-core"        % opRabbitVersion,
  "com.spingo" %% "op-rabbit-play-json"   % opRabbitVersion,
  "com.spingo" %% "op-rabbit-airbrake"    % opRabbitVersion,
  "com.spingo" %% "op-rabbit-akka-stream" % opRabbitVersion
)
```

The following libraries are available

- op-rabbit-core
    - Implements basic patterns for serialization and message processing
- op-rabbit-play-json
    - Easily use Play Json formats to publish or consume messages; automatically sets RabbitMQ message headers to indicate content type
- op-rabbit-airbrake
    - Report consumer exceptions to airbrake.
- op-rabbit-akka-stream
    - Process or publish messages using Akka-streams

## Usage

Set up RabbitMQ connection information in `application.conf`:

```conf
rabbitmq {
  topic-exchange-name = "op-rabbit-testeroni"
  hosts = ["127.0.0.1"]
  username = "guest"
  password = "guest"
  port = 5672
  timeout = 3s
}
```

Note that hosts is an array; Connection attempts will be made to hosts in that order, with a default timeout of `3s`. This way you can specify addresses of your rabbitMQ cluster, and if one of the instances goes down, your application will automatically reconnect to another member of the cluster.

`topic-exchange-name` is the default topic exchange to use; this can be overriden by passing `exchange = "my-topic"` to TopicBinding or TopicMessage.


Boot up the RabbitMQ control actor:

```scala
implicit val actorSystem = ActorSystem("such-system")
val rabbitMq = actorSystem.actorOf(Props[RabbitControl])
```

### Set up a consumer: (Topic subscription)

(this example uses `op-rabbit-play-json`)

```scala
import com.spingo.op_rabbit.PlayJsonSupport._
import com.spingo.op_rabbit._

implicit val personFormat = Json.format[Person] // setup play-json serializer

val consumer = AsyncAckingConsumer("PersonSignup", qos = 3) { person: Person =>
  Future {
    // do work; when this Future completes, the message will be acknowledged.
    // if the Future fails, it will be automatically retried (up to 3 times, by default)
  }
}

val subscription = new Subscription(
  TopicBinding(
      queueName = "such-message-queue",
      topics = List("some-topic.#"),
  consumer)

rabbitMq ! subscription
```

The following methods are available on subscription:

```scala
// stop receiving new messages from RabbitMQ immediately; shut down consumer and channel as soon as pending messages are completed. A grace period of 30 seconds is given, after which the subscription forcefully shuts down.
subscription.close(30 seconds)

// Shut things down without a grace period
subscription.abort()

// Future[Unit] which completes once the provided binding has been applied (IE: queue has been created and topic bindings configured). Useful if you need to assert you don't send a message before a message queue is created in which to place it.
subscription.initialized

// Future[Unit] which completes when the subscription is closed.
subscription.closed

// Future[Unit] which completes when the subscription begins closing.
subscription.closing
```

### Publish a message:

```scala
rabbitMq ! TopicMessage(Person(name = "Mike How", age = 33), "some-topic.#")

rabbitMq ! QueueMessage(Person(name = "Ivanah Tinkle", age = 25), "such-message-queue")
```

By default, messages will be queued up until a connection is available.

### Consuming using Akka streams

(this example uses `op-rabbit-play-json` and `op-rabbit-akka-streams`)

```scala
import com.spingo.op_rabbit._
import com.spingo.op_rabbit.PlayJsonSupport._
implicit val workFormat = Json.format[Work] // setup play-json serializer

lazy val subscription = Subscription(
  new QueueBinding("such-queue", durable = true, exclusive = false, autoDelete = false),
  RabbitSource[Work](name = "very-stream", qos = qos)) // marshalling is automatically hooked up using implicits

rabbitMq ! subscription

Source(subscription.consumer).
  to(Sink.foreach {
    case (p, work) =>
      doWork(work)
      p.sucess() // fulfilling the promise causes the message to be acknowledge and removed from the queue
  })
  .run
```

### Publishing using Akka streams

(this example uses `op-rabbit-play-json` and `op-rabbit-akka-streams`)

```scala
import com.spingo.op_rabbit._
import com.spingo.op_rabbit.PlayJsonSupport._
implicit val workFormat = Format[Work] // setup play-json serializer

val sink = RabbitSink[Work](
  "my-sink-name",
  rabbitMq,
  GuaranteedPublishedMessage(QueuePublisher("such-queue")))

Source(1 to 15).
  map { i => (Promise[Unit], i) }.  // each promise will be completed by the sink when message delivery occurs
  to(sink)
  .run
```

If you can see the pattern here, combining an akka-stream rabbitmq consumer and publisher allows for guaranteed at-least-once message delivery from head to tail; in other words, don't acknowledge the original message until any and all side-effect events have been published and persisted.

# Notes

- Implementing your own serializers, error handling, consumer or subscription strategies is easy. See the source code for an example. Library is composable and any component can be swapped out for a different behavior.

- AsyncAckingConsumer automatically redelivers a message once on handler failure.

- System is fully fault-tolerant; consumers will automatically reconnect, re-subscribe in the event the RabbitMq connection goes away. By default, message deliveries will be retried until successful.