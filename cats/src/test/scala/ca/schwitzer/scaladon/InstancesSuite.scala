package ca.schwitzer.scaladon

import cats.{Eq, Order, Show}
import munit.FunSuite

import ca.schwitzer.scaladon.domain.{HttpStatus, Id, Visibility}
import ca.schwitzer.scaladon.http.BearerToken
import ca.schwitzer.scaladon.instances.given

class InstancesSuite extends FunSuite:

  test("Eq[Id] equates equal ids and distinguishes different ids") {
    assert(Eq[Id].eqv(Id("7"), Id("7")))
    assert(!Eq[Id].eqv(Id("7"), Id("8")))
  }

  test("Order[Id] orders by the underlying string") {
    assert(Order[Id].lt(Id("10"), Id("2")))
    assert(Order[Id].lt(Id("2"), Id("3")))
  }

  test("Show[Id] is the raw value") {
    assertEquals(Show[Id].show(Id("42")), "42")
  }

  test("Show[Visibility] and Show[HttpStatus] render the wire value and the code") {
    assertEquals(Show[Visibility].show(Visibility.Public), "public")
    assertEquals(Show[HttpStatus].show(HttpStatus(404)), "404")
  }

  test("Show[BearerToken] redacts the secret") {
    val shown = Show[BearerToken].show(BearerToken("super-secret"))
    assertEquals(shown, "BearerToken(**redacted**)")
    assert(!shown.contains("super-secret"))
  }
