package osgifelix

import java.io.File

import aQute.bnd.osgi.Jar
import aQute.bnd.version.Version
import sbt.ModuleID

object ManifestInstructions {
  val Default = ManifestInstructions()
}

case class ManifestInstructions(imports: String = "*", exports: String = "*;version=VERSION",
                                privates: String = "", fragment: Option[String] = None,  extraProperties: Map[String, String] = Map.empty)

object BundleInstructions
{
  def manifestOnly(symbolicName: String, version: String, headers: Map[String, String]) =
    ManifestOnly(None, symbolicName, new Version(version), headers)
}

sealed trait BundleInstructions

case class RewriteManifest(moduleId: Option[ModuleID], jar: Jar, symbolicName: String, version: Version, instructions: ManifestInstructions)  extends BundleInstructions
case class CreateBundle(moduleIds: Seq[ModuleID], jars: Iterable[File], symbolicName: String, version: Version, instructions: ManifestInstructions)  extends BundleInstructions
case class UseBundle(moduleId: Option[ModuleID], jf: File, jar: Jar) extends BundleInstructions
case class ManifestOnly(moduleId: Option[ModuleID], symbolicName: String, version: Version, headers: Map[String, String]) extends BundleInstructions

case class ProcessedJar(moduleIds: Seq[ModuleID], bsn: String, version: Version, jar: Jar, jf: File, groupId: String = "")

