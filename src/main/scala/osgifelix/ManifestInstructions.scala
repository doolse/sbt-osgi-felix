package osgifelix

import java.io.File

import aQute.bnd.osgi.Jar
import aQute.bnd.version.Version

object ManifestInstructions {
  val Default = ManifestInstructions()
}

case class ManifestInstructions(imports: String = "*", exports: String = "*;version=VERSION",
                                privates: String = "", fragment: Option[String] = None,  extraProperties: Map[String, String] = Map.empty)

sealed trait BundleInstructions
{
  def sources: Iterable[File]
}

case class RewriteManifest(jar: Jar, symbolicName: String, version: Version, sources: Iterable[File], instructions: ManifestInstructions)  extends BundleInstructions
case class CreateBundle(jars: Iterable[File], symbolicName: String, version: Version, sources: Iterable[File], instructions: ManifestInstructions)  extends BundleInstructions
case class CopyBundle(jf: File, jar: Jar, sources: Iterable[File]) extends BundleInstructions
case class EditManifest(jar: File, sources: Iterable[File], edits: java.util.jar.Manifest => Boolean) extends BundleInstructions

case class ProcessedJar(bsn: String, version: Version, jar: Jar, jf: File, groupId: String = "")
