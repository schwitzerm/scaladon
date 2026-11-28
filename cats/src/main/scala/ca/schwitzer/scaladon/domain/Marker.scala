package ca.schwitzer.scaladon.domain

import java.time.Instant

/** A timeline whose read position can be saved with the markers API.
  *
  * An enum with a total `fromWire`, like [[Visibility]]: the vocabulary is closed in
  * API version 12.
  */
enum MarkerTimeline(val wire: String):
  case Home          extends MarkerTimeline("home")
  case Notifications extends MarkerTimeline("notifications")

object MarkerTimeline:
  def all: List[MarkerTimeline] = values.toList

  /** Total inverse of [[MarkerTimeline.wire]]; `None` for values this version does not know. */
  def fromWire(value: String): Option[MarkerTimeline] = all.find(_.wire == value)

/** A saved read position in one timeline.
  *
  * `version` is bumped by the server on every write and can be used to detect a stale client;
  * a conflicting write answers `409`.
  */
final case class Marker(lastReadId: Id, version: Int, updatedAt: Instant)
