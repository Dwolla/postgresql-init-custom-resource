package com.dwolla.postgres.init
package repositories

import cats.MonadThrow
import cats.data.*
import cats.effect.*
import cats.effect.std.Console
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.Network
import natchez.Trace
import skunk.*
import skunk.util.Typer

import scala.concurrent.duration.Duration

@FunctionalInterface
trait CreateSkunkSession[F[_]] {
  def single(host: String,
             port: Int = 5432,
             user: String,
             database: String,
             password: Option[String] = none,
             debug: Boolean = false,
             strategy: Typer.Strategy = Typer.Strategy.BuiltinsOnly,
             ssl: SSL = SSL.None,
             parameters: Map[String, String] = Session.DefaultConnectionParameters,
             commandCache: Int = 1024,
             queryCache: Int = 1024,
             parseCache: Int = 1024,
             readTimeout: Duration = Duration.Inf,
            ): Resource[F, Session[F]]
}

object CreateSkunkSession {
  type InSession[F[_], A] = Kleisli[F, Session[F], A]

  extension [F[_], A](kleisli: Kleisli[F, Session[F], A])
    def inSession(host: Host,
                  port: Port,
                  username: MasterDatabaseUsername,
                  password: MasterDatabasePassword,
                 )
                 (using CreateSkunkSession[F], MonadCancelThrow[F]): F[A] =
      CreateSkunkSession[F].single(
        host = host.show,
        port = port.value,
        user = username.value.value,
        database = "postgres",
        password = password.value.some,
        ssl = if (host == host"localhost") SSL.None else SSL.System,
      ).use(kleisli.run)

  extension [F[_], A](fa: F[A])
    def recoverUndefinedAs(a: A)
                          (using MonadThrow[F]): F[A] =
      fa.recover:
        case SqlState.UndefinedObject(_) => a
        case SqlState.InvalidCatalogName(_) => a

  def apply[F[_] : CreateSkunkSession]: CreateSkunkSession[F] = implicitly

  given [F[_] : {Temporal, Trace, Network, Console}]: CreateSkunkSession[F] = Session.single
}
