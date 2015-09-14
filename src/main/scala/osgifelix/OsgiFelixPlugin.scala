package osgifelix

import aQute.bnd.osgi.Jar
import aQute.bnd.version.Version
import com.typesafe.sbt.osgi.SbtOsgi.defaultOsgiSettings
import com.typesafe.sbt.osgi.OsgiKeys.bundle
import org.apache.felix.bundlerepository.Repository
import org.osgi.framework.VersionRange
import sbt.Keys._
import sbt._

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
    lazy val osgiRepoAdmin = settingKey[FelixRepoRunner]("Repository admin interface")
    lazy val osgiExtraJDKPackages = settingKey[Seq[String]]("Extra JDK packages for framework classpath")
    lazy val osgiNamePrefix = settingKey[String]("Prefix for generated bundle names")

    lazy val osgiDevManifest = taskKey[BundleLocation]("Generate dev bundle")

    lazy val osgiBundles = taskKey[Seq[BundleLocation]]("OSGi bundle locations")
    lazy val osgiRequiredBundles = taskKey[Seq[BundleLocation]]("OSGi bundles required for running")

    lazy val osgiRunLevels = settingKey[Map[Int, Seq[BundleRequirement]]]("OSGi run level configuration")
    lazy val osgiRunFrameworkLevel = settingKey[Int]("OSGi framework run level")
    lazy val osgiRunDefaultLevel = settingKey[Int]("OSGi default run level")

    lazy val osgiStartConfig = taskKey[BundleStartConfig]("OSGi framework start configuration")

    lazy val osgiDeploy = taskKey[(File, ForkOptions, Seq[String])]("Deploy an OSGi launcher to directory")

    lazy val osgiShow = taskKey[Unit]("Show OSGi runner config")

    lazy val DeployLauncher = config("deployLauncher")


    def manifestOnly(symbolicName: String, version: String, headers: Map[String, String]) =
      ManifestOnly(None, symbolicName, new Version(version), headers)

    def packageReqs(name: String*) = name.map(n => PackageRequirement(n)).toSeq

    def bundleReqs(name: String*) = name.map(n => BundleRequirement(n)).toSeq

    def bundleReq(name: String, version: Option[String]) = BundleRequirement(name, version.map(new VersionRange(_)))

    def packageReq(name: String, version: Option[String]) = PackageRequirement(name, version.map(new VersionRange(_)))

    def fragmentsFor(name: String) = FragmentsRequirement(name)

    import OsgiTasks._

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
      artifactPath in osgiRepositories := target.value,
      osgiRepositoryConfigurations := configurationFilter(Compile.name),
      osgiRepositoryRules := Seq.empty,
      osgiRepoAdmin <<= repoAdminTaskRunner,
      osgiRepositories <<= cachedRepoLookupTask,
      osgiNamePrefix := name.value + "."
    )

    def bundleSettings(repositoryProject: ProjectReference) = defaultOsgiSettings ++ Seq(
      osgiDependencies in Compile := Seq.empty,
      osgiDependencies in Test := Seq.empty,
      osgiRepositoryRules := Seq.empty,
      osgiDependencyClasspath in Compile <<= osgiDependencyClasspathTask(Compile),
      osgiDependencyClasspath in Test <<= osgiDependencyClasspathTask(Test),
      unmanagedClasspath in Compile ++= {
        scalaInstance.value.allJars().toSeq.classpath ++
          (osgiDependencyClasspath in Compile).all(ScopeFilter(inDependencies(ThisProject, true, false))).value.flatten ++ (osgiDependencyClasspath in Compile).value
      },
      unmanagedClasspath in Test <++= osgiDependencyClasspath in Test,
      osgiRepoAdmin <<= repoAdminTaskRunner,
      osgiDevManifest <<= devManifestTask,
      jarCacheKey in Global <<= jarCacheTask,
      managedClasspath in Compile := Seq(),
      osgiRepositories := (osgiRepositories in repositoryProject).value)

    def repositoryAndRunnerSettings(prjs: ProjectReference*) = repositorySettings ++ runnerSettings(ThisProject, ScopeFilter(inProjects(prjs: _*)))

    def runnerSettings(repositoryProject: ProjectReference, bundlesScope: ScopeFilter, deploying: Boolean = true) = Seq(
      osgiShow <<= showStartup(ThisScope.in(run.key)),
      osgiRepositories in run := (osgiRepositories in Compile).value :+ osgiApplicationRepos(ThisScope.in(run.key)).value,
      osgiBundles in run := osgiDevManifest.all(bundlesScope).value,
      osgiRunDefaultLevel := 1,
      osgiRunFrameworkLevel := 1,
      osgiDependencies in run := Seq.empty,
      osgiRunLevels := Map.empty,
      osgiRequiredBundles in run := (osgiBundles in run).value,
      osgiStartConfig in run <<= osgiStartConfigTask(ThisScope.in(run.key)),
      run <<= osgiRunTask
    ) ++ (if (deploying) inConfig(DeployLauncher)(Defaults.configSettings ++ Seq(
      osgiRepositories := (osgiRepositories in Compile).value :+ osgiApplicationRepos(ThisScope in DeployLauncher).value,
      osgiDependencies := (osgiDependencies in run).value,
      osgiBundles := bundle.all(bundlesScope).value.map(BundleLocation.apply),
      osgiRequiredBundles := (osgiBundles in DeployLauncher).value,
      osgiStartConfig <<= osgiStartConfigTask(ThisScope in DeployLauncher),
      artifact in packageBin := artifact.value.copy(`type` = "zip", extension = "zip"),
      packageBin <<= packageDeploymentTask)) ++
      Seq(
        artifactPath in osgiDeploy := target.value / "launcher",
        osgiDeploy <<= osgiDeployTask
      )
    else Seq.empty)
  }
}
