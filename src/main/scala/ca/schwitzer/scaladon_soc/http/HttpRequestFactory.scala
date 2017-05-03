package ca.schwitzer.scaladon_soc.http

import akka.http.scaladsl.model.{HttpMethods, HttpRequest}

import scala.util.Try

trait HttpRequestFactory {
  def fetchAccountRequest(accountId: Int): Try[HttpRequest]
  def fetchFollowersRequest(accountId: Int): Try[HttpRequest]
  def fetchFollowingRequest(accountId: Int): Try[HttpRequest]

  def fetchCurrentUserRequest: HttpRequest
  def fetchInstanceRequest: HttpRequest
}

class HttpRequestFactoryImpl extends HttpRequestFactory {
  private final val accountIdException = new IndexOutOfBoundsException("Account ID must be greater than 0!")

  override def fetchAccountRequest(accountId: Int): Try[HttpRequest] = Try {
    if(accountId < 1) { throw accountIdException }
    else { HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$accountId") }
  }

  override def fetchFollowersRequest(accountId: Int): Try[HttpRequest] = Try {
    if(accountId < 1) { throw accountIdException }
    else { HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$accountId/followers") }
  }

  override def fetchFollowingRequest(accountId: Int): Try[HttpRequest] = Try {
    if(accountId < 1) { throw accountIdException }
    else { HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/$accountId/following") }
  }



  override def fetchCurrentUserRequest: HttpRequest = {
    HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/accounts/verify_credentials")
  }

  override def fetchInstanceRequest: HttpRequest = {
    HttpRequest(method = HttpMethods.GET, uri = s"/api/v1/instance")
  }
}
