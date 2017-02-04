package wav.devtools.sbt.karaf.packaging

private [packaging] object DependenciesProperties {

  val jarPath = "META-INF/maven/dependencies.properties"

  case class Project(groupId: String, artifactId: String, version: String)

  case class Artifact(groupId: String, artifactId: String, version: String, scope: String, `type`: String)

  def apply(project: Project, dependencies: Seq[Artifact]): String = {
    val pp = Seq(
      "artifactId" -> project.artifactId,
      "groupId" -> project.groupId,
      "version" -> project.version)
    val dp = dependencies.map { d =>
      val pre = (s"${d.groupId}/${d.artifactId}")
      Seq(s"$pre/scope" -> d.scope, s"$pre/version" -> d.version, s"$pre/type" -> d.`type`)
    }.flatten
    val all = (pp ++ dp).sorted
    val colWidth = all.map(_._1.length).max
    all.map(e => e._1.padTo(colWidth, ' ') + s"\t=\t${e._2}").mkString("\n")
  }

}
