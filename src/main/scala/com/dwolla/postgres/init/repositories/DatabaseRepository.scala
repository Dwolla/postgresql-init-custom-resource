package com.dwolla.postgres.init
package repositories

import cats.data.*
import cats.effect.{Trace as _, *}
import cats.syntax.all.*
import cats.tagless.Derive
import cats.tagless.aop.Aspect
import com.dwolla.postgres.init.repositories.CreateSkunkSession.*
import com.dwolla.tracing.syntax.*
import natchez.{Trace, TraceableValue}
import org.typelevel.log4cats.Logger
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

trait DatabaseRepository[F[_]] {
  def createDatabase(db: DatabaseMetadata): F[Database]
  def removeDatabase(database: Database): F[Database]
  def grantAllPrivilegesOnAllTablesInSchemaPublicToPgadmin: F[Unit]
}

@annotation.experimental
object DatabaseRepository {
  given Aspect[DatabaseRepository, TraceableValue, TraceableValue] = {
    import com.dwolla.tracing.LowPriorityTraceableValueInstances.unitTraceableValue
    Derive.aspect
  }

  val pgadminRole = RoleName(sqlIdentifier"pgadmin")

  def apply[F[_] : {MonadCancelThrow, Logger, Trace}]: DatabaseRepository[InSession[F, *]] = new DatabaseRepository[InSession[F, *]] {
    override def createDatabase(db: DatabaseMetadata): Kleisli[F, Session[F], Database] =
      checkDatabaseExists(db)
        .ifM(createDatabase(db.name, db.username), Logger[F].mapK(Kleisli.liftK[F, Session[F]]).info(s"No-op: database ${db.name} already exists"))
        .as(db.name)

    private def checkDatabaseExists(db: DatabaseMetadata): Kleisli[F, Session[F], Boolean] = Kleisli {
      _
        .unique(DatabaseQueries.checkDatabaseExists)(db.name)
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

    override def grantAllPrivilegesOnAllTablesInSchemaPublicToPgadmin: InSession[F, Unit] = Kleisli { (session: Session[F]) =>
      List(
        DatabaseQueries.grantAllPrivilegesOnAllTablesInSchemaPublicTo(_),
        DatabaseQueries.alterDefaultPrivilegesToGrantAllPrivilegesOnAllTablesInSchemaPublicTo(_),
      ).traverse { (f: RoleName => Command[Void]) =>
        session.execute(f(pgadminRole))
      }
    }.void
  }.traceWithInputsAndOutputs
}

object DatabaseQueries {
  private val narrowDatabase: Database => String = a => a.value.value

  val checkDatabaseExists: Query[Database, Long] =
    sql"SELECT count(*) as count FROM pg_catalog.pg_database WHERE pg_database.datname = $bpchar"
      .query(int8)
      .contramap(narrowDatabase)

  def createDatabase(database: Database, owner: MasterDatabaseUsername): Command[Void] =
    sql"CREATE DATABASE #${database.value.value} OWNER #${owner.value.value}"
      .command

  def dropDatabase(database: Database): Command[Void] =
    sql"DROP DATABASE IF EXISTS #${database.value.value}"
      .command

  def grantAllPrivilegesOnAllTablesInSchemaPublicTo(role: RoleName): Command[Void] =
    sql"GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO #${role.value.value}".command

  def alterDefaultPrivilegesToGrantAllPrivilegesOnAllTablesInSchemaPublicTo(role: RoleName): Command[Void] =
    sql"ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO #${role.value.value}".command
}
