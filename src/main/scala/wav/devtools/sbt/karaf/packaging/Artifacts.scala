package wav.devtools.sbt.karaf.packaging

import java.io.File

import wav.devtools.karaf.packaging._

sealed trait FeaturesArtifactData {

  val module  : sbt.ModuleID
  val artifact: sbt.Artifact
  val file    : Option[File]

  lazy val downloaded: Boolean =
    file.isDefined && file.get.exists()

  lazy val url: String =
    artifact.url.map(_.toString) getOrElse mavenUrl.toString

  lazy val mavenUrl: MavenUrl =
    MavenUrl(module.organization, artifact.name, module.revision, Some(artifact.extension), artifact.classifier)

  private [packaging] def equalsData(that: FeaturesArtifactData): Boolean =
    this.module == that.module && this.artifact == that.artifact

  def copyData(module: sbt.ModuleID = module, artifact: sbt.Artifact = artifact, file: Option[File] = file): FeaturesArtifact =
    new FeaturesArtifact(module, artifact, file)

}

case class FeaturesArtifact(module: sbt.ModuleID, artifact: sbt.Artifact, file: Option[File])
  extends FeaturesArtifactData {

  def toBundle: Option[OSGiBundle] =
    if (downloaded && SbtResolution.canBeOSGiBundle(artifact) && ArtifactUtil.getSymbolicName(file.get).isDefined)
      Some(new OSGiBundle(module, artifact, file))
    else None

  def toRepository: Option[FeatureRepository] =
    if (!downloaded) None
    else ArtifactUtil.readFeaturesXml(file.get).map(new FeatureRepository(module, artifact, file, _))

}

case class OSGiBundle(
  module: sbt.ModuleID,
  artifact: sbt.Artifact,
  file: Option[File])
  extends FeaturesArtifactData

case class FeatureRepository(
  module: sbt.ModuleID,
  artifact: sbt.Artifact,
  file: Option[File],
  featuresXml: FeaturesXml)
  extends FeaturesArtifactData {

  override def toString(): String =
    s"FeaturesRepository(${featuresXml.name},$module/${artifact.name},$file,${featuresXml.features.map(_.toDep)}})"

}