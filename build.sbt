organization := "org.doolse"

name := "sbt-osgi-felix"

scalaVersion := "2.10.4"

libraryDependencies ++= Seq("biz.aQute.bnd" % "bndlib" % "2.4.0",
  "org.apache.felix" % "org.apache.felix.bundlerepository" % "2.0.4",
  "org.apache.felix" % "org.apache.felix.main" % "5.0.0" intransitive(),
  "org.scalaz" %% "scalaz-core" % "7.1.2")

sbtPlugin := true

