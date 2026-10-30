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

  {
    val extensionNameTail: Gen[Char] = Gen.oneOf(Gen.alphaLowerChar, Gen.numChar, Gen.const('_'), Gen.const('-'))

    property("extension names of up to 63 lowercase letters, digits, underscores, and hyphens are valid") {
      forAll(Gen.alphaLowerChar, Gen.choose(0, 62).flatMap(Gen.listOfN(_, extensionNameTail))) { (initial, tail) =>
        val s = (initial :: tail).mkString
        assertEquals(refineV[ExtensionNamePredicate](s).map(_.value), s.asRight)
      }
    }

    property("extension names containing any other character are invalid") {
      val allowed: Set[Char] = ('a' to 'z').toSet ++ ('0' to '9') + '_' + '-'
      val disallowed: Gen[Char] = Gen.frequency(
        9 -> Gen.oneOf((' ' to '~').filterNot(allowed)),
        1 -> arbitrary[Char].suchThat(c => !allowed(c)),
      )

      forAll(Gen.alphaLowerChar, Gen.listOf(extensionNameTail), disallowed, Gen.listOf(extensionNameTail)) { (initial, before, bad, after) =>
        assert(refineV[ExtensionNamePredicate]((initial :: before ::: bad :: after).mkString).isLeft)
      }
    }

    property("extension names that don't start with a lowercase letter are invalid") {
      forAll(Gen.oneOf(Gen.numChar, Gen.const('_'), Gen.const('-'), Gen.alphaUpperChar), Gen.listOf(extensionNameTail)) { (initial, tail) =>
        assert(refineV[ExtensionNamePredicate]((initial :: tail).mkString).isLeft)
      }
    }

    property("extension names longer than 63 characters are invalid") {
      forAll(Gen.alphaLowerChar, Gen.choose(63, 200).flatMap(Gen.listOfN(_, extensionNameTail))) { (initial, tail) =>
        assert(refineV[ExtensionNamePredicate]((initial :: tail).mkString).isLeft)
      }
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
