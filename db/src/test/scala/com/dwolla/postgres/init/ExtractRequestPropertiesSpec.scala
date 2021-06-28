package com.dwolla.postgres.init

import io.circe.JsonObject
import io.circe.literal._
import eu.timepit.refined.auto._

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
      input.as[JsonObject].flatMap(DatabaseMetadata(_)),
      Right(DatabaseMetadata(
        Host("database-hostname"),
        Port(5432),
        Database("mydb"),
        MasterDatabaseUsername("masterdb"),
        MasterDatabasePassword("master-pass"),
        List("secret1", "secret2").map(SecretId(_)),
      ))
    )

  }

}
