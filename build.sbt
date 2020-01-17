organization := "org.doolse"

name := "sbt-osgi-felix"

description := "SBT plugin for working with OSGi bundles using Apache Felix"

version := "1.0.4"

libraryDependencies ++= Seq(
  "biz.aQute.bnd" % "bndlib" % "2.4.0",
  "org.apache.felix" % "org.apache.felix.bundlerepository" % "2.0.4",
  "org.apache.felix" % "org.apache.felix.main" % "5.0.0" intransitive(),
  "io.argonaut" %% "argonaut" % "6.2.3",
  "org.scalaz" %% "scalaz-core" % "7.2.29")

sbtPlugin := true

addSbtPlugin("com.typesafe.sbt" % "sbt-osgi" % "0.9.5")

BintrayPlugin.bintrayPublishSettings

publishMavenStyle := false

bintrayRepository in bintray := "sbt-plugins"

bintrayOrganization in bintray := None

licenses += "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")

