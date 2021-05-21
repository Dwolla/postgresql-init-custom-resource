package com.dwolla.postgres.init

import cats._
import cats.syntax.all._
import com.dwolla.lambda.cloudformation.CloudFormationCustomResourceRequest
import org.typelevel.log4cats.Logger
import io.circe.refined._

case class DatabaseMetadata(host: Host,
                            port: Port,
                            name: Database,
                            username: MasterDatabaseUsername,
                            password: MasterDatabasePassword,
                           )

object ExtractRequestProperties {
  def apply[F[_] : MonadThrow : Logger](req: CloudFormationCustomResourceRequest): F[DatabaseMetadata] =
    req
      .ResourceProperties
      .toRightNel(InvalidInputException("Missing ResourceProperties"))
      .flatMap { props =>
        (
          FindProperty[Host]("Host"),
          FindProperty[Port]("Port"),
          FindProperty[Database]("DatabaseName"),
          FindProperty[MasterDatabaseUsername]("MasterDatabaseUsername"),
          FindProperty[MasterDatabasePassword]("MasterDatabasePassword"),
          )
          .applyAll(props)
          .parMapN(DatabaseMetadata)
      }
      .leftMap(InvalidInputsException(_))
      .liftTo[F]
      .flatTap {
        case DatabaseMetadata(host, port, name, username, _) =>
          Logger[F].info(s"Received request to create $name on $username@$host:$port")
      }
}
