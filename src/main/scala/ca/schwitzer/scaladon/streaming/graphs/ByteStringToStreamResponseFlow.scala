package ca.schwitzer.scaladon.streaming.graphs

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import ca.schwitzer.scaladon.streaming.StreamResponse

object ByteStringToStreamResponseFlow {
  def apply(): Flow[ByteString, StreamResponse, NotUsed] = Flow.fromGraph(GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._

    val in = b.add(Broadcast[ByteString](1))
    val graph = b.add(TransformByteStringToStreamResponse())

    in ~> graph.bsIn

    FlowShape(in.in, graph.responseOut)
  })
}
