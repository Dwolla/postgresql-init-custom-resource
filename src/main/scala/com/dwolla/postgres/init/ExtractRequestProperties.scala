package com.dwolla.postgres.init

import cats.syntax.all.*
import com.amazonaws.secretsmanager.SecretIdType
import com.dwolla.tracing.LowPriorityTraceableValueInstances.nonPrimitiveTraceValueViaJson
import io.circe.generic.semiauto.deriveDecoder
import io.circe.refined.*
import io.circe.{Decoder, HCursor, Json}
import io.circe.literal.*
import io.circe.syntax.EncoderOps
import natchez.*

case class DatabaseMetadata(host: Host,
                            port: Port,
                            name: Database,
                            username: MasterDatabaseUsername,
                            password: MasterDatabasePassword,
                            secretIds: List[SecretIdType],
                           )

object DatabaseMetadata {
  implicit val DecodeDatabaseMetadata: Decoder[DatabaseMetadata] = Decoder.accumulatingInstance { (c: HCursor) =>
    (c.downField("Host").asAcc[Host],
      c.downField("Port").asAcc[Port],
      c.downField("DatabaseName").asAcc[Database],
      c.downField("MasterDatabaseUsername").asAcc[MasterDatabaseUsername],
      c.downField("MasterDatabasePassword").asAcc[MasterDatabasePassword],
      c.downField("UserConnectionSecrets").asAcc[List[String]].nested.map(SecretIdType(_)).value,
    ).mapN(DatabaseMetadata.apply)
  }

  implicit val traceableValue: TraceableValue[DatabaseMetadata] = TraceableValue[Json].contramap { dm =>
    implicit def toTraceableValueOps[A](a: A): TraceableValueOps[A] = new TraceableValueOps[A](a)

    json"""{
          "Host": ${dm.host.toTraceValue},
          "Port": ${dm.port.toTraceValue},
          "DatabaseName": ${dm.name.toTraceValue},
          "MasterDatabaseUsername": ${dm.username.toTraceValue},
          "MasterDatabasePassword": ${dm.password.toTraceValue},
          "UserConnectionSecrets": ${dm.secretIds.map(_.toTraceValue)}
        }"""
  }
}

case class UserConnectionInfo(database: Database,
                              host: Host,
                              port: Port,
                              user: Username,
                              password: Password,
                             )

object UserConnectionInfo {
  implicit val UserConnectionInfoDecoder: Decoder[UserConnectionInfo] = deriveDecoder[UserConnectionInfo]

  implicit val traceableValue: TraceableValue[UserConnectionInfo] = TraceableValue[Json].contramap { uci =>
    implicit def toTraceableValueOps[A](a: A): TraceableValueOps[A] = new TraceableValueOps[A](a)

    json"""{
          "host": ${uci.host.toTraceValue},
          "port": ${uci.port.toTraceValue},
          "database": ${uci.database.toTraceValue},
          "user": ${uci.user.toTraceValue},
          "password": ${uci.password.toTraceValue}
        }"""
  }
}

private class TraceableValueOps[A](val a: A) extends AnyVal {
  def toTraceValue(implicit T: TraceableValue[A]): Json = T.toTraceValue(a) match {
    case TraceValue.StringValue(value) => value.asJson
    case TraceValue.BooleanValue(value) => value.asJson
    case TraceValue.NumberValue(value) =>
      value match {
        case i: java.lang.Byte    => Json.fromInt(i.intValue)
        case s: java.lang.Short   => Json.fromInt(s.intValue)
        case i: java.lang.Integer => Json.fromInt(i)
        case l: java.lang.Long    => Json.fromLong(l)
        case f: java.lang.Float   => Json.fromFloat(f).getOrElse(Json.Null)
        case d: java.lang.Double  => Json.fromDouble(d).getOrElse(Json.Null)
        case bd: java.math.BigDecimal =>
          Json.fromBigDecimal(scala.math.BigDecimal(bd))
        case bi: java.math.BigInteger =>
          Json.fromBigInt(scala.math.BigInt(bi))
        case _ =>
          // Fallback: try BigDecimal to preserve value if possible
          val bd = new java.math.BigDecimal(value.toString)
          Json.fromBigDecimal(scala.math.BigDecimal(bd))
      }
  }
}
