package com.dwolla.postgres.init
package repositories

import cats.data._
import cats.effect._
import cats.syntax.all._
import com.dwolla.postgres.init.repositories.CreateSkunkSession._
import eu.timepit.refined.api.Refined
import org.typelevel.log4cats._
import skunk._
import skunk.codec.all._
import skunk.implicits._

import scala.concurrent.duration._

trait UserRepository[F[_]] {
  def addOrUpdateUser(userConnectionInfo: UserConnectionInfo): F[Username]
  def removeUser(username: Username): F[Username]
}

object UserRepository {
  def usernameForDatabase(database: Database): Username =
    Username(Refined.unsafeApply(database.value))

  def apply[F[_] : Logger : Temporal]: UserRepository[Kleisli[F, Session[F], *]] = new UserRepository[Kleisli[F, Session[F], *]] {
    private implicit def kleisliLogger[A]: Logger[Kleisli[F, A, *]] = Logger[F].mapK(Kleisli.liftK)

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

    private def removeUser(user: Username, retries: Int): Kleisli[F, Session[F], Username] =
      Kleisli[F, Session[F], Username] {
        _
          .execute(UserQueries.removeUser(user))
          .flatTap { completion =>
            Logger[F].info(s"removed user $user with status $completion")
          }
          .as(user)
          .recoverUndefinedAs(user)
      }
        .recoverWith {
          case SqlState.DependentObjectsStillExist(ex) if retries > 0 =>
            for {
              _ <- Logger[Kleisli[F, Session[F], *]].warn(ex)(s"Failed when removing $user")
              _ <- Temporal[Kleisli[F, Session[F], *]].sleep(5.seconds)
              user <- removeUser(user, retries - 1)
            } yield user
          case SqlState.DependentObjectsStillExist(ex) if retries == 0 =>
            Kleisli.liftF(DependentObjectsStillExistButRetriesAreExhausted(user.value, ex).raiseError[F, Username])
        }

    override def removeUser(username: Username): Kleisli[F, Session[F], Username] =
      removeUser(username, 5)
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
    sql"DROP USER IF EXISTS #${username.value}"
      .command
}
