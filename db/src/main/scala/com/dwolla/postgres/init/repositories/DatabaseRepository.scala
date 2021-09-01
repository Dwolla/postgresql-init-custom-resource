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

trait DatabaseRepository[F[_]] {
  def createDatabase(db: DatabaseMetadata): F[Database]
  def removeDatabase(database: Database): F[Database]
}

object DatabaseRepository {
  def apply[F[_] : MonadCancelThrow : Logger]: DatabaseRepository[InSession[F, *]] = new DatabaseRepository[InSession[F, *]] {
    override def createDatabase(db: DatabaseMetadata): Kleisli[F, Session[F], Database] =
      checkDatabaseExists(db)
        .ifM(createDatabase(db.name, db.username), Logger[F].mapK(Kleisli.liftK[F, Session[F]]).info(s"No-op: database ${db.name} already exists"))
        .as(db.name)

    private def checkDatabaseExists(db: DatabaseMetadata): Kleisli[F, Session[F], Boolean] = Kleisli {
      _
        .prepare(DatabaseQueries.checkDatabaseExists)
        .use(_.unique(db.name))
        .flatTap { count =>
          Logger[F].info(s"Found $count databases matching ${db.name} on ${db.username}@${db.host}:${db.port}")
        }
        .map(_ == 0)
    }

    private def createDatabase(database: Database, owner: MasterDatabaseUsername): Kleisli[F, Session[F], Unit] = Kleisli {
      _
        .execute(DatabaseQueries.createDatabase(database, owner))
        .flatTap { completion =>
          Logger[F].info(s"created database $database with status $completion")
        }
        .void
    }

    override def removeDatabase(database: Database): Kleisli[F, Session[F], Database] = Kleisli {
      _
        .execute(DatabaseQueries.dropDatabase(database))
        .flatTap { completion =>
          Logger[F].info(s"dropped database $database with status $completion")
        }
        .as(database)
        .recoverUndefinedAs(database)
    }
  }
}

object DatabaseQueries {
  private val narrowDatabase: Database => String = a => a.value

  val checkDatabaseExists: Query[Database, Long] =
    sql"SELECT count(*) as count FROM pg_catalog.pg_database WHERE pg_database.datname = $bpchar"
      .query(int8)
      .contramap(narrowDatabase)

  def createDatabase(database: Database, owner: MasterDatabaseUsername): Command[Void] =
    sql"CREATE DATABASE #${database.value} OWNER #${owner.value}"
      .command

  def dropDatabase(database: Database): Command[Void] =
    sql"DROP DATABASE IF EXISTS #${database.value}"
      .command
}
