package sttp.client3.otel4s.metrics

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.otel4s.metrics.{BucketBoundaries, MeterProvider}
import org.typelevel.otel4s.sdk.metrics.data.MetricData
import org.typelevel.otel4s.sdk.testkit.metrics.MetricsTestkit
import org.typelevel.otel4s.semconv.{MetricSpec, Requirement}
import org.typelevel.otel4s.semconv.experimental.metrics.HttpExperimentalMetrics
import org.typelevel.otel4s.semconv.metrics.HttpMetrics
import sttp.client3._
import sttp.client3.impl.cats.CatsMonadAsyncError
import sttp.client3.testing.SttpBackendStub
import sttp.model.{Header, StatusCode}

class MetricsBackendSuite extends CatsEffectSuite {

  test("client semantic test") {
    val specs = List(
      HttpMetrics.ClientRequestDuration,
      HttpExperimentalMetrics.ClientRequestBodySize,
      HttpExperimentalMetrics.ClientResponseBodySize,
      HttpExperimentalMetrics.ClientActiveRequests,
    )

    MetricsTestkit.inMemory[IO]().use { testkit =>
      implicit val meterProvider: MeterProvider[IO] = testkit.meterProvider

      def stub = SttpBackendStub(new CatsMonadAsyncError[IO]).whenRequestMatchesPartial {
        case r if r.uri.toString.contains("success") =>
          val body = "body"
          Response(body, StatusCode.Ok, "OK", Seq(Header.contentLength(body.length.toLong)))
      }

      val makeBackend = MetricsBackend[IO, Any](
        delegate = stub,
        requestDurationHistogramBuckets = BucketBoundaries(
          Vector(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10)
        ),
        requestBodySizeHistogramBuckets = None,
        responseBodySizeHistogramBuckets = None
      )

      for {
        backend <- makeBackend
        _ <- backend.send(basicRequest.post(uri"http://localhost:8080/success").body("payload"))
        metrics <- testkit.collectMetrics
      } yield specs.foreach(spec => specTest(metrics, spec))
    }
  }

  private def specTest(metrics: List[MetricData], spec: MetricSpec): Unit = {
    val metric = metrics.find(_.name == spec.name)
    assert(
      metric.isDefined,
      s"${spec.name} metric is missing. Available [${metrics.map(_.name).mkString(", ")}]",
    )

    val clue = s"[${spec.name}] has a mismatched property"

    metric.foreach { md =>
      assertEquals(md.name, spec.name, clue)
      assertEquals(md.description, Some(spec.description), clue)
      assertEquals(md.unit, Some(spec.unit), clue)

      val required = spec.attributeSpecs
        .filter(_.requirement.level == Requirement.Level.Required)
        .map(_.key)
        .toSet

      val current = md.data.points.toVector
        .flatMap(_.attributes.map(_.key))
        .filter(key => required.contains(key))
        .toSet

      assertEquals(current, required, clue)
    }
  }
}
