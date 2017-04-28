package ca.schwitzer.scaladon.streaming.graphs

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import ca.schwitzer.scaladon.streaming.{StreamMessage, StreamMessages, StreamResponse}
import com.typesafe.scalalogging.LazyLogging

import scala.collection.immutable

case class TransformByteStringToStreamResponseShape(bsIn: Inlet[ByteString],
                                                    responseOut: Outlet[StreamResponse]) extends Shape {
  override val inlets: immutable.Seq[Inlet[_]] = bsIn :: Nil
  override val outlets: immutable.Seq[Outlet[_]] = responseOut :: Nil

  override def deepCopy(): TransformByteStringToStreamResponseShape = TransformByteStringToStreamResponseShape(
    bsIn = bsIn.carbonCopy(),
    responseOut = responseOut.carbonCopy()
  )
}

object TransformByteStringToStreamResponse extends LazyLogging {
  final val messagesFlow: Flow[ByteString, StreamMessage, NotUsed] = Flow[ByteString]
    .map(_.utf8String)
    .map {
      case str if str.startsWith(":")     => StreamMessages.Heartbeat
      case str if str.startsWith("event") => StreamMessages.Event(str.split(" ").last.stripLineEnd)
      case str if str.startsWith("data")  => StreamMessages.Payload(str.split(" ").tail.mkString)
    }

  def apply(): Graph[TransformByteStringToStreamResponseShape, NotUsed] = {
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

      TransformByteStringToStreamResponseShape(
        bsIn = in.in,
        responseOut = fanIn.responseOut
      )
    }
  }
}
