package osgifelix

import java.io.File

import com.typesafe.sbt.osgi.OsgiManifestHeaders
import sbt._
import aQute.bnd.osgi.{Jar, Builder}
import aQute.bnd.osgi.Constants._
import java.util.Properties
import sbt._
import sbt.Keys._
import scala.collection.JavaConversions._

/**
 * Created by jolz on 13/08/15.
 */
object OsgiTasks {

  def convertToInstructions(prefix: String, configReport: Seq[(ModuleID, Artifact, File)], filters: Seq[InstructionFilter]): (Set[InstructionFilter], Seq[BundleInstructions]) = {

    def autoDetect(mid: ModuleID, file: File) = {
      val (jar, nameVer) = BndManifestGenerator.parseDetails(file)
      if (nameVer.isDefined) UseBundle(Some(mid), file, jar)
      else RewriteManifest(Some(mid), jar, prefix + mid.name, SbtUtils.convertRevision(mid.revision), ManifestInstructions.Default)
    }
    val unusedFilters = filters.toSet
    val (u, _, insts) = configReport.foldLeft((unusedFilters, unusedFilters, Seq.empty[BundleInstructions])) {
      case ((unused, filters, instructions), (mid, art, file)) =>
        val matchedFilters = filters.filter(_.filter("", mid, art))
        val (newInsts, nextFilters) = if (matchedFilters.isEmpty) {
          (Seq(autoDetect(mid, file)), filters)
        } else {
          val changes = matchedFilters collect {
            case RewriteFilter(_, _, instructions, nameO, versionO) =>
              val (jar, nameVer) = BndManifestGenerator.parseDetails(file)
              val name = nameO.orElse(nameVer.map(_._1)).getOrElse(prefix + mid.name)
              val version = versionO.orElse(nameVer.map(_._2)).getOrElse(SbtUtils.convertRevision(mid.revision))
              (Seq(RewriteManifest(Some(mid), jar, name, version, instructions)), None)
            case f@CreateFilter(_, filter, name, version, instructions, processDefault) =>
              val allFiles = configReport.collect {
                case (mid, art, file) if filter("", mid, art) => (file, IgnoreFilter("generated", filter = moduleFilter(organization = mid.organization, name = mid.name, revision = mid.revision)))
              }
              val optionalDefault = if (processDefault) Seq(autoDetect(mid, file)) else Nil
              (optionalDefault :+ CreateBundle(Seq(mid), allFiles.map(_._1), name, version, instructions), Some(f, allFiles.map(_._2)))
          }
          (changes.flatMap(_._1), changes.foldLeft(filters) {
            case (newFilters, (_, Some((matched, replacements)))) => (newFilters - matched) ++ replacements
            case (nf, _) => nf
          })
        }
        (unused -- matchedFilters, nextFilters, instructions ++ newInsts)
    }
    (u, insts)
  }

  def bundleTask(
                  headers: OsgiManifestHeaders,
                  additionalHeaders: Map[String, String],
                  fullClasspath: Seq[Attributed[File]],
                  artifactPath: File,
                  resourceDirectories: Seq[File],
                  embeddedJars: Seq[File],
                  streams: TaskStreams): Jar = {
    val builder = new Builder
    builder.setClasspath(fullClasspath map (_.data) toArray)
    builder.setProperties(headersToProperties(headers, additionalHeaders))
    includeResourceProperty(resourceDirectories.filter(_.exists), embeddedJars) foreach (dirs =>
      builder.setProperty(INCLUDERESOURCE, dirs)
      )
    bundleClasspathProperty(embeddedJars) foreach (jars =>
      builder.setProperty(BUNDLE_CLASSPATH, jars)
      )
    // Write to a temporary file to prevent trying to simultaneously read from and write to the
    // same jar file in exportJars mode (which causes a NullPointerException).
    val tmpArtifactPath = file(artifactPath.absolutePath + ".tmp")
    // builder.build is not thread-safe because it uses a static SimpleDateFormat.  This ensures
    // that all calls to builder.build are serialized.
    val jar = synchronized {
      builder.build
    }
    jar
  }

  def headersToProperties(headers: OsgiManifestHeaders, additionalHeaders: Map[String, String]): Properties = {
    import headers._
    val properties = new Properties
    properties.put(BUNDLE_SYMBOLICNAME, bundleSymbolicName)
    properties.put(BUNDLE_VERSION, bundleVersion)
    bundleActivator foreach (properties.put(BUNDLE_ACTIVATOR, _))
    seqToStrOpt(dynamicImportPackage)(id) foreach (properties.put(DYNAMICIMPORT_PACKAGE, _))
    seqToStrOpt(exportPackage)(id) foreach (properties.put(EXPORT_PACKAGE, _))
    seqToStrOpt(importPackage)(id) foreach (properties.put(IMPORT_PACKAGE, _))
    fragmentHost foreach (properties.put(FRAGMENT_HOST, _))
    seqToStrOpt(privatePackage)(id) foreach (properties.put(PRIVATE_PACKAGE, _))
    seqToStrOpt(requireBundle)(id) foreach (properties.put(REQUIRE_BUNDLE, _))
    additionalHeaders foreach { case (k, v) => properties.put(k, v) }
    properties
  }

  def seqToStrOpt[A](seq: Seq[A])(f: A => String): Option[String] =
    if (seq.isEmpty) None else Some(seq map f mkString ",")

  def includeResourceProperty(resourceDirectories: Seq[File], embeddedJars: Seq[File]) =
    seqToStrOpt(resourceDirectories ++ embeddedJars)(_.getAbsolutePath)

  def bundleClasspathProperty(embeddedJars: Seq[File]) =
    seqToStrOpt(embeddedJars)(_.getName) map (".," + _)

  def defaultBundleSymbolicName(organization: String, name: String): String = {
    val organizationParts = parts(organization)
    val nameParts = parts(name)
    val partsWithoutOverlap = (organizationParts.lastOption, nameParts.headOption) match {
      case (Some(last), Some(head)) if (last == head) => organizationParts ++ nameParts.tail
      case _ => organizationParts ++ nameParts
    }
    partsWithoutOverlap mkString "."
  }

  def id(s: String) = s

  def parts(s: String) = s split "[.-]" filterNot (_.isEmpty)

}