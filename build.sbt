// Scala versions
val scala213 = "2.13.18"

val scala3 = "3.3.7"

val scala2 = List(scala213)
val allScalaVersions = scala3 :: scala2

// Variables
val org: String = "com.outr"
val projectName: String = "rapid"
val githubOrg: String = "outr"
val email: String = "matt@matthicks.com"
val developerId: String = "darkfrog"
val developerName: String = "Matt Hicks"
val developerURL: String = "https://matthicks.com"

name := projectName
ThisBuild / organization := org
ThisBuild / version := "2.5.0"
ThisBuild / scalaVersion := scala3
ThisBuild / crossScalaVersions := allScalaVersions
ThisBuild / scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

ThisBuild / sonatypeCredentialHost := xerial.sbt.Sonatype.sonatypeCentralHost
ThisBuild / publishTo := sonatypePublishToBundle.value
ThisBuild / sonatypeProfileName := org
ThisBuild / licenses := Seq("MIT" -> url(s"https://github.com/$githubOrg/$projectName/blob/master/LICENSE"))
ThisBuild / sonatypeProjectHosting := Some(xerial.sbt.Sonatype.GitHubHosting(githubOrg, projectName, email))
ThisBuild / homepage := Some(url(s"https://github.com/$githubOrg/$projectName"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url(s"https://github.com/$githubOrg/$projectName"),
    s"scm:git@github.com:$githubOrg/$projectName.git"
  )
)
ThisBuild / developers := List(
  Developer(id=developerId, name=developerName, email=email, url=url(developerURL))
)

ThisBuild / resolvers += Resolver.mavenLocal

ThisBuild / outputStrategy := Some(StdoutOutput)

ThisBuild / Test / testOptions += Tests.Argument("-oDF")

ThisBuild / Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-W", "30", "30")

ThisBuild / Test / parallelExecution := false

ThisBuild / Test / logBuffered := false

val sourcecodeVersion: String = "0.4.4"

val scribeVersion: String = "3.17.0"

/// Testing and Benchmarking Libraries

val catsVersion: String = "3.6.3"

val fs2Version: String = "3.12.2"

val scalaJsMacrotaskVersion: String = "1.1.1"

val scalaTestVersion: String = "3.2.19"

lazy val root = project.in(file("."))
  .aggregate(core.jvm, scribe.jvm, test.jvm, cats.jvm)
  .settings(
    name := projectName,
    publish := {},
    publishLocal := {}
  )

lazy val core = crossProject(JVMPlatform) //, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .settings(
    name := s"$projectName-core",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "sourcecode" % sourcecodeVersion,
      "org.scalatest" %%% "scalatest" % scalaTestVersion % Test
    ),
    Compile / unmanagedSourceDirectories ++= {
      val major = if (scalaVersion.value.startsWith("3")) "-3" else "-2"
      List(CrossType.Pure, CrossType.Full).flatMap(
        _.sharedSrcDir(baseDirectory.value, "main").toList.map { f =>
          file(f.getPath + major)
        }
      )
    }
  )
//  .jsSettings(
//    libraryDependencies ++= Seq(
//      "org.scala-js" %%% "scala-js-macrotask-executor" % scalaJsMacrotaskVersion,
//    )
//  )

lazy val scribe = crossProject(JVMPlatform)
  .crossType(CrossType.Full)
  .dependsOn(core)
  .settings(
    name := s"$projectName-scribe",
    libraryDependencies ++= Seq(
      "com.outr" %%% "scribe" % scribeVersion,
      "org.scalatest" %%% "scalatest" % scalaTestVersion % Test
    )
  )

lazy val test = crossProject(JVMPlatform) //, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .dependsOn(core)
  .settings(
    name := s"$projectName-test",
    libraryDependencies ++= Seq(
      "org.scalatest" %%% "scalatest" % scalaTestVersion
    )
  )

lazy val cats = crossProject(JVMPlatform) //, JSPlatform)
  .crossType(CrossType.Full)
  .dependsOn(core)
  .settings(
    name := s"$projectName-cats",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect" % catsVersion,
      "co.fs2" %%% "fs2-core" % fs2Version,
      "org.scalatest" %%% "scalatest" % scalaTestVersion % Test
    )
  )

lazy val benchmark = project.in(file("benchmark"))
  .enablePlugins(JmhPlugin)
  .dependsOn(core.jvm, cats.jvm)
  .settings(
    name := s"$projectName-benchmark",
    libraryDependencies ++= Seq(
      "org.openjdk.jmh" % "jmh-core" % "1.37",
      "org.openjdk.jmh" % "jmh-generator-annprocess" % "1.37",
      "org.typelevel" %% "cats-effect" % catsVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "dev.zio" %% "zio" % "2.1.22"
    )
  )

lazy val docs = project
  .in(file("documentation"))
  .dependsOn(core.jvm, scribe.jvm, cats.jvm, test.jvm)
  .enablePlugins(MdocPlugin)
  .settings(
    mdocVariables := Map(
      "VERSION" -> version.value
    ),
    mdocOut := file(".")
  )