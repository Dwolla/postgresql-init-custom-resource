package com.dwolla.postgres.init
package repositories

import cats.effect.*
import cats.effect.std.SecureRandom
import com.comcast.ip4s.*
import eu.timepit.refined.refineV
import munit.CatsEffectSuite
import natchez.Trace.Implicits.noop
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import scodec.bits.ByteVector
import skunk.data.Completion

@annotation.experimental
class UserRepositorySpec extends CatsEffectSuite {
  private given Logger[IO] = NoOpLogger[IO]

  private val passwordMarker = "made-up-password-marker"
  private val user = Username(sqlIdentifier"made_up_user")
  private val userConnectionInfo = UserConnectionInfo(
    database = Database(sqlIdentifier"made_up_db"),
    host = host"made-up-host.example",
    port = port"5432",
    user = user,
    password = Password(refineV[GeneratedPasswordPredicate](passwordMarker).fold(e => throw new IllegalArgumentException(e), identity)),
  )

  private val createUserWithVerifier =
    """CREATE USER made_up_user WITH PASSWORD 'SCRAM-SHA-256\$4096:[A-Za-z0-9+/]+=*\$[A-Za-z0-9+/]+=*:[A-Za-z0-9+/]+=*'""".r

  test("creating a user sets its password as a SCRAM-SHA-256 verifier, so the statement does not hold the password") {
    SecureRandom.javaSecuritySecureRandom[IO].flatMap { random =>
      given SecureRandom[IO] = random

      for {
        executed <- Ref[IO].of(List.empty[String])
        session = new InMemorySession(command => executed.update(_ :+ command.sql).as(Completion.CreateRole))
        _ <- UserRepository[IO].addOrUpdateUser(userConnectionInfo).run(session)
        statements <- executed.get
      } yield {
        assertEquals(statements.size, 1, statements)
        assert(createUserWithVerifier.matches(statements.head), statements)
        assert(!statements.head.contains(passwordMarker), statements)
      }
    }
  }

  test("updating a user sets its password as the verifier it is given") {
    val verifier = ScramSha256Verifier.fromPassword(userConnectionInfo.password, ByteVector.fromValidBase64("AAECAwQFBgcICQoLDA0ODw=="))

    assertEquals(
      UserQueries.updateUser(user, verifier).sql,
      s"ALTER USER made_up_user WITH PASSWORD '${verifier.value}'"
    )
  }
}
