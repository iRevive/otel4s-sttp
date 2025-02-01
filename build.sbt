ThisBuild / tlBaseVersion := "0.1"
ThisBuild / organization  := "io.github.irevive"
ThisBuild / licenses      := Seq(License.Apache2)
ThisBuild / startYear     := Some(2025)

// the project does not provide any binary guarantees
ThisBuild / tlMimaPreviousVersions := Set.empty

ThisBuild / developers := List(
  // your GitHub handle and name
  tlGitHubDev("iRevive", "Maksym Ochenashko")
)

ThisBuild / githubWorkflowBuildPostamble ++= Seq(
  WorkflowStep.Sbt(
    List("doc", "docs/mdoc"),
    name = Some("Verify docs"),
    cond = Some("matrix.project == 'rootJVM' && matrix.scala == '2.13'")
  )
)

ThisBuild / githubWorkflowJavaVersions := Seq(
  JavaSpec.temurin("8"),
  JavaSpec.semeru("21")
)

val Versions = new {
  val Scala213        = "2.13.16"
  val Scala3          = "3.3.3"
  val CatsEffect      = "3.6.0-RC1"
  val Otel4s          = "0.12.0-RC2"
  val Sttp            = "3.10.2"
  val Munit           = "1.0.0"
  val MUnitCatsEffect = "2.0.0"
}

ThisBuild / crossScalaVersions := Seq(Versions.Scala213, Versions.Scala3)
ThisBuild / scalaVersion       := Versions.Scala213 // the default Scala

lazy val munitDependencies = Def.settings(
  libraryDependencies ++= Seq(
    "org.scalameta" %%% "munit"             % Versions.Munit           % Test,
    "org.typelevel" %%% "munit-cats-effect" % Versions.MUnitCatsEffect % Test
  )
)

lazy val root = tlCrossRootProject
  .settings(name := "otel4s-sttp3")
  .aggregate(metrics, trace)

lazy val metrics = crossProject(JVMPlatform, JSPlatform /*, NativePlatform*/ )
  .crossType(CrossType.Pure)
  .in(file("modules/metrics"))
  .enablePlugins(BuildInfoPlugin)
  .settings(munitDependencies)
  .settings(
    name := "otel4s-sttp3-metrics",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %%% "core"                                % Versions.Sttp,
      "org.typelevel"                 %%% "otel4s-core-metrics"                 % Versions.Otel4s,
      "org.typelevel"                 %%% "otel4s-semconv"                      % Versions.Otel4s,
      "org.typelevel"                 %%% "otel4s-semconv-metrics-experimental" % Versions.Otel4s % Test,
      "org.typelevel"                 %%% "otel4s-sdk-metrics-testkit"          % Versions.Otel4s % Test,
      "com.softwaremill.sttp.client3" %%% "cats"                                % Versions.Sttp   % Test
    ),
    buildInfoKeys    := Seq(version),
    buildInfoPackage := "sttp.client3.otel4s.metrics",
    buildInfoOptions += BuildInfoOption.PackagePrivate,
  )

lazy val trace = crossProject(JVMPlatform, JSPlatform /*, NativePlatform*/ )
  .crossType(CrossType.Pure)
  .in(file("modules/trace"))
  .settings(munitDependencies)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "otel4s-sttp3-trace",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %%% "core"                     % Versions.Sttp,
      "org.typelevel"                 %%% "otel4s-core-trace"        % Versions.Otel4s,
      "org.typelevel"                 %%% "otel4s-semconv"           % Versions.Otel4s,
      "org.typelevel"                 %%% "otel4s-sdk-trace-testkit" % Versions.Otel4s     % Test,
      "org.typelevel"                 %%% "cats-effect-testkit"      % Versions.CatsEffect % Test,
      "com.softwaremill.sttp.client3" %%% "cats"                     % Versions.Sttp       % Test
    ),
    buildInfoKeys    := Seq(version),
    buildInfoPackage := "sttp.client3.otel4s.trace",
    buildInfoOptions += BuildInfoOption.PackagePrivate,
  )
