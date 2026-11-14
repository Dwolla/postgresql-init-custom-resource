package com.dwolla.postgres.init

import cats.effect.*
import fs2.concurrent.Signal
import fs2.Stream
import skunk.*
import skunk.data.*
import skunk.net.protocol.{Describe, Parse}
import skunk.util.Typer

/**
 * A skunk `Session` that runs no SQL, for tests: queries with arguments find no row, and
 * each command is handed to `onExecute`. Anything else the repositories do not use fails.
 */
class InMemorySession(onExecute: Command[Void] => IO[Completion]) extends Session.Impl[IO] {
  override def option[A, B](query: Query[A, B])(args: A): IO[Option[B]] = IO.pure(None)
  override def execute(command: Command[Void]): IO[Completion] = onExecute(command)

  private def unsupported[A]: A = throw new UnsupportedOperationException("not used by these tests")

  override def parameters: Signal[IO, Map[String, String]] = unsupported
  override def parameter(key: String): Stream[IO, String] = unsupported
  override def transactionStatus: Signal[IO, TransactionStatus] = unsupported
  override def execute[A](query: Query[Void, A]): IO[List[A]] = unsupported
  override def unique[A](query: Query[Void, A]): IO[A] = unsupported
  override def option[A](query: Query[Void, A]): IO[Option[A]] = unsupported
  override def prepare[A, B](query: Query[A, B]): IO[PreparedQuery[IO, A, B]] = unsupported
  override def prepare[A](command: Command[A]): IO[PreparedCommand[IO, A]] = unsupported
  override def channel(name: Identifier): Channel[IO, String, String] = unsupported
  override def transaction[A]: Resource[IO, Transaction[IO]] = unsupported
  override def transaction[A](isolationLevel: TransactionIsolationLevel, accessMode: TransactionAccessMode): Resource[IO, Transaction[IO]] = unsupported
  override def typer: Typer = unsupported
  override def describeCache: Describe.Cache[IO] = unsupported
  override def parseCache: Parse.Cache[IO] = unsupported
}

object InMemorySession {
  /** A `CreateSkunkSession` whose sessions are all `session`, so no connection is made. */
  def creating(session: Session[IO]): repositories.CreateSkunkSession[IO] =
    (_, _, _, _, _, _, _, _, _, _, _, _, _) => Resource.pure(session)
}
