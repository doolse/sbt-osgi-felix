
organization := "org.philandrew"

name := "sbt-osgi-felix-p"

description := "SBT plugin for working with OSGi bundles using Apache Felix"

version := "1.0.16"

scalaVersion := "2.10.6"

libraryDependencies ++= Seq("biz.aQute.bnd" % "biz.aQute.bndlib" % "3.3.0",
  "org.slf4j" % "slf4j-api" % "1.7.22",
  "org.apache.felix" % "org.apache.felix.bundlerepository" % "2.0.4",
  "org.apache.felix" % "org.apache.felix.main" % "5.0.0" intransitive(),
  "io.argonaut" %% "argonaut" % "6.2-RC2",
  "org.scalaz" %% "scalaz-core" % "7.2.8")

//lazy val sbtOsgiPlugin = uri("https://github.com/philip368320/sbt-osgi.git#49d72598b49e73042d36bff536eb50d406df5a43")

//lazy val root = project.in(file(".")).dependsOn(sbtOsgiPlugin)

sbtPlugin := true

//addSbtPlugin("com.typesafe.sbt" % "sbt-osgi" % "0.9.1")

//bintrayPackage := "sbt-osgi-felix-p"

//bintrayRepository := "sbt-osgi-felix-p"

//bintrayOrganization := Some("philandrew")

//publishMavenStyle := true

licenses += ("MIT", url("http://opensource.org/licenses/MIT"))

//bintrayReleaseOnPublish in ThisBuild := true // true or false, it still published

//bintrayReleaseOnPublish in ThisBuild := false
