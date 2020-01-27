package osgifelix

import aQute.bnd.osgi.Jar
import aQute.bnd.version.Version
import com.typesafe.sbt.osgi.SbtOsgi.defaultOsgiSettings
import com.typesafe.sbt.osgi.OsgiKeys.bundle
import org.apache.felix.bundlerepository.Repository
import org.osgi.framework.VersionRange
import sbt.Keys._
import sbt._
import OsgiTasks._
import sbt.librarymanagement.ConfigurationFilter

/**
 * Created by jolz on 13/08/15.
 */
object OsgiFelixPlugin extends AutoPlugin {

  lazy val jarCacheKey = taskKey[File => Jar]("Per run jar cache")

  object autoImport extends InstructionFilters {
    lazy val osgiRepositories = taskKey[Seq[Repository]]("Repositories for resolving OSGi dependencies against")
    lazy val osgiDependencies = settingKey[Seq[OsgiRequirement]]("OSGi package or bundle dependencies")
    lazy val osgiDependencyClasspath = taskKey[Classpath]("Classpath from OSGi dependencies")

    lazy val osgiRepositoryConfigurations = settingKey[ConfigurationFilter]("Ivy configurations to include in OBR repository")
    lazy val osgiRepositoryRules = settingKey[Seq[InstructionFilter]]("Filters for generating BND instructions")
    lazy val osgiRepositoryInstructions = taskKey[Seq[BundleInstructions]]("Instructions for building the bundles in the OBR repository")
    lazy val osgiRepoAdmin = taskKey[FelixRepoRunner]("Repository admin interface")
    lazy val osgiExtraJDKPackages = settingKey[Seq[String]]("Extra JDK packages for framework classpath")
    lazy val osgiNamePrefix = settingKey[String]("Prefix for generated bundle names")

    lazy val osgiDevManifest = taskKey[BundleLocation]("Generate dev bundle")

    lazy val osgiBundles = taskKey[Seq[BundleLocation]]("OSGi bundle locations")
    lazy val osgiRequiredBundles = taskKey[Seq[BundleLocation]]("OSGi bundles required for running")

    lazy val osgiRunLevels = settingKey[Map[Int, Seq[BundleRequirement]]]("OSGi run level configuration")
    lazy val osgiRunFrameworkLevel = settingKey[Int]("OSGi framework run level")
    lazy val osgiRunDefaultLevel = settingKey[Int]("OSGi default run level")

    lazy val osgiStartConfig = taskKey[BundleStartConfig]("OSGi framework start configuration")

    lazy val osgiPackageOBR = taskKey[File]("Create a self contained OBR repository")

    lazy val osgiDeploy = taskKey[(File, ForkOptions, Seq[String])]("Deploy an OSGi launcher to directory")

    lazy val osgiShow = taskKey[Unit]("Show OSGi runner config")
    lazy val osgiShowDeps = inputKey[Unit]("Show resolved dependencies for runner")

    lazy val DeployLauncher = config("deployLauncher")


    def manifestOnly(symbolicName: String, version: String, headers: Map[String, String]) =
      ManifestOnly(None, symbolicName, new Version(version), headers)

    def packageReqs(name: String*) = name.map(n => PackageRequirement(n)).toSeq

    def bundleReqs(name: String*) = name.map(n => BundleRequirement(n)).toSeq

    def bundleReq(name: String, version: Option[String]) = BundleRequirement(name, version.map(new VersionRange(_)))

    def packageReq(name: String, version: Option[String]) = PackageRequirement(name, version.map(new VersionRange(_)))

    def fragmentsFor(name: String) = FragmentsRequirement(name)


    lazy val defaultSingleProjectSettings = repositorySettings ++ bundleSettings(ThisProject) ++
      runnerSettings(ThisProject, ScopeFilter(inProjects(ThisProject)), true)

    lazy val repositorySettings = Seq(
      osgiExtraJDKPackages := Seq("sun.reflect", "sun.reflect.generics.reflectiveObjects", "com.sun.jna", "com.sun", "sun.misc", "com.sun.jdi", "com.sun.jdi.connect",
        "com.sun.jdi.event", "com.sun.jdi.request", "sun.nio.ch", "com.sun.javadoc", "com.sun.tools.javadoc"),
      osgiRepositoryInstructions := {
        val extra = osgiExtraJDKPackages.value
        val extBundle = if (extra.nonEmpty) {
          val bundleName = osgiNamePrefix.value + "jdkext"
          Seq(manifestOnly(bundleName, "1.0.0", Map(
            "Fragment-Host" -> "system.bundle; extension:=framework",
            "Export-Package" -> extra.mkString(",")
          )))
        } else Seq()
        extBundle ++ createInstructionsTask.value
      },
      osgiRepositories / artifactPath := target.value,
      osgiRepositoryConfigurations := configurationFilter(Compile.name),
      osgiRepositoryRules := Seq.empty,
      Compile / osgiRepositories := cachedRepoLookupTask.value,
      osgiNamePrefix := name.value + "."
    ) ++ repoAdminTasks

