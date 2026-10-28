package com.dwolla.postgres.init

import com.amazonaws.secretsmanager.SecretIdType
import com.comcast.ip4s.*
import io.circe.{CursorOp, Decoder}
import io.circe.literal.*

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
        Database(sqlIdentifier"mydb"),
        MasterDatabaseUsername(sqlIdentifier"masterdb"),
        MasterDatabasePassword("master-pass"),
        List("secret1", "secret2").map(SecretIdType(_)),
      ))
    )

  }

  test("ExtractRequestProperties can decode Extensions") {

    val input =
      json"""{
               "DatabaseName": "mydb",
               "Host": "database-hostname",
               "Port": "5432",
               "MasterDatabaseUsername": "masterdb",
               "MasterDatabasePassword": "master-pass",
               "UserConnectionSecrets": [
                 "secret1"
               ],
               "Extensions": [
                 "vector",
                 "uuid-ossp"
               ]
             }"""

    assertEquals(
      Decoder[DatabaseMetadata].decodeJson(input).map(_.extensions.map(_.value.value)),
      Right(List("vector", "uuid-ossp"))
    )

  }

  test("ExtractRequestProperties rejects invalid extension names") {

    val input =
      json"""{
               "DatabaseName": "mydb",
               "Host": "database-hostname",
               "Port": "5432",
               "MasterDatabaseUsername": "masterdb",
               "MasterDatabasePassword": "master-pass",
               "UserConnectionSecrets": [],
               "Extensions": [
                 "vector\"; DROP DATABASE mydb; --"
               ]
             }"""

    val failure = Decoder[DatabaseMetadata].decodeJson(input)
      .fold(identity, metadata => fail(s"expected decoding to fail, but got $metadata"))

    assert(failure.history.contains(CursorOp.DownField("Extensions")), failure.history)

  }

}
