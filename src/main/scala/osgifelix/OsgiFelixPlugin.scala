package osgifelix

import aQute.bnd.version.Version
import com.typesafe.sbt.osgi.SbtOsgi.defaultOsgiSettings
import org.apache.felix.bundlerepository.Repository
import org.osgi.framework.VersionRange
import sbt.Keys._
import sbt._

/**
 * Created by jolz on 13/08/15.
 */
object OsgiFelixPlugin extends AutoPlugin {

  object autoImport extends InstructionFilters {
    lazy val osgiRepository = taskKey[Repository]("Repository for resolving osgi dependencies")
    lazy val osgiRepositoryDir = settingKey[File]("Directory to store created OBR repository")
    lazy val osgiRepositoryConfigurations = settingKey[ConfigurationFilter]("Configuration to include in OBR repository")
    lazy val osgiInstructions = taskKey[Seq[BundleInstructions]]("Instructions for BND")
    lazy val osgiDependencies = settingKey[Seq[OsgiRequirement]]("OSGi dependencies")
    lazy val osgiDependencyClasspath = taskKey[Classpath]("Classpath from OSGi dependencies")
    lazy val osgiFilterRules = settingKey[Seq[InstructionFilter]]("Filters for generating BND instructions")
    lazy val osgiPrefix = settingKey[String]("Prefix for generated bundle names")
    lazy val osgiExtraJDKPackages = settingKey[Seq[String]]("Extra JDK packages for framework classpath")
    lazy val osgiRepoAdmin = settingKey[FelixRepoRunner]("Repository admin interface")
    lazy val osgiDevManifest = taskKey[BundleLocation]("Generate dev bundle")

    lazy val osgiRunBundles = taskKey[Seq[BundleLocation]]("OSGi compile bundles")
    lazy val osgiRunLevels = settingKey[Map[Int, Seq[BundleRequirement]]]("OSGi run level configuration")
    lazy val osgiRunFrameworkLevel = settingKey[Int]("OSgi runner framework run level")
    lazy val osgiRunDefaultLevel = settingKey[Int]("OSgi runner default run level")
    lazy val osgiRunRequirements = settingKey[Seq[OsgiRequirement]]("OSGi runner resolver requirements")
    lazy val osgiRunInclusions = taskKey[Seq[BundleLocation]]("OSGi runner auto-started bundles")
    lazy val osgiRunEnvironment = taskKey[Map[String, String]]("OSGi runner environment variables")


    def manifestOnly(symbolicName: String, version: String, headers: Map[String, String]) =
      ManifestOnly(None, symbolicName, new Version(version), headers)

    def packageReqs(name: String*) = name.map(n => PackageRequirement(n)).toSeq

    def bundleReqs(name: String*) = name.map(n => BundleRequirement(n)).toSeq

    def bundleReq(name: String, version: Option[String] = None) = BundleRequirement(name, version.map(new VersionRange(_)))

    def packageReq(name: String, version: Option[String]) = PackageRequirement(name, version.map(new VersionRange(_)))

    def fragmentsFor(name: String) = FragmentsRequirement(name)

    import OsgiTasks._

    lazy val defaultSingleProjectSettings = repositorySettings ++ bundleSettings(ThisProject) ++ runnerSettings(ThisProject) ++ defaultOsgiSettings

    lazy val repositorySettings = Seq(
      osgiExtraJDKPackages := Seq("sun.reflect", "sun.reflect.generics.reflectiveObjects", "com.sun.jna", "com.sun", "sun.misc", "com.sun.jdi", "com.sun.jdi.connect",
        "com.sun.jdi.event", "com.sun.jdi.request", "sun.nio.ch", "com.sun.javadoc", "com.sun.tools.javadoc"),
      osgiInstructions := {
        val extra = osgiExtraJDKPackages.value
        val extBundle = if (extra.nonEmpty) {
          val bundleName = osgiPrefix.value + "jdkext"
          Seq(manifestOnly(bundleName, "1.0.0", Map(
            "Fragment-Host" -> "system.bundle; extension:=framework",
            "Export-Package" -> extra.mkString(",")
          )))
        } else Seq()
        extBundle ++ createInstructionsTask.value
      },
      osgiRepositoryDir <<= target,
      osgiRepositoryConfigurations := configurationFilter(Compile.name),
      osgiRepoAdmin <<= repoAdminTaskRunner,
      osgiRepository <<= cachedRepoLookupTask,
      osgiPrefix := name.value + "."
    )

    def bundleSettings(repositoryProject: ProjectReference) = Seq(
      osgiDependencies := Seq.empty,
      osgiFilterRules := Seq.empty,
      osgiRepoAdmin <<= repoAdminTaskRunner,
      osgiDevManifest <<= devManifestTask,
      osgiDependencyClasspath <<= osgiDependencyClasspathTask,
      (unmanagedClasspath in Compile) ++= {
        scalaInstance.value.allJars().toSeq.classpath ++
          osgiDependencyClasspath.all(ScopeFilter(inDependencies(ThisProject, true, false))).value.flatten ++ osgiDependencyClasspath.value
      },
      (managedClasspath in Compile) := Seq(),
      osgiRepository := (osgiRepository in repositoryProject).value)

    def runnerSettings(repositoryProject: ProjectReference) = Seq(
      osgiRepository := (osgiRepository in repositoryProject).value,
      osgiRunBundles := Seq(osgiDevManifest.value),
      osgiRunDefaultLevel := 1,
      osgiRunFrameworkLevel := 1,
      osgiRunRequirements := Seq.empty,
      osgiRunLevels := Map.empty,
      osgiRunInclusions <<= osgiRunBundles,
      osgiRunEnvironment := Map.empty,
      run <<= osgiRunTask
    )
  }

}
