package com.dwolla.postgres.init

import cats.data.EitherNel
import cats.syntax.all._
import io.circe.generic.semiauto.deriveDecoder
import io.circe.refined._
import io.circe.{Decoder, JsonObject}

case class DatabaseMetadata(host: Host,
                            port: Port,
                            name: Database,
                            username: MasterDatabaseUsername,
                            password: MasterDatabasePassword,
                            secretIds: List[SecretId],
                           )

object DatabaseMetadata extends ((Host, Port, Database, MasterDatabaseUsername, MasterDatabasePassword, List[SecretId]) => DatabaseMetadata) {
  implicit def DatabaseMetadataDecoder: Decoder[DatabaseMetadata] = ???

  def apply(props: JsonObject): EitherNel[InvalidInputException, DatabaseMetadata] =
    (
      FindProperty[Host]("Host"),
      FindProperty[Port]("Port"),
      FindProperty[Database]("DatabaseName"),
      FindProperty[MasterDatabaseUsername]("MasterDatabaseUsername"),
      FindProperty[MasterDatabasePassword]("MasterDatabasePassword"),
      FindProperty[List[SecretId]]("UserConnectionSecrets"),
      )
      .applyAll(props)
      .parMapN(DatabaseMetadata(_, _, _, _, _, _))
}

//object ExtractRequestProperties {
//  def apply[F[_] : MonadThrow : Logger](req: CloudFormationCustomResourceRequest): F[DatabaseMetadata] =
//    req
//      .ResourceProperties
//      .toRightNel(InvalidInputException("Missing ResourceProperties"))
//      .flatMap(DatabaseMetadata(_))
//      .leftMap(InvalidInputsException(_))
//      .liftTo[F]
//      .flatTap {
//        case DatabaseMetadata(host, port, name, username, _, _) =>
//          Logger[F].info(s"Received request to create $name on $username@$host:$port")
//      }
//}

case class UserConnectionInfo(database: Database,
                              host: Host,
                              port: Port,
                              user: Username,
                              password: Password,
                             )

object UserConnectionInfo {
  implicit val UserConnectionInfoDecoder: Decoder[UserConnectionInfo] = deriveDecoder[UserConnectionInfo]
}
