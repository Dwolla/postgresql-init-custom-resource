package com.dwolla.postgres.init

import cats.MonadThrow
import cats.syntax.all.*
import skunk.SqlState
import skunk.exception.{PostgresErrorException, SkunkException}

import scala.util.control.NoStackTrace

/**
 * The failure CloudFormation receives as the resource's failure reason: the step that failed and
 * the error. A database error is reported by its SQLSTATE and the SQLSTATE's name, and another
 * database driver error or a JSON parsing or decoding error by its class, because their messages
 * carry the statement text and its arguments, or the JSON they reject. Every other error, AWS SDK
 * and network errors included, is reported with its message.
 */
case class StepFailed(step: String, error: String)
  extends RuntimeException(s"$step failed: $error")
    with NoStackTrace

object StepFailed {
  def describe(cause: Throwable): String = cause match
    case ex: PostgresErrorException =>
      SqlState.values.find(_.code == ex.code).fold(s"SQLSTATE ${ex.code}")(state => s"SQLSTATE ${ex.code} ($state)")
    case ex: SkunkException => ex.getClass.getSimpleName
    case ex: io.circe.Error => ex.getClass.getSimpleName
    case ex: io.circe.Errors => ex.getClass.getSimpleName
    case ex => Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)

  extension [F[_], A](fa: F[A])
    /**
     * Reports a failure of `fa` as a failure of `step`. A failure that already names its step
     * keeps it, so the innermost step is the one reported.
     */
    def failsAsStep(step: => String)(using MonadThrow[F]): F[A] =
      fa.adaptError {
        case alreadyReported: StepFailed => alreadyReported
        case cause => StepFailed(step, describe(cause))
      }
}
