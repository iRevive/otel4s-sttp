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
      spanNameSelector: SpanNameSelector,
      spanAttributes: SpanAttributes,
      delegate: SttpBackend[F, P]
  ): F[SttpBackend[F, P]] =
    TracerProvider[F].tracer("sttp.client3").withVersion("0.0.1").get.map { implicit tracer =>
      usingTracer(spanNameSelector, spanAttributes, delegate)
    }

  def usingTracer[F[_]: MonadCancelThrow: Tracer, P](
      spanNameSelector: SpanNameSelector,
      spanAttributes: SpanAttributes,
      delegate: SttpBackend[F, P]
  ): SttpBackend[F, P] =
    if (Tracer[F].meta.isEnabled) {
      new FollowRedirectsBackend(new TracingBackend[F, P](spanNameSelector, spanAttributes, delegate))
    } else {
      delegate
    }

}
