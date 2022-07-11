package com.dwolla.postgres

import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string._
import monix.newtypes.NewtypeWrapped
import monix.newtypes.integrations.DerivedCirceCodec
import shapeless.ops.hlist
import shapeless.ops.tuple._
import shapeless.syntax.std.tuple._
import shapeless.{HList, LabelledGeneric}

package object init {
  implicit class ApplyAll[P <: Product](p: P) {
    def applyAll[A, B, O](a: A)
                         (implicit
                          cm: ConstMapper.Aux[P, A, B],
                          za: ZipApply.Aux[P, B, O],
                         ): O =
      p.zipApply(p.mapConst(a))
  }

  implicit class MigrationOps[A](a: A) {
    def migrateTo[B](implicit migration: Migration[A, B]): B =
      migration.apply(a)
  }

  implicit def genericMigration[A, B, ARepr <: HList, BRepr <: HList](implicit
                                                                      aGen: LabelledGeneric.Aux[A, ARepr],
                                                                      bGen: LabelledGeneric.Aux[B, BRepr],
                                                                      inter: hlist.Intersection.Aux[ARepr, BRepr, BRepr]
                                                                     ): Migration[A, B] =
    a => bGen.from(inter.apply(aGen.to(a)))

  type SqlIdentifierPredicate = MatchesRegex[W.`"[A-Za-z][A-Za-z0-9_]*"`.T]
  type SqlIdentifier = String Refined SqlIdentifierPredicate
  type GeneratedPasswordPredicate = MatchesRegex[W.`"""[-A-Za-z0-9!"#$%&()*+,./:<=>?@\\[\\]\\\\^_{|}~]+"""`.T]
  type GeneratedPassword = String Refined GeneratedPasswordPredicate

  type MasterDatabaseUsername = MasterDatabaseUsername.Type
  object MasterDatabaseUsername extends NewtypeWrapped[SqlIdentifier] with DerivedCirceCodec

  type MasterDatabasePassword = MasterDatabasePassword.Type
  object MasterDatabasePassword extends NewtypeWrapped[String] with DerivedCirceCodec

  type SecretId = SecretId.Type
  object SecretId extends NewtypeWrapped[String] with DerivedCirceCodec

  type Host = Host.Type
  object Host extends NewtypeWrapped[String] with DerivedCirceCodec

  type Port = Port.Type
  object Port extends NewtypeWrapped[Int] with DerivedCirceCodec

  type Username = Username.Type
  object Username extends NewtypeWrapped[SqlIdentifier] with DerivedCirceCodec

  type Password = Password.Type
  object Password extends NewtypeWrapped[GeneratedPassword] with DerivedCirceCodec

  type Database = Database.Type
  object Database extends NewtypeWrapped[SqlIdentifier] with DerivedCirceCodec

  type RoleName = RoleName.Type
  object RoleName extends NewtypeWrapped[SqlIdentifier] with DerivedCirceCodec
}

package init {
  trait Migration[A, B] {
    def apply(a: A): B
  }
}
