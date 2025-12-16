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
                 (using CreateSkunkSession[F], MonadCancelThrow[F], Trace[F]): F[A] =
      impl(host, port, username, password, none)

    def inSession(host: Host,
                  port: Port,
                  username: MasterDatabaseUsername,
                  password: MasterDatabasePassword,
                  database: Database,
                 )
                 (using CreateSkunkSession[F], MonadCancelThrow[F], Trace[F]): F[A] =
      impl(host, port, username, password, database.some)

    private def impl(host: Host,
                     port: Port,
                     username: MasterDatabaseUsername,
                     password: MasterDatabasePassword,
                     database: Option[Database],
                    )
                    (using CreateSkunkSession[F], MonadCancelThrow[F], Trace[F]): F[A] =
      Trace[F].span("database.session") {
        Trace[F].put("database" -> database.map(_.value).getOrElse(sqlIdentifier"postgres").value) >>
          CreateSkunkSession[F].single(
            host = host.show,
            port = port.value,
            user = username.value.value,
            database = database.map(_.value).getOrElse(sqlIdentifier"postgres").value,
            password = password.value.some,
            ssl = if (host == host"localhost") SSL.None else SSL.System,
          ).use(kleisli.run)
      }

  extension [F[_], A](fa: F[A])
    def recoverUndefinedAs(a: A)
                          (using MonadThrow[F]): F[A] =
      fa.recover:
        case SqlState.UndefinedObject(_) => a
        case SqlState.InvalidCatalogName(_) => a

  def apply[F[_] : CreateSkunkSession]: CreateSkunkSession[F] = implicitly

  given [F[_] : {Temporal, Trace, Network, Console}]: CreateSkunkSession[F] = Session.single
}
