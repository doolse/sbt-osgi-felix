package wav.devtools.sbt.karaf.packaging

import sbt.Keys._
import sbt._
import wav.devtools.karaf.packaging.FeaturesXml._
import wav.devtools.karaf.packaging.{MavenUrl, FeaturesXml, FeaturesXmlFormats, KarafDistribution}
import wav.devtools.sbt.karaf.packaging

object KarafPackagingDefaults {

  import KarafPackagingKeys._
  import wav.devtools.karaf.packaging.{Resolution, Util}

  lazy val featuresXmlTask = Def.task {
    new FeaturesXml(name.value, featuresRepositories.value.toSeq :+ featuresProjectFeature.value)
  }

  lazy val featuresFileTask = Def.task {
    Util.write(
      crossTarget.value / "features.xml",
      FeaturesXmlFormats.featuresXsd,
      FeaturesXmlFormats.makeFeaturesXml(featuresXml.value))
  }

  private lazy val allFeaturesRepositories   = taskKey[Set[FeatureRepository]]("All resolved features repositories")

  private lazy val allFeaturesRepositoriesTask = Def.task {
    val resolveAll = SbtResolution.resolveAllFeatureRepositoriesTask.value
    resolveAll(update.value)
  }

  lazy val featuresRepositoriesTask = Def.task {
    val constraints = featuresRequired.value
    val repos = allFeaturesRepositories.value
    for {
      fr <- repos
      f <- fr.featuresXml.features
      c <- constraints
      if (Resolution.satisfies(c,f))
    } yield Repository(fr.url)
  }

  lazy val featuresSelectedTask = Def.task {
    val repos = allFeaturesRepositories.value
    Resolution.resolveFeatures(featuresRequired.value, repos.flatMap(_.featuresXml.features))
  }

  lazy val featuresProjectBundleTask = Def.task {
    val (_, f) = (packagedArtifact in(Compile, packageBin)).value
    Bundle(f.toURI.toString)
  }

  lazy val featuresProjectFeatureTask = Def.task {
    val selected = featuresSelected.value
    val resolved = Resolution.mustResolveFeatures(selected)
    val bundles = SbtResolution.selectProjectBundles(update.value, resolved) + featuresProjectBundle.value
    feature(name.value, version.value, bundles ++ featuresRequired.value)
      .copy(description = description.value)
  }

  lazy val generateDependsFileTask: SbtTask[Seq[File]] = Def.task {
    if (shouldGenerateDependsFile.value) {
      val f = (resourceManaged in Compile).value / packaging.DependenciesProperties.jarPath
      val artifacts = for {
        conf <- update.value.configurations
        moduleReport <- conf.modules
        (a, _) <- moduleReport.artifacts
      } yield {
          val m = moduleReport.module
          DependenciesProperties.Artifact(m.organization, a.name, m.revision, conf.configuration, a.`type`)
        }
      val fcontent = DependenciesProperties(
        DependenciesProperties.Project(organization.value, name.value, version.value),
        artifacts)
      IO.write(f, fcontent)
      Seq(f)
    } else Seq.empty
  }

  lazy val featuresPackagedArtifactsTask: SbtTask[Map[Artifact, File]] = Def.task {
    val pas = packagedArtifacts.value
    featuresFile.value
      .map(f => pas.updated(Artifact(name.value, "xml", "xml", "features"), f))
      .getOrElse(pas)
  }

  lazy val karafSourceDistributionTask: SbtTask[Option[File]] = Def.task {
    val dist = karafDistribution.value
    val archive = target.value / dist.artifactName
    if (archive.exists()) Some(archive)
    else {
      // sbt-maven-resolver doesn't like artifacts that are non jar which don't have a classifier. (sbt 0.13.9)
      // So we bypass it by downloading it manually without using the update report.
      if (dist.url.getScheme.startsWith("mvn")) {
        val rs = fullResolvers.value.collect { case mr @ MavenRepository(n,r) => Util.MavenRepo(n,r,mr.isCache) }
        val mavenLocal = new File(new URI(Resolver.mavenLocal.root))
        val result = Util.downloadMavenArtifact(dist.url, mavenLocal, rs)
        result map { f =>
          IO.copyFile(f, archive)
          f
        }
      } else if (Util.download(dist.url, archive)) Some(archive)
      else None
    }
  }

  lazy val unpackKarafDistributionTask: SbtTask[File] = Def.task {
    val source = karafDistribution.value
    val archive = karafSourceDistribution.value.filter(_.exists)
    require(archive.isDefined, s"An archive is found for $source")
    val karafDist = target.value / "karaf-dist"
    Util.unpack(archive.get, karafDist)
    val contentPath = Option(source.contentPath).filterNot(_.isEmpty).map(karafDist / _)
    val finalKarafDist = contentPath getOrElse karafDist
    require(finalKarafDist.isDirectory(), s"$finalKarafDist not found")
    finalKarafDist
  }

  val KarafMinimalDistribution = {
    import org.apache.commons.lang3.SystemUtils
    val ext = if (SystemUtils.IS_OS_WINDOWS) "zip" else "tar.gz"
    KarafDistribution(
      uri(s"mvn:org.apache.karaf/apache-karaf-minimal/4.0.2/$ext"),
      s"apache-karaf-minimal-4.0.2.$ext",
      "apache-karaf-minimal-4.0.2")
  }

  lazy val karafDistributionSettings: Seq[Setting[_]] =
    Seq(
      karafDistribution := KarafMinimalDistribution,
      karafSourceDistribution := karafSourceDistributionTask.value,
      unpackKarafDistribution := unpackKarafDistributionTask.value)

  lazy val featureDependencies = Def.task {
    if (featuresAddDependencies.value) {
      val logger = streams.value.log
      val download = (url: MavenUrl) => Ivy.
        downloadMavenArtifact(url, externalResolvers.value, ivySbt.value, logger, updateOptions.value)
      val resolve = (m: ModuleID) => SbtResolution.downloadFeaturesRepository(logger, download, m)
      val results = libraryDependencies.value.map(resolve)
      val failures = results.collect { case Left(e) => e }
      failures.foreach(logger.error(_))
      if (failures.nonEmpty)
        sys.error("Could not resolve all features repositories.")
      val repos = results.collect { case Right(frs) => frs }.flatten.toSet
      val result = Resolution.resolveFeatures(featuresRequired.value, repos.flatMap(_.featuresXml.features))
      val resolved = Resolution.mustResolveFeatures(result)
      SbtResolution.toLibraryDependencies(resolved)
    } else Seq.empty
  }

  lazy val featuresSettings: Seq[Setting[_]] =
      Seq(
        featuresXml := featuresXmlTask.value,
        featuresFile := Some(featuresFileTask.value),
        featuresRequired := Set.empty,
        featuresRepositories := featuresRepositoriesTask.value,
        allFeaturesRepositories := allFeaturesRepositoriesTask.value,
        featuresSelected := featuresSelectedTask.value,
        featuresProjectBundle := featuresProjectBundleTask.value,
        featuresProjectFeature := featuresProjectFeatureTask.value,
        packagedArtifacts <<= featuresPackagedArtifactsTask,
        featuresAddDependencies := false,
        allDependencies ++= featureDependencies.value,
        shouldGenerateDependsFile := false,
        resourceGenerators in Compile <+= generateDependsFileTask)

}