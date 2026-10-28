package com.dwolla.postgres.init
package repositories

import eu.timepit.refined.refineV

class DatabaseQueriesSpec extends munit.FunSuite {

  test("createExtension quotes the extension name and cascades to its dependencies") {
    val extension = ExtensionName(refineV[ExtensionNamePredicate]("uuid-ossp").toOption.get)

    assertEquals(
      DatabaseQueries.createExtension(extension).sql,
      """CREATE EXTENSION IF NOT EXISTS "uuid-ossp" CASCADE"""
    )
  }

}
