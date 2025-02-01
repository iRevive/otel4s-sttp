# otel4s-sttp3

## Getting started

Prepare `build.sbt`:
```scala
libraryDependencies ++= Seq(
  "io.github.irevive" %%% "otel4s-sttp3-metrics" % "0.0.1",
  "io.github.irevive" %%% "otel4s-sttp3-trace"   % "0.0.1"
)
```

## Enable metrics and tracing

```scala
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import sttp.client3.SttpBackend
import sttp.client3.otel4s.metrics.MetricsBackend

implicit val tracerProvider: TracerProvider[IO] = ???
implicit val meterProvider: MeterProvider[IO] = ???

val backend: SttpBackend[IO, Any] = ???

for {
  metered <- MetricsBackend[IO, Any](backend)
  traced <- TracingBackend[IO, Any](metered)
  // use the traced backend
} yield ()
```
