package sttp.client3.otel4s.metrics

import java.util.concurrent.TimeUnit

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.foldable._
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.{
  BucketBoundaries,
  Histogram,
  Meter,
  MeterProvider,
  UpDownCounter
}
import org.typelevel.otel4s.semconv.attributes.{
  ErrorAttributes,
  HttpAttributes,
  NetworkAttributes,
  ServerAttributes,
  UrlAttributes
}
import sttp.client3.listener.{ListenerBackend, RequestListener}
import sttp.client3.{FollowRedirectsBackend, HttpError, Request, Response, SttpBackend}
import sttp.model.{HttpVersion, StatusCode}

import scala.concurrent.duration.FiniteDuration
import scala.util.chaining._

object MetricsBackend {

  val DefaultDurationBuckets: BucketBoundaries = BucketBoundaries(
    0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10
  )

  def apply[F[_]: Monad: Clock: MeterProvider, P](
      delegate: SttpBackend[F, P],
      requestDurationHistogramBuckets: BucketBoundaries = DefaultDurationBuckets,
      requestBodySizeHistogramBuckets: Option[BucketBoundaries] = None,
      responseBodySizeHistogramBuckets: Option[BucketBoundaries] = None,
  ): F[SttpBackend[F, P]] =
    MeterProvider[F].meter("sttp.client3").withVersion("0.0.1").get.flatMap { implicit tracer =>
      usingMeter(
        delegate,
        requestDurationHistogramBuckets,
        requestBodySizeHistogramBuckets,
        responseBodySizeHistogramBuckets
      )
    }

  def usingMeter[F[_]: Monad: Clock: Meter, P](
      delegate: SttpBackend[F, P],
      requestDurationHistogramBuckets: BucketBoundaries = DefaultDurationBuckets,
      requestBodySizeHistogramBuckets: Option[BucketBoundaries] = None,
      responseBodySizeHistogramBuckets: Option[BucketBoundaries] = None,
  ): F[SttpBackend[F, P]] =
    for {
      requestDuration <- Meter[F]
        .histogram[Double]("http.client.request.duration")
        .withExplicitBucketBoundaries(requestDurationHistogramBuckets)
        .withDescription("Duration of HTTP client requests.")
        .withUnit("s")
        .create

      requestBodySize <- Meter[F]
        .histogram[Long]("http.client.request.body.size")
        .pipe(b => requestBodySizeHistogramBuckets.fold(b)(b.withExplicitBucketBoundaries))
        .withDescription("Size of HTTP client request bodies.")
        .withUnit("By")
        .create

      responseBodySize <- Meter[F]
        .histogram[Long]("http.client.response.body.size")
        .pipe(b => responseBodySizeHistogramBuckets.fold(b)(b.withExplicitBucketBoundaries))
        .withDescription("Size of HTTP client response bodies.")
        .withUnit("By")
        .create

      activeRequests <- Meter[F]
        .upDownCounter[Long]("http.client.active_requests")
        .withDescription("Number of active HTTP requests.")
        .withUnit("{request}")
        .create
      // redirects should be handled before metrics
    } yield new FollowRedirectsBackend[F, P](
      new ListenerBackend[F, P, State](
        delegate,
        new MetricsRequestListener[F](
          requestDuration,
          requestBodySize,
          responseBodySize,
          activeRequests
        )
      )
    )

  private final case class State(start: FiniteDuration, activeRequestsAttributes: Attributes)

  private final class MetricsRequestListener[F[_]: Monad: Clock](
      requestDuration: Histogram[F, Double],
      requestBodySize: Histogram[F, Long],
      responseBodySize: Histogram[F, Long],
      activeRequests: UpDownCounter[F, Long]
  ) extends RequestListener[F, State] {
    def beforeRequest(request: Request[_, _]): F[State] =
      for {
        start <- Clock[F].realTime
        attributes <- Monad[F].pure(activeRequestAttributes(request))
        _ <- activeRequests.inc(attributes)
      } yield State(start, attributes)

    def requestException(request: Request[_, _], state: State, e: Exception): F[Unit] =
      HttpError.find(e) match {
        case Some(HttpError(body, statusCode)) =>
          requestSuccessful(
            request,
            Response(body, statusCode).copy(request = request.onlyMetadata),
            state
          )

        case _ =>
          for {
            now <- Clock[F].realTime
            attributes <- Monad[F].pure(fullAttributes(request, None, Some(e.getClass.getName)))
            _ <- requestDuration.record((now - state.start).toUnit(TimeUnit.SECONDS), attributes)
            _ <- request.contentLength.traverse_(size => requestBodySize.record(size, attributes))
            _ <- activeRequests.dec(state.activeRequestsAttributes)
          } yield ()
      }

    def requestSuccessful(request: Request[_, _], response: Response[_], state: State): F[Unit] =
      for {
        now <- Clock[F].realTime
        attributes <- Monad[F].pure(fullAttributes(request, response))
        _ <- requestDuration.record((now - state.start).toUnit(TimeUnit.SECONDS), attributes)
        _ <- request.contentLength.traverse_(length => requestBodySize.record(length, attributes))
        _ <- response.contentLength.traverse_(length => responseBodySize.record(length, attributes))
        _ <- activeRequests.dec(state.activeRequestsAttributes)
      } yield ()

    private def activeRequestAttributes(request: Request[_, _]): Attributes = {
      val b = Attributes.newBuilder

      b += HttpAttributes.HttpRequestMethod(request.method.method)
      b ++= ServerAttributes.ServerAddress.maybe(request.uri.host)
      b ++= ServerAttributes.ServerPort.maybe(request.uri.port.map(_.toLong))
      b ++= UrlAttributes.UrlScheme.maybe(request.uri.scheme)

      b.result()
    }

    private def fullAttributes(request: Request[_, _], response: Response[_]): Attributes =
      fullAttributes(
        request,
        Some(response.code),
        Option.unless(response.isSuccess)(response.code.toString())
      )

    private def fullAttributes(
        request: Request[_, _],
        responseStatusCode: Option[StatusCode],
        errorType: Option[String]
    ): Attributes = {
      val b = Attributes.newBuilder

      b += HttpAttributes.HttpRequestMethod(request.method.method)
      b ++= ServerAttributes.ServerAddress.maybe(request.uri.host)
      b ++= ServerAttributes.ServerPort.maybe(request.uri.port.map(_.toLong))
      b ++= NetworkAttributes.NetworkProtocolVersion.maybe(request.httpVersion.map(networkProtocol))
      b ++= UrlAttributes.UrlScheme.maybe(request.uri.scheme)

      // response
      b ++= HttpAttributes.HttpResponseStatusCode.maybe(responseStatusCode.map(_.code.toLong))
      b ++= ErrorAttributes.ErrorType.maybe(errorType)

      b.result()
    }

    private def networkProtocol(httpVersion: HttpVersion): String =
      httpVersion match {
        case HttpVersion.HTTP_1   => "1.0"
        case HttpVersion.HTTP_1_1 => "1.1"
        case HttpVersion.HTTP_2   => "2"
        case HttpVersion.HTTP_3   => "3"
      }
  }

}
