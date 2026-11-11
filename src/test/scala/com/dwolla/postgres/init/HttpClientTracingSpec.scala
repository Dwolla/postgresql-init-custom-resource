package com.dwolla.postgres.init

import cats.effect.*
import munit.CatsEffectSuite
import natchez.{Trace, TraceValue}
import org.http4s.*
import org.http4s.client.Client
import org.http4s.syntax.all.*

@annotation.experimental
class HttpClientTracingSpec extends CatsEffectSuite {
  private val signatureMarker = "made-up-signature-marker"

  test("the HTTP client's span records the request URI without its query string, and the request is sent with it") {
    val requestUri = uri"https://made-up.example/made-up-response-path".withQueryParam("X-Amz-Signature", signatureMarker)

    for {
      recorded <- Ref[IO].of(List.empty[(String, TraceValue)])
      trace <- Trace.ioTrace(new RecordingSpan(recorded))
      received <- Deferred[IO, Uri]
      client = {
        given Trace[IO] = trace
        PostgresqlDatabaseInitHandler.decorate(Client.fromHttpApp(HttpApp[IO](req => received.complete(req.uri).as(Response[IO](Status.Ok)))))
      }
      status <- client.status(Request[IO](Method.PUT, requestUri))
      sentUri <- received.get
      fields <- recorded.get
    } yield {
      assertEquals(status, Status.Ok)
      assertEquals(sentUri, requestUri)
      assertEquals(
        fields.collect { case ("client.http.uri", value) => value },
        List[TraceValue](TraceValue.StringValue("https://made-up.example/made-up-response-path"))
      )
      assertEquals(fields.filter(_._2.toString.contains(signatureMarker)), List.empty)
    }
  }
}
