package ca.schwitzer.scaladon

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import ca.schwitzer.scaladon.streaming.{FanInResponses, StreamResponse}
import ca.schwitzer.scaladon.streaming.StreamProcesses.{StreamEvent, StreamPayload}
import org.scalatest._
import org.scalatest.concurrent._

import scala.concurrent.ExecutionContext
import scala.collection.immutable

class FanInResponsesSpec extends WordSpec with Matchers with ScalaFutures {
  implicit val sys: ActorSystem = ActorSystem()
  implicit val mat: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

  "The graph" should {
    "do the thing" in {
      val events = Source(
        immutable.Seq(
          StreamEvent("delete"),
          StreamEvent("notification")
        )
      )

      val payloads = Source(
        immutable.Seq(
          StreamPayload(
           """{
             |"id": 1
             |}
           """.stripMargin),
          StreamPayload(
            """{
              |"id": 1,
              |"type": "follow",
              |"created_at": "2017-04-28T04:35:28.771Z",
              |"account": {"id":1,"username":"elgruntox","acct":"elgruntox","display_name":"ElGruntox","locked":false,"created_at":"2017-04-04T19:55:36.754Z","note":"<a href=\"http://github.com/ell\" rel=\"nofollow noopener\" target=\"_blank\"><span class=\"invisible\">http://</span><span class=\"\">github.com/ell</span><span class=\"invisible\"></span></a>","url":"https://marxism.party/@elgruntox","avatar":"https://d1snqgixfyzghf.cloudfront.net/accounts/avatars/000/000/001/original/92a2e2f80992e49d.jpg?1491343907","header":"/headers/original/missing.png","followers_count":58,"following_count":24,"statuses_count":42},
              |"status": null
              |}
            """.stripMargin)
        )
      )

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._

        val fanIn = b.add(FanInResponses())
        val printFlow = Flow[StreamResponse].map(println)

        events ~> fanIn.eventsIn
        payloads ~> fanIn.payloadsIn
        fanIn.responsesOut ~> printFlow ~> Sink.ignore

        ClosedShape
      }).run()
    }
  }
}
