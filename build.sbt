// Scala versions
val scala213 = "2.13.15"

val scala3 = "3.5.2"

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
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := scala213
ThisBuild / crossScalaVersions := allScalaVersions
ThisBuild / scalacOptions ++= Seq("-unchecked", "-deprecation")

ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
ThisBuild / sonatypeRepository := "https://s01.oss.sonatype.org/service/local"
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

ThisBuild / Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF")

val catsVersion: String = "3.5.7"

val fs2Version: String = "3.11.0"

val scalaTestVersion: String = "3.2.19"

lazy val root = project.in(file("."))
  .aggregate(core, cats)
  .settings(
    name := projectName,
    publish := {},
    publishLocal := {}
  )

lazy val core = project.in(file("core"))
  .settings(
    name := s"$projectName-core",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test
    )
  )

lazy val cats = project.in(file("cats"))
  .dependsOn(core)
  .settings(
    name := s"$projectName-cats",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test
    )
  )

lazy val benchmark = project.in(file("benchmark"))
  .enablePlugins(JmhPlugin)
  .dependsOn(core, cats)
  .settings(
    name := s"$projectName-benchmark",
    libraryDependencies ++= Seq(
      "org.openjdk.jmh" % "jmh-core" % "1.37",
      "org.openjdk.jmh" % "jmh-generator-annprocess" % "1.37",
      "org.typelevel" %% "cats-effect" % catsVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "dev.zio" %% "zio" % "2.0.15"
    )
  )