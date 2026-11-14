package com.dwolla.postgres.init

import cats.Functor
import cats.effect.std.SecureRandom
import cats.syntax.all.*
import monix.newtypes.NewtypeWrapped
import scodec.bits.ByteVector

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import javax.crypto.spec.{PBEKeySpec, SecretKeySpec}
import javax.crypto.{Mac, SecretKeyFactory}

/**
 * A password in the form Postgres stores it for SCRAM-SHA-256 authentication (RFC 5802, RFC 7677):
 * `SCRAM-SHA-256$<iterations>:<salt>$<StoredKey>:<ServerKey>`. Postgres stores a password given
 * in this form as it is, so `CREATE USER` and `ALTER USER` can set a password without the
 * password itself appearing in the statement.
 */
type ScramSha256Verifier = ScramSha256Verifier.Type
object ScramSha256Verifier extends NewtypeWrapped[String] {
  /** Postgres's own defaults for new verifiers (`scram_iterations` and `SCRAM_DEFAULT_SALT_LEN`). */
  val Iterations: Int = 4096
  val SaltLength: Int = 16

  /**
   * The passwords this resource accepts are printable ASCII (`GeneratedPasswordPredicate`), which
   * SASLprep leaves unchanged, so the password's UTF-8 bytes are what Postgres clients hash.
   */
  def fromPassword(password: Password, salt: ByteVector): ScramSha256Verifier = {
    val saltedPassword = SecretKeyFactory
      .getInstance("PBKDF2WithHmacSHA256")
      .generateSecret(new PBEKeySpec(password.value.value.toCharArray, salt.toArray, Iterations, 256))
      .getEncoded
    val clientKey = hmacSha256(saltedPassword, "Client Key")
    val storedKey = ByteVector.view(MessageDigest.getInstance("SHA-256").digest(clientKey.toArray))
    val serverKey = hmacSha256(saltedPassword, "Server Key")

    ScramSha256Verifier(s"SCRAM-SHA-256$$$Iterations:${salt.toBase64}$$${storedKey.toBase64}:${serverKey.toBase64}")
  }

  def generate[F[_] : {SecureRandom, Functor}](password: Password): F[ScramSha256Verifier] =
    SecureRandom[F].nextBytes(SaltLength).map(salt => fromPassword(password, ByteVector.view(salt)))

  private def hmacSha256(key: Array[Byte], message: String): ByteVector = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    ByteVector.view(mac.doFinal(message.getBytes(UTF_8)))
  }
}
