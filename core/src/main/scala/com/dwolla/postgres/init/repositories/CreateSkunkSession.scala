package com.dwolla.postgres.init
package repositories

import cats.data._
import cats.effect._
import cats.syntax.all._
import natchez.Trace
import skunk._
import skunk.util.Typer

import scala.concurrent.duration._

trait CreateSkunkSession[F[_]] {
  def single(host: String,
             port: Int = 5432,
             user: String,
             database: String,
             password: Option[String] = none,
             debug: Boolean = false,
             readTimeout: FiniteDuration = Int.MaxValue.seconds,
             writeTimeout: FiniteDuration = 5.seconds,
             strategy: Typer.Strategy = Typer.Strategy.BuiltinsOnly,
             ssl: SSL = SSL.None,
             parameters: Map[String, String] = Session.DefaultConnectionParameters,
            ): Resource[F, Session[F]]
}

object CreateSkunkSession {
  type InSession[F[_], A] = Kleisli[F, Session[F], A]

  implicit class InSessionOps[F[_], A](val kleisli: Kleisli[F, Session[F], A]) extends AnyVal {
    def inSession(host: Host,
                  port: Port,
                  username: MasterDatabaseUsername,
                  password: MasterDatabasePassword,
                 )
                 (implicit
                  `🦨`: CreateSkunkSession[F],
                  `[]`: BracketThrow[F]): F[A] =
      CreateSkunkSession[F].single(
        host = host.value,
        port = port.value,
        user = username.value,
        database = "postgres",
        password = password.value.some,
        ssl = SSL.System,
      ).use(kleisli.run)
  }

  implicit class IgnoreErrorOps[F[_], A](val fa: F[A]) extends AnyVal {
    def recoverUndefinedAs(a: A)
                          (implicit `[]`: BracketThrow[F]): F[A] =
      fa.recover {
        case SqlState.UndefinedObject(_) => a
        case SqlState.InvalidCatalogName(_) => a
      }
  }

  def apply[F[_] : CreateSkunkSession]: CreateSkunkSession[F] = implicitly

  implicit def instance[F[_] : Concurrent : ContextShift : Trace]: CreateSkunkSession[F] =
    Session.single
}
