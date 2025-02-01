/*
 * Copyright 2025 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sttp.client3.otel4s.trace

import cats.effect.IO
import cats.effect.testkit.TestControl
import munit.CatsEffectSuite
import org.typelevel.otel4s.{Attribute, Attributes}
import org.typelevel.otel4s.sdk.testkit.trace.TracesTestkit
import org.typelevel.otel4s.sdk.trace.context.propagation.W3CTraceContextPropagator
import org.typelevel.otel4s.sdk.trace.data.{EventData, LimitedData, StatusData}
import org.typelevel.otel4s.trace.{StatusCode, TracerProvider}
import sttp.client3._
import sttp.client3.impl.cats.CatsMonadAsyncError
import sttp.client3.testing.SttpBackendStub
import sttp.model.{StatusCode => HttpStatusCode}

import scala.concurrent.duration.Duration
import scala.util.control.NoStackTrace

class TracingBackendSuite extends CatsEffectSuite {

  test("add tracing headers to the request") {
    TracesTestkit
      .inMemory[IO](_.addTextMapPropagators(W3CTraceContextPropagator.default))
      .use { testkit =>
        implicit val tracerProvider: TracerProvider[IO] = testkit.tracerProvider

        def stub = SttpBackendStub(new CatsMonadAsyncError[IO])
          .whenRequestMatchesPartial { r =>
            assert(r.header("traceparent").isDefined)
            Response.ok("")
          }

        val makeBackend = TracingBackend[IO, Any](
          stub,
          SpanNameSelector.default(UriTemplateClassifier.none),
          SpanAttributes.default,
        )

        for {
          backend <- makeBackend
          response <- backend.send(basicRequest.get(uri"success"))
        } yield assertEquals(response.code, HttpStatusCode.Ok)
      }
  }

  test("record request/response-specific attributes: 200 OK response") {
    TracesTestkit
      .inMemory[IO](_.addTextMapPropagators(W3CTraceContextPropagator.default))
      .use { testkit =>
        implicit val tracerProvider: TracerProvider[IO] = testkit.tracerProvider

        def stub = SttpBackendStub(new CatsMonadAsyncError[IO]).whenRequestMatchesPartial {
          case r if r.uri.toString.contains("success") =>
            assert(r.header("traceparent").isDefined)
            Response.ok("")
        }

        val makeBackend = TracingBackend[IO, Any](
          stub,
          SpanNameSelector.default(UriTemplateClassifier.none),
          SpanAttributes.default,
        )

        for {
          backend <- makeBackend
          response <- backend.send(
            basicRequest.get(uri"http://user:pwd@localhost:8080/success?q=v")
          )
          spans <- testkit.finishedSpans
        } yield {
          val status = StatusData(StatusCode.Unset)

          val attributes = Attributes(
            Attribute("http.request.method", "GET"),
            Attribute("http.response.status_code", 200L),
            Attribute("http.request.header.accept-encoding", Seq("gzip, deflate")),
            Attribute("server.address", "localhost"),
            Attribute("server.port", 8080L),
            Attribute("url.full", "http://REDACTED:REDACTED@localhost:8080/success?q=v"),
            Attribute("url.scheme", "http"),
          )

          assertEquals(response.code, HttpStatusCode.Ok)

          assertEquals(spans.map(_.attributes.elements), List(attributes))
          assertEquals(spans.map(_.events.elements), List(Vector.empty))
          assertEquals(spans.map(_.status), List(status))
        }
      }
  }

  test("record request/response-specific attributes: 400 BadRequest response") {
    TracesTestkit
      .inMemory[IO](_.addTextMapPropagators(W3CTraceContextPropagator.default))
      .use { testkit =>
        implicit val tracerProvider: TracerProvider[IO] = testkit.tracerProvider

        def stub = SttpBackendStub(new CatsMonadAsyncError[IO]).whenRequestMatchesPartial {
          case r if r.uri.toString.contains("bad-request") =>
            assert(r.header("traceparent").isDefined)
            Response("", HttpStatusCode.BadRequest)
        }

        val makeBackend = TracingBackend[IO, Any](
          stub,
          SpanNameSelector.default(UriTemplateClassifier.none),
          SpanAttributes.default,
        )

        for {
          backend <- makeBackend
          response <- backend.send(
            basicRequest.get(uri"http://user@localhost:8080/bad-request?q=v")
          )
          spans <- testkit.finishedSpans
        } yield {
          val status = StatusData(StatusCode.Error)

          val attributes = Attributes(
            Attribute("http.request.method", "GET"),
            Attribute("http.response.status_code", 400L),
            Attribute("http.request.header.accept-encoding", Seq("gzip, deflate")),
            Attribute("server.address", "localhost"),
            Attribute("server.port", 8080L),
            Attribute("url.full", "http://REDACTED@localhost:8080/bad-request?q=v"),
            Attribute("url.scheme", "http"),
            Attribute("error.type", "400"),
          )

          assertEquals(response.code, HttpStatusCode.BadRequest)

          assertEquals(spans.map(_.attributes.elements), List(attributes))
          assertEquals(spans.map(_.events.elements), List(Vector.empty))
          assertEquals(spans.map(_.status), List(status))
        }
      }
  }

  test("record request/response-specific attributes: runtime error") {
    TestControl.executeEmbed {
      TracesTestkit
        .inMemory[IO](_.addTextMapPropagators(W3CTraceContextPropagator.default))
        .use { testkit =>
          implicit val tracerProvider: TracerProvider[IO] = testkit.tracerProvider

          object Err extends RuntimeException("Something went wrong") with NoStackTrace

          def stub = SttpBackendStub(new CatsMonadAsyncError[IO]).whenRequestMatchesPartial {
            case r if r.uri.toString.contains("error") =>
              assert(r.header("traceparent").isDefined)
              throw Err
          }

          val makeBackend = TracingBackend[IO, Any](
            stub,
            SpanNameSelector.default(UriTemplateClassifier.none),
            SpanAttributes.default,
          )

          for {
            backend <- makeBackend
            response <- backend
              .send(
                basicRequest.get(uri"http://localhost/error")
              )
              .attempt
            spans <- testkit.finishedSpans
          } yield {
            val status = StatusData(StatusCode.Error)

            val attributes = Attributes(
              Attribute("http.request.method", "GET"),
              Attribute("http.request.header.accept-encoding", Seq("gzip, deflate")),
              Attribute("server.address", "localhost"),
              Attribute("url.full", "http://localhost/error"),
              Attribute("url.scheme", "http"),
              Attribute("error.type", Err.getClass.getName),
            )

            val event = EventData.fromException(
              Duration.Zero,
              Err,
              LimitedData.attributes(128, 128),
              escaped = false
            )

            assertEquals(response, Left(Err))

            assertEquals(spans.map(_.attributes.elements), List(attributes))
            assertEquals(spans.map(_.events.elements), List(Vector(event)))
            assertEquals(spans.map(_.status), List(status))
          }
        }
    }
  }

}
