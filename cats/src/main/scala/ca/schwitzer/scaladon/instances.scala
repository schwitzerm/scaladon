package ca.schwitzer.scaladon

import cats.{Eq, Order, Show}

import ca.schwitzer.scaladon.domain.{
  HttpStatus,
  Id,
  PreviewCardType,
  QuoteApprovalPolicy,
  QuoteState,
  Visibility
}
import ca.schwitzer.scaladon.http.BearerToken

/** The Cats instances for core values.
  *
  * A companion must live beside its type, so these cannot stay in `scaladon-core` without putting
  * Cats on the core classpath. They are moved verbatim from the companions; import
  * `ca.schwitzer.scaladon.instances.*` to bring them into scope.
  */
object instances:

  given Eq[Id] = Eq.fromUniversalEquals

  given Order[Id] = Order.fromOrdering(using Ordering.by[Id, String](_.value))

  given Show[Id] = Show.show(_.value)

  given Show[Visibility] = Show.show(_.wire)

  given Show[QuoteState] = Show.show(_.wire)

  given Show[QuoteApprovalPolicy] = Show.show(_.wire)

  given Show[PreviewCardType] = Show.show(_.wire)

  given Show[HttpStatus] = Show.show(_.code.toString)

  given Show[BearerToken] = Show.show(_ => "BearerToken(**redacted**)")
