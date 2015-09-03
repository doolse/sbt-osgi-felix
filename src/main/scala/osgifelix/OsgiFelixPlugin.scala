package osgifelix

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

  object autoImport extends InstructionFilters {
    lazy val osgiRepository = taskKey[Repository]("Repository for resolving OSGi dependencies")
    lazy val osgiRepositoryConfigurations = settingKey[ConfigurationFilter]("Ivy configurations to include in OBR repository")
    lazy val osgiRepositoryRules = settingKey[Seq[InstructionFilter]]("Filters for generating BND instructions")
    lazy val osgiRepositoryInstructions = taskKey[Seq[BundleInstructions]]("Instructions for building the bundles in the OBR repository")
    lazy val osgiRepoAdmin = settingKey[FelixRepoRunner]("Repository admin interface")
    lazy val osgiExtraJDKPackages = settingKey[Seq[String]]("Extra JDK packages for framework classpath")
    lazy val osgiNamePrefix = settingKey[String]("Prefix for generated bundle names")

    lazy val osgiDependencies = settingKey[Seq[OsgiRequirement]]("OSGi dependencies")
    lazy val osgiDependencyClasspath = taskKey[Classpath]("Classpath from OSGi dependencies")
    lazy val osgiDevManifest = taskKey[BundleLocation]("Generate dev bundle")

    lazy val osgiBundles = taskKey[Seq[BundleLocation]]("OSGi bundle locations")
    lazy val osgiRequiredBundles = taskKey[Seq[BundleLocation]]("OSGi bundles required for running")

    lazy val osgiRunLevels = settingKey[Map[Int, Seq[BundleRequirement]]]("OSGi run level configuration")
    lazy val osgiRunFrameworkLevel = settingKey[Int]("OSGi framework run level")
    lazy val osgiRunDefaultLevel = settingKey[Int]("OSGi default run level")
    lazy val osgiRunRequirements = settingKey[Seq[OsgiRequirement]]("OSGi runtime resolver requirements")

    lazy val osgiStartConfig = taskKey[BundleStartConfig]("OSGi framework start configuration")

    lazy val osgiDeploy = taskKey[(File, ForkOptions, Seq[String])]("Deploy an OSGi launcher to directory")

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
      runnerSettings(ThisProject, ScopeFilter(inProjects(ThisProject)), true) ++ defaultOsgiSettings

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
      artifactPath in osgiRepository <<= target,
      osgiRepositoryConfigurations := configurationFilter(Compile.name),
      osgiRepoAdmin <<= repoAdminTaskRunner,
      osgiRepository <<= cachedRepoLookupTask,
      osgiNamePrefix := name.value + "."
    )

    def bundleSettings(repositoryProject: ProjectReference) = Seq(
      osgiDependencies := Seq.empty,
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
      managedClasspath in Compile := Seq(),
      osgiRepository := (osgiRepository in repositoryProject).value)

    def runnerSettings(repositoryProject: ProjectReference, bundlesScope: ScopeFilter, launching: Boolean) = Seq(
      osgiRepository := (osgiRepository in repositoryProject).value,
      osgiBundles in run := osgiDevManifest.all(bundlesScope).value,
      osgiRunDefaultLevel := 1,
      osgiRunFrameworkLevel := 1,
      osgiRunRequirements := Seq.empty,
      osgiRunLevels := Map.empty,
      osgiRequiredBundles in run <<= osgiBundles in run,
      osgiStartConfig in run <<= osgiStartConfigTask(ThisScope.in(run.key)),
      run <<= osgiRunTask
    ) ++ (if (launching) inConfig(DeployLauncher)(Defaults.configSettings ++ Seq(
      osgiBundles := bundle.all(bundlesScope).value.map(BundleLocation.apply),
      osgiRequiredBundles <<= osgiBundles in DeployLauncher,
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
