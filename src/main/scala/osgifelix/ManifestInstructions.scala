package osgifelix

import java.io.File

import aQute.bnd.osgi.Jar

object ManifestInstructions {
  val Default = ManifestInstructions()
}

case class ManifestInstructions(imports: String = "*", exports: String = "*;version=VERSION", privates: String = "",
                                version: String = "", fragment: Option[String] = None, symbolicName: String = "",
                                extraProperties: Map[String, String] = Map.empty)

sealed trait BundleInstructions
{
  def sources: Iterable[File]
}

case class RewriteManifest(jar: File, sources: Iterable[File], name: String, jarVersion: Option[String], instructions: ManifestInstructions)  extends BundleInstructions
case class CreateBundle(jars: Iterable[File], sources: Iterable[File], instructions: ManifestInstructions)  extends BundleInstructions
case class CopyBundle(jar: File, sources: Iterable[File]) extends BundleInstructions
case class EditManifest(jar: File, sources: Iterable[File], edits: java.util.jar.Manifest => Boolean) extends BundleInstructions

case class ProcessedJar(bundleId: Option[(String, String)], jar: Jar, featureId: String = "")
