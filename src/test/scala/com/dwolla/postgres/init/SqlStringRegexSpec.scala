package com.dwolla.postgres.init

import cats.syntax.all.*
import eu.timepit.refined.refineV
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Gen, Shrink}

class SqlStringRegexSpec extends munit.ScalaCheckSuite {
  given Shrink[String] = Shrink.shrinkAny

  test("strings containing semicolons don't validate") {
    assert(refineV[GeneratedPasswordPredicate](";").isLeft)
  }

  test("strings containing apostrophes don't validate") {
    assert(refineV[GeneratedPasswordPredicate]("'").isLeft)
  }

  property("sql identifiers match [A-Za-z][A-Za-z0-9_]*") {
    given Arbitrary[String] = Arbitrary {
      for {
        initial <- Gen.alphaChar
        tail <- Gen.stringOf(Gen.oneOf(Gen.alphaChar, Gen.numChar, Gen.const('_')))
      } yield s"$initial$tail"
    }

    forAll { (s: String) =>
      assertEquals(refineV[SqlIdentifierPredicate](s).map(_.value), s.asRight)
    }
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
