package com.dwolla.postgres.init

import cats.MonadThrow
import cats.syntax.all.*
import natchez.{Trace, TraceValue}
import org.typelevel.log4cats.Logger
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
    case ex: PostgresErrorException => s"SQLSTATE ${sqlState(ex)}"
    case ex: SkunkException => ex.getClass.getSimpleName
    case ex: io.circe.Error => ex.getClass.getSimpleName
    case ex: io.circe.Errors => ex.getClass.getSimpleName
    case ex => Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)

  private def sqlState(ex: PostgresErrorException): String =
    SqlState.values.find(_.code == ex.code).fold(ex.code)(state => s"${ex.code} ($state)")

  /** One of Postgres's own error fields, with its label in the log line and its span key. */
  private case class PostgresField(logLabel: String, spanKey: String, value: String)

  /**
   * Postgres's own message, detail and hint for a database error. These never include the
   * statement or its arguments, unlike the exception's message. The message goes under
   * `error.postgres.message` because natchez's own error attachment writes `error.message` on
   * the same span when `StepFailed` leaves it.
   */
  private def postgresMessages(cause: Throwable): List[PostgresField] = cause match
    case ex: PostgresErrorException =>
      PostgresField("message", "error.postgres.message", ex.message) ::
        ex.detail.map(PostgresField("detail", "error.detail", _)).toList :::
        ex.hint.map(PostgresField("hint", "error.hint", _)).toList
    case _ => Nil

  private def reportedAs[F[_] : {MonadThrow, Logger, Trace}, A](fa: F[A],
                                                               step: => String,
                                                               messages: Throwable => List[PostgresField],
                                                              ): F[A] =
    fa.onError {
      case _: StepFailed => ().pure[F]
      case cause =>
        val described = cause match
          case ex: PostgresErrorException => List("error.sqlstate" -> sqlState(ex))
          case _ => List("error.message" -> describe(cause))
        val details = messages(cause)

        Logger[F].warn(s"$step failed: ${describe(cause)}${details.map(field => s"; ${field.logLabel}: ${field.value}").mkString}") >>
          Trace[F].put(
            (("error.step" -> step) :: described ::: details.map(field => field.spanKey -> field.value))
              .map((key, value) => key -> TraceValue.StringValue(value))*
          )
    }.adaptError {
      case alreadyReported: StepFailed => alreadyReported
      case cause => StepFailed(step, describe(cause))
    }

  extension [F[_], A](fa: F[A])
    /**
     * Reports a failure of `fa` as a failure of `step`. A failure that already names its step
     * keeps it, so the innermost step is the one reported, logged and traced. Before the error is
     * replaced, the step, the SQLSTATE, and Postgres's message, detail and hint are logged and put
     * on the current span; the statement and its arguments are not.
     */
    def failsAsStep(step: => String)(using MonadThrow[F], Logger[F], Trace[F]): F[A] =
      reportedAs(fa, step, postgresMessages)

    /**
     * Like `failsAsStep`, for a step whose statement carries credential material, such as a
     * password verifier. Only the step and the SQLSTATE are logged and traced: Postgres's
     * message, detail and hint can quote tokens from the statement, as a syntax error does.
     * This keeps the statement's credential material out of logs only: the database driver
     * records the statement text on its own span, so traces carry it by design.
     */
    def failsAsCredentialStep(step: => String)(using MonadThrow[F], Logger[F], Trace[F]): F[A] =
      reportedAs(fa, step, _ => Nil)
}
