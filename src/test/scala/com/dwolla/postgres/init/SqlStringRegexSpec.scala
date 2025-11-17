package com.dwolla.postgres.init

import cats.kernel.laws.discipline.SemigroupTests
import cats.syntax.all.*
import eu.timepit.refined.refineV
import munit.DisciplineSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalacheck.Arbitrary.arbitrary

class SqlStringRegexSpec extends DisciplineSuite {
  given Shrink[String] = Shrink.shrinkAny

  test("strings containing semicolons don't validate") {
    assert(refineV[GeneratedPasswordPredicate](";").isLeft)
  }

  test("strings containing apostrophes don't validate") {
    assert(refineV[GeneratedPasswordPredicate]("'").isLeft)
  }


  {
    given Arbitrary[SqlIdentifierTail] = Arbitrary:
      Gen.stringOf(Gen.oneOf(Gen.alphaChar, Gen.numChar, Gen.const('_')))
        .flatMap:
          SqlIdentifierTail.from(_).fold(msg => throw new IllegalArgumentException(msg), Gen.const)

    given Arbitrary[String] = Arbitrary:
      for
        initial <- Gen.alphaChar
        tail <- arbitrary[SqlIdentifierTail]
      yield s"$initial$tail"

    given Arbitrary[SqlIdentifier] = Arbitrary:
      arbitrary[String].flatMap:
        SqlIdentifier.from(_).fold(msg => throw new IllegalArgumentException(msg), Gen.const)

    property("sql identifiers match [A-Za-z][A-Za-z0-9_]*") {
      forAll { (s: String) =>
        assertEquals(refineV[SqlIdentifierPredicate](s).map(_.value), s.asRight)
      }
    }

    property("SqlIdentifierTails can be appended to SqlIdentifiers"):
      forAll: (base: SqlIdentifier, tail: SqlIdentifierTail) =>
        assertEquals(SqlIdentifier.from(base.value |+| tail.value), base.append(tail).asRight)

    checkAll("Semigroup[SqlIdentifier]", SemigroupTests[SqlIdentifier].semigroup)
  }

  property("passwords contain the allowed characters") {
    given Arbitrary[String] = Arbitrary {
      val allowedPunctuation: Gen[Char] = Gen.oneOf("""! " # $ % & ( ) * + , - . / : < = > ? @ [ \ ] ^ _ { | } ~ """.replaceAll(" ", "").toList)
      val allowedCharacters: Gen[Char] = Gen.oneOf(Gen.alphaChar, Gen.numChar, allowedPunctuation)

      for {
        initial <- allowedCharacters
        tail <- Gen.stringOf(allowedCharacters)
      } yield s"$initial$tail"
    }

    forAll { (s: String) =>
      assertEquals(refineV[GeneratedPasswordPredicate](s).map(_.value), s.asRight)
    }
  }
}
