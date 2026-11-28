package ca.schwitzer.scaladon.domain

import munit.FunSuite

import scala.concurrent.duration.*

class ApiErrorSuite extends FunSuite:

  private val boom = new RuntimeException("connection refused")

  test("Transport exposes its cause, message and retryability") {
    val error = ApiError.Transport(boom)
    assertEquals(error.message, "Transport failure: connection refused")
    assertEquals(error.retryable, true)
    assertEquals(error.underlying, Some(boom))
    assertEquals(error.isNotFound, false)
    assertEquals(error.isUnauthorized, false)
  }

  test("Http keeps the status, detail and raw body") {
    val error = ApiError.Http(HttpStatus(422), Some("Validation failed"), """{"error":"Validation failed"}""")
    assertEquals(error.message, "HTTP 422: Validation failed")
    assertEquals(error.retryable, false)
  }

  test("Http falls back to the raw body for its message") {
    val error = ApiError.Http(HttpStatus(500), None, "  boom  ")
    assertEquals(error.message, "HTTP 500: boom")
  }

  test("Http retries 5xx and 408 only") {
    assertEquals(ApiError.Http(HttpStatus(500), None, "").retryable, true)
    assertEquals(ApiError.Http(HttpStatus(408), None, "").retryable, true)
    assertEquals(ApiError.Http(HttpStatus(404), None, "").retryable, false)
  }

  test("isNotFound and isUnauthorized read the status") {
    assert(ApiError.Http(HttpStatus.NotFound, None, "").isNotFound)
    assert(ApiError.Http(HttpStatus.Unauthorized, None, "").isUnauthorized)
    assert(!ApiError.Http(HttpStatus(403), None, "").isUnauthorized)
  }

  test("RateLimited carries the wait and is retryable") {
    val error = ApiError.RateLimited(Some(5.seconds), Some("slow down"))
    assertEquals(error.message, "HTTP 429, rate limited (retry after 5s): slow down")
    assertEquals(error.retryable, true)
    assertEquals(ApiError.RateLimited(None, None).message, "HTTP 429, rate limited")
  }

  test("Decode and NotAuthenticated are not retryable") {
    assertEquals(ApiError.Decode("bad json").message, "Could not decode response: bad json")
    assertEquals(ApiError.Decode("bad json").retryable, false)
    assertEquals(ApiError.NotAuthenticated("/x").message, "No access token configured for /x")
    assertEquals(ApiError.NotAuthenticated("/x").retryable, false)
  }

  test("fromThrowable recovers only ApiException payloads") {
    val error = ApiError.Http(HttpStatus.NotFound, None, "")
    assertEquals(ApiError.fromThrowable(ApiException(error)), Some(error))
    assertEquals(ApiError.fromThrowable(boom), None)
  }

  test("ApiException carries the message and no stack trace") {
    val error   = ApiError.NotAuthenticated("/x")
    val exception = ApiException(error)
    assertEquals(exception.getMessage, error.message)
    assertEquals(exception.error, error)
    assertEquals(exception.getStackTrace.toList, Nil)
  }
