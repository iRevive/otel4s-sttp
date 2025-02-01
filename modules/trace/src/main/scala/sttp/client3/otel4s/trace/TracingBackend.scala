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

import cats.effect.MonadCancelThrow
import cats.effect.Outcome
import cats.effect.syntax.monadCancel._
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import org.typelevel.otel4s.semconv.attributes.ErrorAttributes
import org.typelevel.otel4s.trace.{SpanKind, StatusCode, Tracer, TracerProvider}
import sttp.capabilities
import sttp.client3.{DelegateSttpBackend, FollowRedirectsBackend, Request, Response, SttpBackend}

private class TracingBackend[F[_]: MonadCancelThrow: Tracer, P](
    spanNameSelector: SpanNameSelector,
    spanAttributes: SpanAttributes,
    delegate: SttpBackend[F, P]
) extends DelegateSttpBackend[F, P](delegate) {

  def send[T, R >: P with capabilities.Effect[F]](request: Request[T, R]): F[Response[T]] =
    MonadCancelThrow[F].uncancelable { poll =>
      Tracer[F]
        .spanBuilder(spanNameSelector.name(request))
        .withSpanKind(SpanKind.Client)
        .addAttributes(spanAttributes.requestAttributes(request))
        .build
        .use { span =>
          for {
            headers <- Tracer[F].propagate(Map.empty[String, String])
            newReq = request.headers(headers, replaceExisting = false)
            response <- poll(delegate.send(newReq)).guaranteeCase {
              case Outcome.Succeeded(fa) =>
                fa.flatMap { resp =>
                  for {
                    _ <- span.addAttributes(spanAttributes.responseAttributes(resp))
                    _ <- span.setStatus(StatusCode.Error).unlessA(resp.code.isSuccess)
                  } yield ()
                }

              case Outcome.Errored(e) =>
                span.addAttributes(ErrorAttributes.ErrorType(e.getClass.getName))

              case Outcome.Canceled() =>
                MonadCancelThrow[F].unit
            }
          } yield response
        }
    }
}

object TracingBackend {

  def apply[F[_]: MonadCancelThrow: TracerProvider, P](
      delegate: SttpBackend[F, P],
      spanNameSelector: SpanNameSelector = SpanNameSelector.default(UriTemplateClassifier.none),
      spanAttributes: SpanAttributes = SpanAttributes.default,
  ): F[SttpBackend[F, P]] =
    TracerProvider[F].tracer("sttp.client3").withVersion(BuildInfo.version).get.map { implicit tracer =>
      usingTracer(delegate, spanNameSelector, spanAttributes)
    }

  def usingTracer[F[_]: MonadCancelThrow: Tracer, P](
      delegate: SttpBackend[F, P],
      spanNameSelector: SpanNameSelector = SpanNameSelector.default(UriTemplateClassifier.none),
      spanAttributes: SpanAttributes = SpanAttributes.default,
  ): SttpBackend[F, P] =
    if (Tracer[F].meta.isEnabled) {
      new FollowRedirectsBackend(
        new TracingBackend[F, P](spanNameSelector, spanAttributes, delegate)
      )
    } else {
      delegate
    }

}
