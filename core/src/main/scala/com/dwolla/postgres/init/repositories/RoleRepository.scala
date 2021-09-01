package com.dwolla.postgres.init
package repositories

import cats.data._
import cats.effect._
import cats.syntax.all._
import com.dwolla.postgres.init.repositories.CreateSkunkSession._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import org.typelevel.log4cats.Logger
import skunk._
import skunk.codec.all._
import skunk.implicits._

trait RoleRepository[F[_]] {
  def createRole(database: Database): F[Unit]
  def removeRole(database: Database): F[Unit]
  def addUserToRole(username: Username, database: Database): F[Unit]
  def removeUserFromRole(username: Username, database: Database): F[Unit]
}

object RoleRepository {
  def roleNameForDatabase(database: Database): RoleName =
    RoleName(Refined.unsafeApply(database.value + "_role"))

  def apply[F[_] : MonadCancelThrow : Logger]: RoleRepository[Kleisli[F, Session[F], *]] = new RoleRepository[Kleisli[F, Session[F], *]] {
    override def createRole(database: Database): Kleisli[F, Session[F], Unit] = {
      val role = roleNameForDatabase(database)

      checkRoleExists(role)
        .ifM(createEmptyRole(role) >> grantPrivileges(role, database), Logger[F].mapK(Kleisli.liftK[F, Session[F]]).info(s"No-op: role $role already exists"))
    }

    private def checkRoleExists(role: RoleName): Kleisli[F, Session[F], Boolean] = Kleisli {
      _
        .prepare(RoleQueries.countRoleByName)
        .use(_.unique(role))
        .flatTap { count =>
          Logger[F].info(s"found $count roles named $role")
        }
        .map(_ == 0)
    }

    private def createEmptyRole(role: RoleName): Kleisli[F, Session[F], Unit] = Kleisli {
      _
        .execute(RoleQueries.createRole(role))
        .flatTap { completion =>
          Logger[F].info(s"created role $role with status $completion")
        }
        .void
    }

    private def grantPrivileges(role: RoleName, database: Database): Kleisli[F, Session[F], Unit] = Kleisli {
      _
        .execute(RoleQueries.grantPrivilegesToRole(database, role))
        .flatTap { completion =>
          Logger[F].info(s"granted privileges to $role on $database with status $completion")
        }
        .void
    }

    private def revokePrivileges(role: RoleName, database: Database): Kleisli[F, Session[F], Unit] = Kleisli {
      _
        .execute(RoleQueries.revokePrivilegesFromRole(database, role))
        .flatTap { completion =>
          Logger[F].info(s"revoked privileges from $role on $database with status $completion")
        }
        .void
        .recoverUndefinedAs(())
    }

    override def removeRole(database: Database): Kleisli[F, Session[F], Unit] = {
      val roleName = roleNameForDatabase(database)

      revokePrivileges(roleName, database) >>
        Kleisli {
          _
            .execute(RoleQueries.dropRole(roleName))
            .flatTap { completion =>
              Logger[F].info(s"dropped role $roleName with status $completion")
            }
            .void
            .recoverUndefinedAs(())
        }
    }

    override def addUserToRole(username: Username, database: Database): Kleisli[F, Session[F], Unit] = Kleisli {
      _
        .execute(RoleQueries.grantRole(username, roleNameForDatabase(database)))
        .flatTap { completion =>
          Logger[F].info(s"added $username to role ${roleNameForDatabase(database)} with status $completion")
        }
        .void
    }

    override def removeUserFromRole(username: Username, database: Database): ReaderT[F, Session[F], Unit] = Kleisli {
      _
        .execute(RoleQueries.revokeRole(username, roleNameForDatabase(database)))
        .flatTap { completion =>
          Logger[F].info(s"revoked role ${roleNameForDatabase(database)} from $username with status $completion")
        }
        .void
        .recoverUndefinedAs(())
    }
  }
}

object RoleQueries {
  private val narrowRoleName: RoleName => String = _.value

  def grantRole(userName: Username,
                role: RoleName): Command[Void] =
    sql"GRANT #${userName.value} TO #${role.value}"
      .command

  def revokeRole(userName: Username,
                 role: RoleName): Command[Void] =
    sql"REVOKE #${userName.value} FROM #${role.value}"
      .command

  val countRoleByName: Query[RoleName, Long] =
    sql"SELECT count(*) as count FROM pg_catalog.pg_roles WHERE rolname = $bpchar"
      .query(int8)
      .contramap(narrowRoleName)

  def createRole(role: RoleName): Command[Void] =
    sql"CREATE ROLE #${role.value}"
      .command

  def grantPrivilegesToRole(database: Database, role: RoleName): Command[Void] =
    sql"GRANT ALL PRIVILEGES ON DATABASE #${database.value} TO #${role.value}"
      .command

  def revokePrivilegesFromRole(database: Database, role: RoleName): Command[Void] =
    sql"REVOKE ALL PRIVILEGES ON DATABASE #${database.value} FROM #${role.value}"
      .command

  def dropRole(role: RoleName): Command[Void] =
    sql"DROP ROLE IF EXISTS #${role.value}"
      .command

}