    def bundleSettings(repositoryProject: ProjectReference) = defaultOsgiSettings ++ Seq(
      Compile / osgiDependencies := Seq.empty,
      Test / osgiDependencies := Seq.empty,
      osgiRepositoryRules := Seq.empty,
      Compile / osgiDependencyClasspath := osgiDependencyClasspathTask(Compile).value,
      Test / osgiDependencyClasspath := osgiDependencyClasspathTask(Test).value,
      Test / unmanagedClasspath ++= ((Compile / unmanagedClasspath).value ++ (Test / osgiDependencyClasspath).all(ScopeFilter(inDependencies(ThisProject, true, true))).value.flatten).distinct,
      Runtime / unmanagedClasspath ++= (Compile / unmanagedClasspath).value,
      Compile / unmanagedClasspath ++= scalaInstance.value.allJars.toSeq.classpath ++ (Compile / osgiDependencyClasspath).all(ScopeFilter(inDependencies(ThisProject, true, true))).value.flatten.distinct,
      osgiDevManifest := devManifestTask.value,
      Global / jarCacheKey := jarCacheTask.value,
      Compile / managedClasspath := Seq(),
      Compile / osgiRepositories := (repositoryProject / Compile / osgiRepositories).value) ++ repoAdminTasks

    def repositoryAndRunnerSettings(prjs: ProjectReference*) = repositorySettings ++ runnerSettings(ThisProject, ScopeFilter(inProjects(prjs: _*)))

    def runnerSettings(repositoryProject: ProjectReference, bundlesScope: ScopeFilter, deploying: Boolean = true) = Seq(
      osgiShow := showStartup(ThisScope.in(run.key)).value,
      osgiShowDeps := showDependencies(ThisScope.in(run.key)).evaluated,
      run / osgiRepositories := (repositoryProject / Compile / osgiRepositories).value :+ osgiApplicationRepos(ThisScope.in(run.key)).value,
      run / osgiBundles := osgiDevManifest.all(bundlesScope).value,
      osgiRunDefaultLevel := 1,
      osgiRunFrameworkLevel := 1,
      run / osgiDependencies := Seq.empty,
      osgiRunLevels := Map.empty,
      run / osgiRequiredBundles := (run / osgiBundles).value,
      run / osgiStartConfig := osgiStartConfigTask(ThisScope.in(run.key)).value,
      Compile / osgiBundles := bundle.all(bundlesScope).value.map(BundleLocation.apply),
      run := osgiRunTask.evaluated
    ) ++ (if (deploying) inConfig(DeployLauncher)(Defaults.configSettings ++ Seq(
      osgiRepositories := (repositoryProject / Compile / osgiRepositories).value :+ osgiApplicationRepos(ThisScope in DeployLauncher).value,
      osgiDependencies := (run / osgiDependencies).value,
      osgiRequiredBundles := (DeployLauncher / osgiBundles ).value,
      osgiBundles := (Compile / osgiBundles).value,
      osgiStartConfig := osgiStartConfigTask(ThisScope in DeployLauncher).value,
      packageBin / artifact := artifact.value.withType("zip").withExtension("zip"),
      packageBin := packageDeploymentTask.value)) ++
      Seq(
        osgiDeploy / artifactPath := target.value / "launcher",
        osgiDeploy := osgiDeployTask.value
      )
    else Seq.empty)

    def packagedRepositorySettings(bundlesScope: ScopeFilter) = Seq(
      osgiPackageOBR / artifactPath := target.value / "repository",
      Compile / osgiPackageOBR := packageObrTask(Compile).value
    ) ++ repoAdminTasks

  }

  import autoImport._

  val repoAdminTasks = Seq(
    Global / osgiRepoAdmin := repoAdminTaskRunner.value,
    Global / onUnload := { state =>
      val existing = (Global / onUnload).value
      FelixRepositories.shutdownFelix
      existing(state)
    }
  )
}
