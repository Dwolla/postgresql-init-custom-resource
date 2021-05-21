package com.dwolla.postgres.init

import cats.data.{EitherNel, NonEmptyList}
import cats.syntax.all._
import com.dwolla.postgres.init.InvalidInputsException.printChainedCauses
import io.circe.{Decoder, JsonObject}


object FindProperty {
  def apply[A: Decoder](key: String): JsonObject => EitherNel[InvalidInputException, A] =
    _.apply(key)
      .toRight(s"Missing $key in input")
      .leftMap(InvalidInputException(_))
      .flatMap {
        _.as[A]
          .leftMap(ex => InvalidInputException(key, ex.some))
      }
      .toEitherNel
}

case class InvalidInputException(field: String, cause: Option[Throwable] = None)
  extends RuntimeException(s"Invalid input: $field", cause.orNull)

object InvalidInputsException {
  private def printChainedCauses(inputs: NonEmptyList[InvalidInputException]): String =
    inputs.toList.filter(_.cause.isDefined) match {
      case Nil => "None"
      case head :: tail => NonEmptyList(head, tail).map(_.cause.fold("None")(_.toString)).mkString_("\n  - ")
    }
}

case class InvalidInputsException(inputs: NonEmptyList[InvalidInputException])
  extends RuntimeException(
    s"""Invalid inputs:
       |
       |  - ${inputs.map(_.field).mkString_("\n  - ")}
       |
       |Chained causes:
       |  - ${printChainedCauses(inputs)}
       |""".stripMargin)
