package ca.schwitzer.scaladon.streaming.graphs

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._
import ca.schwitzer.scaladon.models.mastodon.{Notification, Status}
import ca.schwitzer.scaladon.streaming.{StreamMessages, StreamResponse, StreamResponses}
import play.api.libs.json._

import scala.collection.immutable

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
