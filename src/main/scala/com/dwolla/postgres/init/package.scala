package com.dwolla.postgres.init

import cats.*
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.*
import eu.timepit.refined.api.*
import eu.timepit.refined.string.*
import io.circe.Decoder
import monix.newtypes.integrations.DerivedCirceCodec
import monix.newtypes.{HasExtractor, NewtypeWrapped}
import natchez.TraceableValue
import org.typelevel.literally.Literally

given [A: TraceableValue, P]: TraceableValue[A Refined P] = TraceableValue[A].contramap(_.value)

type SqlIdentifierPredicate = MatchesRegex["[A-Za-z][A-Za-z0-9_]*"]

type SqlIdentifier = String Refined SqlIdentifierPredicate
object SqlIdentifier extends RefinedTypeOps[SqlIdentifier, String]
given Semigroup[SqlIdentifier] = Semigroup.instance((a, b) => SqlIdentifier.unsafeFrom(a.value + b.value))
given Eq[SqlIdentifier] = Eq.by(_.value)

type SqlIdentifierTailPredicate = MatchesRegex["[A-Za-z0-9_]*"]
type SqlIdentifierTail = String Refined SqlIdentifierTailPredicate
object SqlIdentifierTail extends RefinedTypeOps[SqlIdentifierTail, String]
extension (base: SqlIdentifier)
  def append(tail: SqlIdentifierTail): SqlIdentifier =
    SqlIdentifier.unsafeFrom(base.value + tail.value)

extension (inline ctx: StringContext)
  inline def sqlIdentifier(inline args: Any*): SqlIdentifier =
    ${ SqlIdentifierLiteral('ctx, 'args) }
  inline def sqlIdentifierTail(inline args: Any*): SqlIdentifierTail =
    ${ SqlIdentifierTailLiteral('ctx, 'args) }

object SqlIdentifierLiteral extends Literally[SqlIdentifier]:
  def validate(s: String)(using Quotes): Either[String, Expr[SqlIdentifier]] =
    SqlIdentifier.from(s).as:
      '{ SqlIdentifier.from(${ Expr(s) }).toOption.get }

object SqlIdentifierTailLiteral extends Literally[SqlIdentifierTail]:
  def validate(s: String)(using Quotes): Either[String, Expr[SqlIdentifierTail]] =
    SqlIdentifierTail.from(s).as:
      '{ SqlIdentifierTail.from(${ Expr(s) }).toOption.get }

type GeneratedPasswordPredicate = MatchesRegex["""[-A-Za-z0-9!"#$%&()*+,./:<=>?@\[\]\\^_{|}~]+"""]

type MasterDatabaseUsername = MasterDatabaseUsername.Type
object MasterDatabaseUsername extends NewtypeWrapped[SqlIdentifier] with DerivedCirceCodec with DerivedTraceableValueFromNewtype

type MasterDatabasePassword = MasterDatabasePassword.Type
object MasterDatabasePassword extends NewtypeWrapped[String] with DerivedCirceCodec {
  given TraceableValue[MasterDatabasePassword] = TraceableValue[String].contramap(_ => "redacted password")
}

private[init] given Decoder[Host] =
  Decoder[String].emap(s => Host.fromString(s).toRight(s"$s could not be decoded as a Host"))
private[init] given TraceableValue[Host] = TraceableValue[String].contramap(_.show)

private[init] given Decoder[Port] =
  Decoder[Int].emap(i => Port.fromInt(i).toRight(s"$i could not be decoded as a Port"))
private[init] given TraceableValue[Port] = TraceableValue[Int].contramap(_.value)

type Username = Username.Type
object Username extends NewtypeWrapped[SqlIdentifier] with DerivedCirceCodec with DerivedTraceableValueFromNewtype

type Password = Password.Type
object Password extends NewtypeWrapped[String Refined GeneratedPasswordPredicate] with DerivedCirceCodec {
  given TraceableValue[Password] = TraceableValue[String].contramap(_ => "redacted password")
}

type Database = Database.Type
object Database extends NewtypeWrapped[SqlIdentifier] with DerivedCirceCodec with DerivedTraceableValueFromNewtype

type RoleName = RoleName.Type
object RoleName extends NewtypeWrapped[SqlIdentifier] with DerivedCirceCodec with DerivedTraceableValueFromNewtype

trait DerivedTraceableValueFromNewtype {
  given [T, S](using HasExtractor.Aux[T, S], TraceableValue[S]): TraceableValue[T] =
    TraceableValue[S].contramap(summon[HasExtractor.Aux[T, S]].extract)
}
