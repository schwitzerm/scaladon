package ca.schwitzer.scaladon_soc.http

import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, WordSpec}

class HttpRequestFactorySpec extends WordSpec with Matchers with PropertyChecks {
  val factory: HttpRequestFactory = new HttpRequestFactoryImpl

  "The HttpRequestFactory" should {
    "produce expected fetch account requests" in {
      forAll { id: Int =>
        whenever(id > 0) {
          val expectedURI = s"/api/v1/accounts/$id"
          val attempt = factory.fetchAccountRequest(id)

          attempt.isSuccess shouldBe true
          attempt.get.uri.toString shouldBe expectedURI
        }
      }
    }

    "produce IndexOutOfBoundsException exceptions from fetch account requests where the ID is invalid" in {
      forAll { id: Int =>
        whenever(id < 1) {
          val attempt = factory.fetchAccountRequest(id)

          attempt.isFailure shouldBe true
          attempt.failed.get.isInstanceOf[IndexOutOfBoundsException] shouldBe true
        }
      }
    }

    "produce expected fetch followers requests" in {
      forAll { id: Int =>
        whenever(id > 0) {
          val expectedURI = s"/api/v1/accounts/$id/followers"
          val attempt = factory.fetchFollowersRequest(id)

          attempt.isSuccess shouldBe true
          attempt.get.uri.toString shouldBe expectedURI
        }
      }
    }

    "produce IndexOutOfBoundsException exceptions from fetch followers requests where the ID is invalid" in {
      forAll { id: Int =>
        whenever(id < 0) {
          val attempt = factory.fetchFollowersRequest(id)

          attempt.isFailure shouldBe true
          attempt.failed.get.isInstanceOf[IndexOutOfBoundsException] shouldBe true
        }
      }
    }

    "produce expected fetch following requests" in {
      forAll { id: Int =>
        whenever(id > 0) {
          val expectedURI = s"/api/v1/accounts/$id/following"
          val attempt = factory.fetchFollowingRequest(id)

          attempt.isSuccess shouldBe true
          attempt.get.uri.toString shouldBe expectedURI
        }
      }
    }

    "produce IndexOutOfBoundsException exceptions from fetch following requests where the ID is invalid" in {
      forAll { id: Int =>
        whenever(id < 1) {
          val attempt = factory.fetchFollowingRequest(id)

          attempt.isFailure shouldBe true
          attempt.failed.get.isInstanceOf[IndexOutOfBoundsException] shouldBe true
        }
      }
    }
  }
}
