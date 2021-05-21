package com.dwolla.postgres.init

import cats.effect._
import cats.syntax.all._
import com.dwolla.lambda.cloudformation.CloudFormationRequestType.{CreateRequest, DeleteRequest, OtherRequestType, UpdateRequest}
import com.dwolla.lambda.cloudformation.{CloudFormationCustomResourceRequest, HandlerResponse, PhysicalResourceId}
import com.dwolla.postgres.init.repositories.CreateSkunkSession
import com.dwolla.postgres.init.repositories.CreateSkunkSession.{InSession, InSessionOps}

abstract class CreateUpdateDeleteHandler[F[_] : BracketThrow : CreateSkunkSession, A] {
  private def illegalRequestType(other: String): InSession[F, PhysicalResourceId] =
    (new IllegalArgumentException(s"unexpected CloudFormation request type `$other``"): Throwable).raiseError[InSession[F, *], PhysicalResourceId]

  protected def extractRequestProperties(req: CloudFormationCustomResourceRequest): F[(A, DatabaseConnectionInfo)]

  protected def handleCreateOrUpdate(input: A): InSession[F, PhysicalResourceId]

  protected def handleDelete(input: A): InSession[F, PhysicalResourceId]

  def handleRequest(req: CloudFormationCustomResourceRequest): F[HandlerResponse] =
    for {
      (input, db) <- extractRequestProperties(req)
      id <- (req.RequestType match {
        case CreateRequest | UpdateRequest =>
          handleCreateOrUpdate(input)

        case DeleteRequest =>
          handleDelete(input)

        case OtherRequestType(other) =>
          illegalRequestType(other)
      }).inSession(db.host, db.port, db.username, db.password)
    } yield HandlerResponse(id)
}

case class DatabaseConnectionInfo(host: Host,
                            port: Port,
                            username: MasterDatabaseUsername,
                            password: MasterDatabasePassword,
                           )
