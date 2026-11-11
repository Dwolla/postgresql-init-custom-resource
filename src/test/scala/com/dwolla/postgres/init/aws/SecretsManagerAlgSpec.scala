package com.dwolla.postgres.init
package aws

import com.amazonaws.secretsmanager.SecretStringType
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

class SecretsManagerAlgSpec extends FunSuite {
  private val marker = "made-up-secret-marker"

  test("a secret that is not JSON fails with an error that holds none of its contents") {
    assertEquals(
      SecretsManagerAlg.decodeSecret[UserConnectionInfo](SecretString(SecretStringType(marker))).left.map(_.getMessage),
      Left("the secret is not JSON")
    )
  }

  test("a secret whose fields do not decode fails with an error naming the fields, without their values") {
    val secret = Json.obj(
      "database" -> "made_up_db".asJson,
      "host" -> "made-up-host.example".asJson,
      "port" -> 5432.asJson,
      "user" -> "made_up_user".asJson,
      "password" -> s"$marker'".asJson,
    )

    assertEquals(
      SecretsManagerAlg.decodeSecret[UserConnectionInfo](SecretString(SecretStringType(secret.noSpaces))).left.map(_.getMessage),
      Left("could not decode .password")
    )
  }
}
