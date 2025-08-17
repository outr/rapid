// =========================
// Scala Versions
// =========================
val scala213 = "2.13.16"
val scala3 = "3.3.6"

val scala2 = List(scala213)
val allScalaVersions = scala3 :: scala2

// =========================
// Project Metadata
// =========================
val org = "com.outr"
val projectName = "rapid"
val githubOrg = "outr"
val email = "matt@matthicks.com"
val developerId = "darkfrog"
val developerName = "Matt Hicks"
val developerURL = "https://matthicks.com"

ThisBuild / organization := org
ThisBuild / version := "1.1.0-SNAPSHOT"
ThisBuild / scalaVersion := scala3
ThisBuild / crossScalaVersions := allScalaVersions
ThisBuild / scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

ThisBuild / sonatypeCredentialHost := xerial.sbt.Sonatype.sonatypeCentralHost
ThisBuild / publishTo := sonatypePublishToBundle.value
ThisBuild / sonatypeProfileName := org
ThisBuild / licenses := Seq("MIT" -> url(s"https://github.com/$githubOrg/$projectName/blob/master/LICENSE"))
ThisBuild / sonatypeProjectHosting := Some(xerial.sbt.Sonatype.GitHubHosting(githubOrg, projectName, email))
ThisBuild / homepage := Some(url(s"https://github.com/$githubOrg/$projectName"))
ThisBuild / scmInfo := Some(ScmInfo(url(s"https://github.com/$githubOrg/$projectName"), s"scm:git@github.com:$githubOrg/$projectName.git"))
ThisBuild / developers := List(
  Developer(id = developerId, name = developerName, email = email, url = url(developerURL))
)

ThisBuild / resolvers ++= Seq(
  Resolver.mavenLocal,
  Resolver.sonatypeRepo("public"),
  "jitpack" at "https://jitpack.io"
)

ThisBuild / outputStrategy := Some(StdoutOutput)
ThisBuild / Test / testOptions += Tests.Argument("-oDF")
ThisBuild / Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-W", "30", "30")
ThisBuild / Test / parallelExecution := false
ThisBuild / Test / logBuffered := false

// =========================
// Dependency Versions
// =========================
val scribeVersion = "3.17.0"
val catsVersion = "3.6.3"
val fs2Version = "3.12.0"
val scalaTestVersion = "3.2.19"
val zioVersion = "2.0.15"
val jmhVersion = "1.37"

// =========================
// Core Project
// =========================
lazy val core = crossProject(JVMPlatform)
  .crossType(CrossType.Full)
  .settings(
    name := s"$projectName-core",
    libraryDependencies ++= Seq(
      "org.scalatest" %%% "scalatest" % scalaTestVersion % Test,
      "org.typelevel" %% "cats-effect" % catsVersion,
      "dev.zio" %% "zio" % zioVersion,
      "org.openjdk.jmh" % "jmh-core" % jmhVersion,
      "org.openjdk.jmh" % "jmh-generator-annprocess" % jmhVersion
    )
  )

// =========================
// Benchmark Project
// =========================
lazy val benchmark = project.in(file("benchmark"))
  .enablePlugins(JmhPlugin)
  .dependsOn(core.jvm)
  .settings(
    name := s"$projectName-benchmark",
    scalaVersion := scala3,
    Compile / unmanagedSourceDirectories += baseDirectory.value / ".." / "core" / "shared" / "src" / "main" / "scala",
    libraryDependencies ++= Seq(
      "org.openjdk.jmh" % "jmh-core" % jmhVersion,
      "org.openjdk.jmh" % "jmh-generator-annprocess" % jmhVersion,
      "org.typelevel" %% "cats-effect" % catsVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "dev.zio" %% "zio" % zioVersion,
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test
    ),
    Compile / javacOptions ++= Seq(
      "-proc:only",
      "-processor", "org.openjdk.jmh.generators.BenchmarkProcessor"
    ),
    Compile / unmanagedResourceDirectories += (Compile / classDirectory).value / "META-INF"
  )

// =========================
// Root Project
// =========================
lazy val root = project.in(file("."))
  .aggregate(core.jvm, benchmark)
  .settings(
    name := projectName,
    publish := {},
    publishLocal := {}
  )
