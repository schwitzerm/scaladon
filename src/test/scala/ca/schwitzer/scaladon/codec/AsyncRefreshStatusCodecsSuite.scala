package ca.schwitzer.scaladon.codec

import io.circe.parser.decode
import munit.FunSuite

import ca.schwitzer.scaladon.codec.Codecs.given
import ca.schwitzer.scaladon.domain.{AsyncRefreshState, AsyncRefreshStatus}

class AsyncRefreshStatusCodecsSuite extends FunSuite:

  private val refreshId =
    "ImNvbnRleHQ6MTEzNjQwNTczMzAzNzg1MTc4OnJlZnJlc2gi--c526259eb4a1f3ef0d4b91cf8c99bf501330a815"

  test("the entity decodes its id, running status and result count") {
    val json =
      s"""{"id":"$refreshId","status":"running","result_count":2}"""
    val status = decode[AsyncRefreshStatus](json).toOption.get
    assertEquals(status.id, refreshId)
    assertEquals(status.status, AsyncRefreshState.Running)
    assertEquals(status.resultCount, Some(2))
  }

  test("a finished refresh with a null result_count decodes to None") {
    val json =
      s"""{"id":"$refreshId","status":"finished","result_count":null}"""
    val status = decode[AsyncRefreshStatus](json).toOption.get
    assertEquals(status.status, AsyncRefreshState.Finished)
    assertEquals(status.resultCount, None)
  }

  test("a finished refresh with result_count 0 keeps the zero") {
    val json =
      s"""{"id":"$refreshId","status":"finished","result_count":0}"""
    assertEquals(decode[AsyncRefreshStatus](json).toOption.get.resultCount, Some(0))
  }

  test("an omitted result_count decodes to None") {
    val json = s"""{"id":"$refreshId","status":"running"}"""
    assertEquals(decode[AsyncRefreshStatus](json).toOption.get.resultCount, None)
  }

  test("an unknown status fails decoding") {
    val json = s"""{"id":"$refreshId","status":"paused"}"""
    assert(decode[AsyncRefreshStatus](json).isLeft)
  }
