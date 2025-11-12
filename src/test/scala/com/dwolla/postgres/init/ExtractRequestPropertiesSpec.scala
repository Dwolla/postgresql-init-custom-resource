package com.dwolla.postgres.init

import com.amazonaws.secretsmanager.SecretIdType
import com.comcast.ip4s.*
import io.circe.Decoder
import io.circe.literal.*
import eu.timepit.refined.auto.*

class ExtractRequestPropertiesSpec extends munit.FunSuite {

  test("ExtractRequestProperties can decode expected JSON") {

    val input =
      json"""{
               "DatabaseName": "mydb",
               "Host": "database-hostname",
               "Port": "5432",
               "MasterDatabaseUsername": "masterdb",
               "MasterDatabasePassword": "master-pass",
               "UserConnectionSecrets": [
                 "secret1",
                 "secret2"
               ]
             }"""

    assertEquals(
      Decoder[DatabaseMetadata].decodeJson(input),
      Right(DatabaseMetadata(
        host"database-hostname",
        port"5432",
        Database("mydb"),
        MasterDatabaseUsername("masterdb"),
        MasterDatabasePassword("master-pass"),
        List("secret1", "secret2").map(SecretIdType(_)),
      ))
    )

  }

}
