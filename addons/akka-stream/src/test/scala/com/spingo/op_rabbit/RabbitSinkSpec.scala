package com.spingo.op_rabbit

import akka.actor._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.Timeout
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.Envelope
import com.spingo.op_rabbit.helpers.{DeleteQueue, RabbitTestHelpers}
import com.spingo.op_rabbit.consumer.Directives._
import com.spingo.scoped_fixtures.ScopedFixtures
import org.scalatest.{FunSpec, Matchers}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try
import shapeless._

class RabbitSinkSpec extends FunSpec with ScopedFixtures with Matchers with RabbitTestHelpers {
  implicit val executionContext = ExecutionContext.global

  val queueName = ScopedFixture[String] { setter =>
    val name = s"test-queue-rabbit-control-${Math.random()}"
    deleteQueue(name)
    val r = setter(name)
    deleteQueue(name)
    r
  }

  trait RabbitFixtures {
    implicit val materializer = ActorMaterializer()
    val exceptionReported = Promise[Boolean]
    implicit val errorReporting = new consumer.RabbitErrorLogging {
      def apply(name: String, message: String, exception: Throwable, consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]): Unit = {
        exceptionReported.success(true)
      }
    }
    val rabbitControl = actorSystem.actorOf(Props(new RabbitControl))
    val range = (0 to 16)
    val qos = 8
  }

  describe("RabbitSink") {
    it("publishes all messages consumed, and acknowledges the promises") {
      new RabbitFixtures {
        import consumer.HListToValueOrTuple

        val (subscription, consumed) = consumer.RabbitSource(
          "very-stream",
          rabbitControl,
          channel(qos),
          consume(queue(queueName(), durable = true, exclusive = false, autoDelete = false)),
          body(as[Int])).
          acked.
          take(range.length).
          toMat(Sink.fold(List.empty[Int]) {
            case (acc, v) =>
              println(s"${v == range.max} ${v} == ${range.max}")
              acc ++ List(v)
          })(Keep.both).run

        await(subscription.initialized)

        val sink = RabbitSink[Int](
          "test-sink",
          rabbitControl,
          ConfirmedMessage.factory(QueuePublisher(queueName())))

        val data = range map { i => (Promise[Unit], i) }

        val published = Source(data).
          runWith(sink)

        await(published)
        await(Future.sequence(data.map(_._1.future))) // this asserts that all of the promises were fulfilled
        await(consumed) should be (range)
      }
    }
  }
}
