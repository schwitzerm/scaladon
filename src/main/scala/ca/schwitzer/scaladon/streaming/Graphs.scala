package ca.schwitzer.scaladon.streaming

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Partition, Sink, ZipWith}
import akka.util.ByteString
import ca.schwitzer.scaladon.models.mastodon.{Notification, Status}
import com.typesafe.scalalogging.LazyLogging
import play.api.libs.json.Json

import scala.collection.immutable

object Graphs {
  case class FanInMessagesShape(eventIn: Inlet[StreamMessages.Event],
                                payloadIn: Inlet[StreamMessages.Payload],
                                responseOut: Outlet[StreamResponse]) extends Shape {
    override val inlets: immutable.Seq[Inlet[_]] = eventIn :: payloadIn :: Nil
    override val outlets: immutable.Seq[Outlet[_]] = responseOut :: Nil

    override def deepCopy() = FanInMessagesShape(
      eventIn = eventIn.carbonCopy(),
      payloadIn = payloadIn.carbonCopy(),
      responseOut = responseOut.carbonCopy()
    )
  }

  object FanInMessages {
    def apply(): Graph[FanInMessagesShape, NotUsed] = {
      GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._

        val event = b.add(Broadcast[StreamMessages.Event](1))
        val payload = b.add(Broadcast[StreamMessages.Payload](1))

        val zip = b.add(ZipWith[StreamMessages.Event, StreamMessages.Payload, StreamResponse]((evt, payload) => evt.eventType match {
          case "update" => StreamResponses.UpdateResponse(Json.parse(payload.data).as[Status])
          case "notification" => StreamResponses.NotificationResponse(Json.parse(payload.data).as[Notification])
          case "delete" => StreamResponses.DeleteResponse((Json.parse(payload.data) \ "id").as[Int])
        }))

        event ~> zip.in0
        payload ~> zip.in1

        FanInMessagesShape(
          eventIn = event.in,
          payloadIn = payload.in,
          responseOut = zip.out
        )
      }
    }
  }


  case class StreamResponseGraphShape(bsIn: Inlet[ByteString],
                                      responseOut: Outlet[StreamResponse]) extends Shape {
    override val inlets: immutable.Seq[Inlet[_]] = bsIn :: Nil
    override val outlets: immutable.Seq[Outlet[_]] = responseOut :: Nil

    override def deepCopy(): StreamResponseGraphShape = StreamResponseGraphShape(
      bsIn = bsIn.carbonCopy(),
      responseOut = responseOut.carbonCopy()
    )
  }

  object StreamResponseGraph extends LazyLogging {
    final val messagesFlow: Flow[ByteString, StreamMessage, NotUsed] = Flow[ByteString]
      .map(_.utf8String)
      .map {
        case str if str.startsWith(":")     => StreamMessages.Heartbeat
        case str if str.startsWith("event") => StreamMessages.Event(str.split(" ").last.stripLineEnd)
        case str if str.startsWith("data")  => StreamMessages.Payload(str.split(" ").tail.mkString)
      }

    def apply(): Graph[StreamResponseGraphShape, NotUsed] = {
      GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._

        val in = b.add(Broadcast[ByteString](1))
        val fanIn = b.add(FanInMessages())

        val partition = b.add(Partition[StreamMessage](3, {
          case _: StreamMessages.Event => 0
          case _: StreamMessages.Payload => 1
          case StreamMessages.Heartbeat => 2
        }))

        val evt = b.add(Flow[StreamMessage].map { case e @ StreamMessages.Event(_) => e })
        val payload = b.add(Flow[StreamMessage].map { case p @ StreamMessages.Payload(_) => p })
        val heartbeat = b.add(Flow[StreamMessage].map { case h @ StreamMessages.Heartbeat => h })

        in ~> messagesFlow ~> partition
        partition ~> evt ~> fanIn.eventIn
        partition ~> payload ~> fanIn.payloadIn
        partition ~> heartbeat ~> Sink.ignore

        StreamResponseGraphShape(
          bsIn = in.in,
          responseOut = fanIn.responseOut
        )
      }
    }
  }
}
