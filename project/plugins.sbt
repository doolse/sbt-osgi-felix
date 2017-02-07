
lazy val plugins = (project in file("."))
  .dependsOn(sbtOsgi)

def sbtOsgi = uri("git://github.com/PhilAndrew/sbt-osgi.git")


