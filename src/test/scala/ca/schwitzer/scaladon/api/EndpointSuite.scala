package ca.schwitzer.scaladon.api

import io.circe.Json
import munit.FunSuite

import ca.schwitzer.scaladon.http.HttpMethod

class EndpointSuite extends FunSuite:

  test("get defaults to an authenticated GET with no body or form") {
    val endpoint = Endpoint.get[String]("/api/v1/thing")
    assertEquals(endpoint.method, HttpMethod.GET)
    assertEquals(endpoint.path, "/api/v1/thing")
    assertEquals(endpoint.query, Nil)
    assertEquals(endpoint.body, None)
    assertEquals(endpoint.form, None)
    assertEquals(endpoint.authenticated, true)
  }

  test("get can be public and carry an initial query") {
    val endpoint =
      Endpoint.get[String]("/api/v1/thing", query = List("limit" -> "5"), authenticated = false)
    assertEquals(endpoint.authenticated, false)
    assertEquals(endpoint.query, List("limit" -> "5"))
  }

  test("post, patch and their form variants set the right method and payload") {
    val body = Json.obj("status" -> Json.fromString("hi"))
    assertEquals(Endpoint.post[String]("/x", body).method, HttpMethod.POST)
    assertEquals(Endpoint.post[String]("/x", body).body, Some(body))
    assertEquals(Endpoint.patch[String]("/x", body).method, HttpMethod.PATCH)
    assertEquals(Endpoint.patch[String]("/x", body).body, Some(body))

    val postForm = Endpoint.postForm[String]("/x", List("a" -> "b"))
    assertEquals(postForm.method, HttpMethod.POST)
    assertEquals(postForm.body, None)
    assertEquals(postForm.form, Some(List("a" -> "b")))

    val patchForm = Endpoint.patchForm[String]("/x", List("a" -> "b"))
    assertEquals(patchForm.method, HttpMethod.PATCH)
    assertEquals(patchForm.form, Some(List("a" -> "b")))
  }

  test("unit and unitForm decode any response body to Unit") {
    val unit = Endpoint.unit(HttpMethod.DELETE, "/x")
    assertEquals(unit.decode(Json.obj()), Right(()))
    assertEquals(unit.decode(Json.fromString("ignored")), Right(()))

    val form = Endpoint.unitForm(HttpMethod.POST, "/x", List("a" -> "b"))
    assertEquals(form.method, HttpMethod.POST)
    assertEquals(form.form, Some(List("a" -> "b")))
    assertEquals(form.decode(Json.obj()), Right(()))
  }

  test("withQuery appends without mutating the original endpoint") {
    val original = Endpoint.get[String]("/x", query = List("a" -> "1"))
    val extended = original.withQuery("b" -> "2", "b" -> "3")
    assertEquals(original.query, List("a" -> "1"))
    assertEquals(extended.query, List("a" -> "1", "b" -> "2", "b" -> "3"))
  }
