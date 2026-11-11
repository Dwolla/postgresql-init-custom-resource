package com.dwolla.postgres.init

import cats.effect.*
import cats.effect.std.SecureRandom
import cats.syntax.all.*
import eu.timepit.refined.refineV
import munit.CatsEffectSuite
import scodec.bits.ByteVector

class ScramSha256VerifierSpec extends CatsEffectSuite {
  private val password = Password(refineV[GeneratedPasswordPredicate]("made-up-password-marker").fold(e => throw new IllegalArgumentException(e), identity))

  test("the verifier matches one computed independently for the same password, salt and iteration count") {
    // expected value computed outside this code base with Python's hashlib.pbkdf2_hmac, hmac and base64
    assertEquals(
      ScramSha256Verifier.fromPassword(password, ByteVector.fromValidBase64("AAECAwQFBgcICQoLDA0ODw==")).value,
      "SCRAM-SHA-256$4096:AAECAwQFBgcICQoLDA0ODw==$Dr/jAo4GThT/8xX2Wo9uHDf+5A9qlqLnAwHe6Ci2Qq4=:H+nqkj9URr7YYgSRg/f4hwJz6NNHH4IMrEDIqgXEjyI="
    )
  }

  test("each generated verifier has its own 16-byte salt and 4096 iterations") {
    SecureRandom.javaSecuritySecureRandom[IO].flatMap { implicit random =>
      (ScramSha256Verifier.generate[IO](password), ScramSha256Verifier.generate[IO](password)).tupled
    }.map { case (first, second) =>
      val salts = List(first, second).map(_.value.split('$')(1))
      assert(salts.forall(_.startsWith("4096:")), salts)
      assertEquals(salts.map(s => ByteVector.fromValidBase64(s.stripPrefix("4096:")).size), List(16L, 16L))
      assertNotEquals(salts(0), salts(1))
    }
  }
}
