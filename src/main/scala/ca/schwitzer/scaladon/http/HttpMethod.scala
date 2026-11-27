package ca.schwitzer.scaladon.http

/** HTTP verbs the Mastodon API uses. */
enum HttpMethod(val name: String):
  case GET    extends HttpMethod("GET")
  case POST   extends HttpMethod("POST")
  case PUT    extends HttpMethod("PUT")
  case PATCH  extends HttpMethod("PATCH")
  case DELETE extends HttpMethod("DELETE")
