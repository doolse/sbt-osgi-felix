package osgifelix

import java.io.File

import com.typesafe.sbt.osgi.OsgiKeys._
import com.typesafe.sbt.osgi.OsgiManifestHeaders
import org.apache.felix.bundlerepository.{RepositoryAdmin, Reason, Repository}
import sbt._
import aQute.bnd.osgi.{Jar, Builder}
import aQute.bnd.osgi.Constants._
import java.util.Properties
import sbt._
import sbt.Keys._
import scala.collection.JavaConversions._
import scalaz.Id.Id
import Keys._

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
    val (u, _, insts) = configReport.foldLeft((unusedFilters, filters, Seq.empty[BundleInstructions])) {
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
            case (newFilters, (_, Some((matched, replacements)))) => newFilters.filter(_ != matched) ++ replacements
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

  val depsWithClasses = Def.task {
    val cp = (dependencyClasspath in Compile).value
    val classes = (classDirectory in Compile).value
    cp ++ Seq(classes).classpath
  }

  val writeManifest = Def.task {
    (compile in Compile).value
    streams.value.log.info("Doing the manifest")
    val manifestDir = (resourceManaged in Compile).value / "META-INF"
    IO.createDirectory(manifestDir)
    val manifestFile = manifestDir / "MANIFEST.MF"
    val headers = manifestHeaders.value
    val addHeaders = additionalHeaders.value
    val cp = depsWithClasses.value
    val jar = OsgiTasks.bundleTask(headers, addHeaders, cp, (artifactPath in(Compile, packageBin)).value,
      (resourceDirectories in Compile).value, embeddedJars.value, streams.value)
    Using.fileOutputStream()(manifestFile) { fo =>
      jar.getManifest.write(fo)
    }
    Seq(manifestFile)
  }

  lazy val OsgiConfig = config("osgi")

  lazy val repoAdminTask = Def.task {
    val storage = target.value / "repo-admin"
    val file = IO.classLocationFile(classOf[RepositoryAdmin])
    FelixRepositories.runRepoAdmin(Seq(file), storage)
  }

  def writeErrors(reasons: Array[Reason], logger: Logger) = {
    reasons.foreach {
      r => logger.error {
        val req = r.getRequirement
        s"Failed to find ${req.getName} with ${req.getFilter} for ${r.getResource.getSymbolicName} "
      }
    }
  }

  lazy val cachedRepoLookup = Def.taskDyn[Repository] {
    val instructions = osgiInstructions.value
    val cacheFile = target.value / "bundle.cache"
    val binDir = target.value / "bundles"
    val indexFile = target.value / "index.xml"
    val cacheData = BndManifestGenerator.serialize(instructions)
    if (cacheFile.exists() && indexFile.exists() && IO.read(cacheFile) == cacheData) {
      Def.task {
        repoAdminTask.value(_.loadRepository(indexFile))
      }
    } else Def.task {
      IO.delete(binDir)
      IO.delete(cacheFile)
      IO.createDirectory(binDir)
      val logger = streams.value.log
      val builder = BndManifestGenerator.buildJars[Id](instructions, binDir)
      val jars = builder.run(new BundleProgress[Id] {
        override def info(msg: String): Id[Unit] = {
          logger.info(msg)
        }
      }).map(_.jf).toSeq
      repoAdminTask.value { repoAdmin =>
        val repo = repoAdmin.createIndexedRepository(jars.map(BundleLocation.apply))
        val reasons = repoAdmin.checkConsistency(repo)
        if (reasons.isEmpty)
        {
          IO.write(cacheFile, cacheData)
          repoAdmin.writeRepo(repo, indexFile)
          repo
        } else {
          writeErrors(reasons, logger)
          sys.error("Failed consistency check")
        }
      }
    }
  }

  lazy val createInstructions = Def.task {
    val ordered = SbtUtils.orderedDependencies(originalUpdate.value.configuration(OsgiConfig.name).get)
    val typeFilter: NameFilter = "jar" | "bundle"
    val artifacts = ordered.flatMap { mr =>
      mr.artifacts.collectFirst {
        case (artifact, file) if typeFilter.accept(artifact.`type`) => (mr.module, artifact, file)
      }
    }
    val rules = osgiFilterRules.value
    val pfx = osgiPrefix.value
    val (unused, insts) = OsgiTasks.convertToInstructions(pfx, artifacts, rules)
    if (unused.nonEmpty) {
      val logger = streams.value.log
      unused.foreach { r =>
        logger.warn(s"OSGi filter rule '${r}' is not used")
      }
    }
    insts
  }

  lazy val osgiUpdateReport = Def.task {
    val updateReport = originalUpdate.value
    val cached = target.value / "update.cache"
    val repo = osgiRepository.value
    val deps = osgiDependencies.value
    val resolved = repoAdminTask.value { repoAdmin =>
      repoAdmin.resolveRequirements(Seq(repo), deps)
    }.leftMap {
      writeErrors(_, streams.value.log)
    }.getOrElse(Seq.empty)
    val osgiModules = resolved.map { bl =>
      val f = bl.file
      val mr = ModuleReport("osgi" % f.getName % "1.0", Seq(Artifact(f.getName) -> f), Seq.empty)
      OrganizationArtifactReport("osgi", f.getName, Seq(mr))
    }
    val configReport = updateReport.configuration("scala-tool").get
    val allNewModules = osgiModules.flatMap(_.modules) ++ configReport.modules
    val allOrgReportts = configReport.details ++ osgiModules
    val newConfig = new ConfigurationReport("compile", allNewModules, allOrgReportts, Seq.empty)
    val newConfigs = Seq(newConfig) ++ (updateReport.configurations.filter(r => !Set("compile", "runtime", "compile-internal", "runtime-internal").contains(r.configuration)))
    new UpdateReport(cached, newConfigs, new UpdateStats(0, 0, 0, false), Map.empty)
  }
}