import bintray.Keys._

organization := "org.doolse"

name := "sbt-osgi-felix"

description := "SBT plugin for working with OSGi bundles using Apache Felix"

version := "1.0.7-PHILIP"

scalaVersion := "2.10.4"

libraryDependencies ++= Seq("biz.aQute.bnd" % "biz.aQute.bndlib" % "3.3.0",
  "org.slf4j" % "slf4j-api" % "1.7.22",
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
