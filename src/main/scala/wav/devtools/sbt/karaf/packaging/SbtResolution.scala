package wav.devtools.sbt.karaf.packaging

import sbt.Keys._
import sbt._
import wav.devtools.karaf.packaging._, FeaturesXml._

private[packaging] object SbtResolution {

  val WRAP_BUNDLE_INSTRUCTIONS = "wrap_bundle_instructions"

  val featuresArtifactFilter = artifactFilter(name = "*", `type` = "xml", extension = "xml", classifier = "features")

  val bundleArtifactFilter = artifactFilter(name = "*", `type` = "jar" | "bundle", extension = "*", classifier = "*")

  def resolveFeaturesRepository(logger: Logger, mr: ModuleReport): Either[String, Seq[FeatureRepository]] = {
    val fas = for {
      (a, f) <- mr.artifacts
      if (canBeFeaturesRepository(a))
    } yield FeaturesArtifact(mr.module, a, Some(f))
    val notDownloaded = fas.filterNot(_.downloaded)
    if (notDownloaded.nonEmpty) Left(s"Failed to resolve all the features repositories for the module: ${mr.module}, missing artifact: ${notDownloaded.map(_.artifact.name)}")
    else Right(fas.flatMap { fa =>
      val r = fa.toRepository
      if (r.isEmpty) logger.warn(s"Ignored possible features repository, content not known: Artifact ${fa.artifact}, Module ${mr.module}")
      r
    })
  }

  def resolveAllFeatureRepositoriesTask: SbtTask[UpdateReport => Set[FeatureRepository]] = Def.task {
    val logger = streams.value.log
    ur => {
      val fas = ur.filter(featuresArtifactFilter)
      val results = fas.configurations.flatMap(_.modules).map(resolveFeaturesRepository(logger, _))
      val failures = results.collect { case Left(e) => e }
      failures.foreach(logger.error(_))
      if (failures.nonEmpty)
        sys.error("Could not resolve all features repositories.")
      results.collect { case Right(frs) => frs }.flatten.toSet
    }
  }

  def toMavenUrl(m: ModuleID, a: Artifact): MavenUrl =
    MavenUrl(m.organization, m.name, m.revision, Some(a.`type`), a.classifier)

  private val bundleTypes = Set("bundle", "jar")
  def toBundle(m: ModuleID, a: Artifact, f: File): Bundle = {
    val t = Some(a.`type`).filterNot(bundleTypes.contains)
    val b =
      if (a.url.isEmpty) Bundle(MavenUrl(m.organization, m.name, m.revision, t, a.classifier).toString)
      else Bundle(a.url.get.toString)
    ArtifactUtil.getSymbolicName(f) match {
      case Some(_) => b
      case None if !b.url.startsWith("wrap:") => {
        var url = b.url
        m.extraAttributes.get(WRAP_BUNDLE_INSTRUCTIONS).foreach(url += _)
        WrappedBundle(url)
      }
    }
  }
  
  def toBundleID(url: MavenUrl): ModuleID =
      ModuleID(url.groupId, url.artifactId, url.version,
        explicitArtifacts = Seq(Artifact(url.artifactId,url.`type` getOrElse "jar", "jar", url.classifer, Nil, None, Map.empty)))

  def toLibraryDependencies(features: Set[Feature]): Seq[ModuleID] =
    features.flatMap(_.deps).collect {
      case b @ Bundle(MavenUrl(url), _, _, _, _) => toBundleID(url) % "provided"
    }.toSeq

  def selectProjectBundles(ur: UpdateReport, features: Set[Feature]): Set[Bundle] = {
    val mavenUrls = features
      .flatMap(_.deps)
      .collect { case Bundle(MavenUrl(url), _, _, _, _) => url }
    val cr = ur.filter(bundleArtifactFilter).configuration("compile").get
    val inFeatures =
      for {
        mr <- cr.modules
        m = mr.module
        (a, _) <- mr.artifacts
        url <- mavenUrls
        if (url.groupId == m.organization && url.artifactId == m.name)
      } yield (m, a)
    (for {
        mr <- cr.modules
        m = mr.module
        (a, f) <- mr.artifacts
        if (!inFeatures.contains((m,a)))
      } yield toBundle(m,a,f)).toSet
  }

  def downloadFeaturesRepository(
    logger: Logger,
    downloadArtifact: MavenUrl => Option[File],
    m: ModuleID): Either[String, Seq[FeatureRepository]] = {
    val as = m.explicitArtifacts.filter(canBeFeaturesRepository)
    val fas = for {
      a <- as
      url = toMavenUrl(m,a)
      f = downloadArtifact(url)
    } yield FeaturesArtifact(m, a, f)
    val notDownloaded = fas.filterNot(_.downloaded)
    if (notDownloaded.nonEmpty) return Left(s"Failed to resolve all the features repositories for the module: $m, missing artifact: ${notDownloaded.map(_.artifact.name)}")
    Right(fas.flatMap { fa =>
      val r = fa.toRepository
      if (r.isEmpty) logger.warn(s"Ignored possible features repository, content not known: Artifact ${fa.artifact}, Module $m")
      r
    })
  }

  def canBeFeaturesRepository(artifact: sbt.Artifact): Boolean =
    artifact.extension == "xml" && artifact.classifier == Some("features")

  def canBeOSGiBundle(artifact: sbt.Artifact): Boolean =
    artifact.extension == "jar" && (artifact.`type` == "jar" || artifact.`type` == "bundle")

}