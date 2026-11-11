package com.dwolla.postgres.init

import cats.effect.*
import natchez.{Kernel, Span, TraceValue}

import java.net.URI

/** A natchez span for tests that records the fields put on it and on its child spans. */
class RecordingSpan(recorded: Ref[IO, List[(String, TraceValue)]]) extends Span[IO] {
  override def put(fields: (String, TraceValue)*): IO[Unit] = recorded.update(_ ++ fields)
  override def log(fields: (String, TraceValue)*): IO[Unit] = IO.unit
  override def log(event: String): IO[Unit] = IO.unit
  override def attachError(err: Throwable, fields: (String, TraceValue)*): IO[Unit] = IO.unit
  override def kernel: IO[Kernel] = IO.pure(Kernel(Map.empty))
  override def span(name: String, options: Span.Options): Resource[IO, Span[IO]] = Resource.pure(this)
  override def traceId: IO[Option[String]] = IO.pure(None)
  override def spanId: IO[Option[String]] = IO.pure(None)
  override def traceUri: IO[Option[URI]] = IO.pure(None)
}
