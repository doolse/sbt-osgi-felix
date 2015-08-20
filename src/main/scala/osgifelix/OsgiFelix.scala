import aQute.bnd.version.Version
import org.apache.felix.bundlerepository.Repository
import org.osgi.framework.VersionRange
import sbt.Keys.Classpath
import sbt._

/**
 * Created by jolz on 13/08/15.
 */
package object osgifelix extends InstructionFilters {
  lazy val osgiRepository = taskKey[Repository]("Repository for resolving osgi dependencies")
  lazy val osgiRepositoryDir = settingKey[File]("Directory to store created OBR repository")
  lazy val osgiRepositoryConfigurations = settingKey[ConfigurationFilter]("Configuration to include in OBR repository")
  lazy val osgiInstructions = taskKey[Seq[BundleInstructions]]("Instructions for BND")
  lazy val osgiDependencies = settingKey[Seq[OsgiRequirement]]("OSGi dependencies")
  lazy val osgiDependencyClasspath = taskKey[Classpath]("Classpath from OSGi dependencies")
  lazy val osgiFilterRules = settingKey[Seq[InstructionFilter]]("Filters for generating BND instructions")
  lazy val osgiPrefix = settingKey[String]("Prefix for generated bundle names")
  lazy val osgiRepoAdmin = settingKey[FelixRepoRunner]("Repository admin interface")
  lazy val osgiDevManifest = taskKey[BundleLocation]("Generate dev bundle")

  def manifestOnly(symbolicName: String, version: String, headers: Map[String, String]) =
    ManifestOnly(None, symbolicName, new Version(version), headers)

  def packageReqs(name: String*) = name.map(n => PackageRequirement(n)).toSeq
  def bundleReqs(name: String*) = name.map(n => BundleRequirement(n)).toSeq
  def bundleReq(name: String, version: Option[String] = None) = BundleRequirement(name, version.map(new VersionRange(_)))
  def packageReq(name: String, version: Option[String]) = PackageRequirement(name, version.map(new VersionRange(_)))
  def fragmentsFor(name: String) = FragmentsRequirement(name)

}
