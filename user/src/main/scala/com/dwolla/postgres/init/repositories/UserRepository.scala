package com.dwolla.postgres.init
package repositories

import cats.data._
import cats.effect._
import cats.syntax.all._
import com.dwolla.postgres.init.repositories.CreateSkunkSession._
import org.typelevel.log4cats.Logger
import skunk._
import skunk.codec.all._
import skunk.implicits._

trait UserRepository[F[_]] {
  def addOrUpdateUser(userConnectionInfo: UserConnectionInfo): F[Username]
  def removeUser(userConnectionInfo: UserConnectionInfo): F[Username]
}

object UserRepository {
  def apply[F[_] : BracketThrow : Logger]: UserRepository[Kleisli[F, Session[F], *]] = new UserRepository[Kleisli[F, Session[F], *]] {
    override def addOrUpdateUser(userConnectionInfo: UserConnectionInfo): Kleisli[F, Session[F], Username] =
      for {
        upsert <- determineCommandFor(userConnectionInfo.user, userConnectionInfo.password)
        _ <- applyUsernameAndPassword(userConnectionInfo.user)(upsert)
      } yield userConnectionInfo.user

    private def determineCommandFor(username: Username,
                                    password: Password): Kleisli[F, Session[F], Command[Void]] = Kleisli {
      _
        .prepare(UserQueries.checkUserExists)
        .use(_.unique(username))
        .flatMap {
          case 0 => Logger[F].info(s"Creating user $username") >> UserQueries.createUser(username, password).pure[F]
          case count => Logger[F].info(s"Found and updating $count user named $username") >> UserQueries.updateUser(username, password).pure[F]
        }
    }

    private def applyUsernameAndPassword(username: Username)
                                        (upsertUser: Command[Void]): Kleisli[F, Session[F], Unit] = Kleisli {
      _
        .execute(upsertUser)
        .flatTap { completion =>
          Logger[F].info(s"upserted $username with status $completion")
        }
        .void
    }

    override def removeUser(userConnectionInfo: UserConnectionInfo): Kleisli[F, Session[F], Username] =
      Kleisli {
        _
          .execute(UserQueries.removeUser(userConnectionInfo.user))
          .flatTap { completion =>
            Logger[F].info(s"removed user ${userConnectionInfo.user} with status $completion")
          }
          .as(userConnectionInfo.user)
          .recoverUndefinedAs(userConnectionInfo.user)
      }
  }
}

object UserQueries {
  private val narrowUsername: Username => String = a => a.value

  val checkUserExists: Query[Username, Long] =
    sql"SELECT count(*) as count FROM pg_catalog.pg_user u WHERE u.usename = $bpchar"
      .query(int8)
      .contramap(narrowUsername)

  def createUser(username: Username,
                 password: Password): Command[Void] =
    sql"CREATE USER #${username.value} WITH PASSWORD '#${password.value}'"
      .command

  def updateUser(username: Username,
                 password: Password): Command[Void] =
    sql"ALTER USER #${username.value} WITH PASSWORD '#${password.value}'"
      .command

  def removeUser(username: Username): Command[Void] =
    sql"DROP USER #${username.value}"
      .command
}
