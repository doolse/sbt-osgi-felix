import bintray.Keys._

organization := "org.doolse"

name := "sbt-osgi-felix"

description := "SBT plugin for working with OSGi bundles using Apache Felix"

version := "1.0.3"

scalaVersion := "2.10.4"

libraryDependencies ++= Seq("biz.aQute.bnd" % "bndlib" % "2.4.0",
  "org.apache.felix" % "org.apache.felix.bundlerepository" % "2.0.4",
  "org.apache.felix" % "org.apache.felix.main" % "5.0.0" intransitive(),
  "io.argonaut" %% "argonaut" % "6.1",
  "org.scalaz" %% "scalaz-core" % "7.1.1")

sbtPlugin := true

addSbtPlugin("com.typesafe.sbt" % "sbt-osgi" % "0.7.0")

bintrayPublishSettings

publishMavenStyle := false

repository in bintray := "sbt-plugins"

bintrayOrganization in bintray := None

licenses += "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")

