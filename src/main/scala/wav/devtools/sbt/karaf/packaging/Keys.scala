package wav.devtools.sbt.karaf.packaging

import sbt.Keys._
import sbt._
import wav.devtools.karaf.packaging.{FeaturesXml, KarafDistribution}

object KarafPackagingKeys {

  import FeaturesXml._

  lazy val featuresXml             = taskKey[FeaturesXml]("The project's features repository")
  lazy val featuresFile            = taskKey[Option[File]]("Generate features.xml")
  lazy val featuresRequired        = settingKey[Set[Dependency]]("Features that will be put in the project feature")
  lazy val featuresRepositories    = taskKey[Set[Repository]]("Repositories where `featuresRequired` are specified")
  lazy val featuresSelected        = taskKey[Either[Set[Dependency], Set[Feature]]]("Resolved features or unsatisfied feature constraints")
  lazy val featuresProjectBundle   = taskKey[Bundle]("The project bundle to add to the project feature")
  lazy val featuresProjectFeature  = taskKey[Feature]("The project feature to add to features.xml")
  lazy val featuresAddDependencies = settingKey[Boolean]("EXPERIMENTAL: Add the dependencies of the resolved `featuresRequired` setting to `libraryDependencies`")

  /**
   * Usage hint: makes the use of `.versionAsInProject()` available in pax-exam tests
   */
  lazy val shouldGenerateDependsFile = settingKey[Boolean]("Generate a dependencies.properties file like the `maven-depends-plugin`")

  lazy val karafDistribution       = settingKey[KarafDistribution]("The archive and the archive's subdirectory for a karaf distribution")
  lazy val karafSourceDistribution = taskKey[Option[File]]("The source karaf archive")
  lazy val unpackKarafDistribution = taskKey[File]("Unpack the source karaf archive")

}

object SbtKarafPackaging extends AutoPlugin {

  object autoImport extends PluginSyntax {

    val KarafPackagingKeys = wav.devtools.sbt.karaf.packaging.KarafPackagingKeys

    def defaultKarafPackagingSettings: Seq[Setting[_]] =
      KarafPackagingDefaults.featuresSettings ++
        KarafPackagingDefaults.karafDistributionSettings

  }

  override def requires =
    sbt.plugins.MavenResolverPlugin

  override def projectSettings =
    autoImport.defaultKarafPackagingSettings

}