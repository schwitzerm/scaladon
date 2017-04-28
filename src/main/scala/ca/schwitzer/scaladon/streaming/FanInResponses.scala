package ca.schwitzer.scaladon.streaming

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._
import ca.schwitzer.scaladon.models.mastodon.{Notification, Status}
import ca.schwitzer.scaladon.streaming.StreamProcesses.{StreamEvent, StreamPayload}
import play.api.libs.json._

import scala.collection.immutable

case class FanInResponsesShape(eventsIn: Inlet[StreamEvent],
                               payloadsIn: Inlet[StreamPayload],
                               responsesOut: Outlet[StreamResponse]) extends Shape {
  override val inlets: immutable.Seq[Inlet[_]] = eventsIn :: payloadsIn :: Nil
  override val outlets: immutable.Seq[Outlet[_]] = responsesOut :: Nil

  override def deepCopy() = FanInResponsesShape(
    eventsIn = eventsIn.carbonCopy(),
    payloadsIn = payloadsIn.carbonCopy(),
    responsesOut = responsesOut.carbonCopy()
  )
}

object FanInResponses {
  def apply(): Graph[FanInResponsesShape, NotUsed] = {
    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val events = b.add(Broadcast[StreamEvent](1))
      val payloads = b.add(Broadcast[StreamPayload](1))
      val responses = b.add(Broadcast[StreamResponse](1))

      val zip = b.add(ZipWith[StreamEvent, StreamPayload, StreamResponse]((evt, payload) => evt.eventType match {
        case "update" => StreamResponses.UpdateResponse(Json.parse(payload.data).as[Status])
        case "notification" => StreamResponses.NotificationResponse(Json.parse(payload.data).as[Notification])
        case "delete" => StreamResponses.DeleteResponse((Json.parse(payload.data) \ "id").as[Int])
      }))

      events ~> zip.in0
      payloads ~> zip.in1
      zip.out ~> responses.in

      FanInResponsesShape(
        eventsIn = events.in,
        payloadsIn = payloads.in,
        responsesOut = responses.out(0)
      )
    }
  }
}
