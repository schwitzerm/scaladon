package ca.schwitzer.scaladon

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import ca.schwitzer.scaladon.streaming.{Graphs, StreamMessages, StreamResponse}
import org.scalatest._
import org.scalatest.concurrent._

import scala.collection.immutable
import scala.concurrent.ExecutionContext

class FanInMessagesSpec extends WordSpec with Matchers with ScalaFutures {
  implicit val sys: ActorSystem = ActorSystem()
  implicit val mat: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

  "The graph" should {
    "do the thing" in {
      val events = Source(
        immutable.Seq(
          StreamMessages.Event("delete"),
          StreamMessages.Event("notification")
        )
      )

      val payloads = Source(
        immutable.Seq(
          StreamMessages.Payload(
           """{
             |"id": 1
             |}
           """.stripMargin),
          StreamMessages.Payload(
            """{
              |"id": 1,
              |"type": "follow",
              |"created_at": "2017-04-28T04:35:28.771Z",
              |"account": {"id":1,"username":"user","acct":"user","display_name":"User","locked":false,"created_at":"2017-04-04T19:55:36.754Z","note":"","url":"https://marxism.party/@user","avatar":"https://d1snqgixfyzghf.cloudfront.net/accounts/avatars/000/000/001/original/92a2e2f80992e49d.jpg?1491343907","header":"/headers/original/missing.png","followers_count":58,"following_count":24,"statuses_count":42},
              |"status": null
              |}
            """.stripMargin)
        )
      )

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._

        val fanIn = b.add(Graphs.FanInMessages())
        val printFlow = Flow[StreamResponse].map(println)

        events ~> fanIn.eventIn
        payloads ~> fanIn.payloadIn
        fanIn.responseOut ~> printFlow ~> Sink.ignore

        ClosedShape
      }).run()
    }
  }
}
