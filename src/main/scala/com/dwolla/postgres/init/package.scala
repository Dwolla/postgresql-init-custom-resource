package com.dwolla.postgres

import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.*
import eu.timepit.refined.api.*
import eu.timepit.refined.string.*
import io.circe.Decoder
import monix.newtypes.NewtypeWrapped
import monix.newtypes.integrations.DerivedCirceCodec
import natchez.TraceableValue

package object init {
  implicit def refinedTraceableValue[A: TraceableValue, P]: TraceableValue[A Refined P] = TraceableValue[A].contramap(_.value)

  type SqlIdentifierPredicate = MatchesRegex["[A-Za-z][A-Za-z0-9_]*"]

  type SqlIdentifier = String Refined SqlIdentifierPredicate
  object SqlIdentifier extends RefinedTypeOps[SqlIdentifier, String]

  type GeneratedPasswordPredicate = MatchesRegex["""[-A-Za-z0-9!"#$%&()*+,./:<=>?@\[\]\\^_{|}~]+"""]

  type MasterDatabaseUsername = MasterDatabaseUsername.Type
  object MasterDatabaseUsername extends NewtypeWrapped[SqlIdentifier] with DerivedCirceCodec with DerivedTraceableValueFromNewtype

  type MasterDatabasePassword = MasterDatabasePassword.Type
  object MasterDatabasePassword extends NewtypeWrapped[String] with DerivedCirceCodec {
    implicit val traceableValue: TraceableValue[MasterDatabasePassword] = TraceableValue[String].contramap(_ => "redacted password")
  }

  private[init] implicit val hostDecoder: Decoder[Host] =
    Decoder[String].emap(s => Host.fromString(s).toRight(s"$s could not be decoded as a Host"))
  private[init] implicit val hostTraceableValue: TraceableValue[Host] = TraceableValue[String].contramap(_.show)

  private[init] implicit val portDecoder: Decoder[Port] =
    Decoder[Int].emap(i => Port.fromInt(i).toRight(s"$i could not be decoded as a Port"))
  private[init] implicit val portTraceableValue: TraceableValue[Port] = TraceableValue[Int].contramap(_.value)

  type Username = Username.Type
  object Username extends NewtypeWrapped[SqlIdentifier] with DerivedCirceCodec with DerivedTraceableValueFromNewtype

  type Password = Password.Type
  object Password extends NewtypeWrapped[String Refined GeneratedPasswordPredicate] with DerivedCirceCodec {
    implicit val traceableValue: TraceableValue[Password] = TraceableValue[String].contramap(_ => "redacted password")
  }

  type Database = Database.Type
  object Database extends NewtypeWrapped[SqlIdentifier] with DerivedCirceCodec with DerivedTraceableValueFromNewtype

  type RoleName = RoleName.Type
  object RoleName extends NewtypeWrapped[SqlIdentifier] with DerivedCirceCodec with DerivedTraceableValueFromNewtype
}

package init {

  import monix.newtypes.HasExtractor

  trait Migration[A, B] {
    def apply(a: A): B
  }

  trait DerivedTraceableValueFromNewtype {
    implicit def traceableValue[T, S](implicit
                                      extractor: HasExtractor.Aux[T, S],
                                      enc: TraceableValue[S],
                                     ): TraceableValue[T] =
      enc.contramap(extractor.extract)
  }
}
