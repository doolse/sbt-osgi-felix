package osgifelix

import java.io.File

import aQute.bnd.version.Version
import com.typesafe.sbt.osgi.OsgiKeys._
import com.typesafe.sbt.osgi.OsgiManifestHeaders
import org.apache.felix.bundlerepository.{RepositoryAdmin, Reason, Repository}
import aQute.bnd.osgi.{Jar, Builder}
import aQute.bnd.osgi.Constants._
import java.util.Properties
import org.osgi.framework.launch.Framework
import sbt._
import scalaz.Id.Id
import Keys._
import OsgiFelixPlugin.autoImport._

/**
 * Created by jolz on 13/08/15.
 */
object OsgiTasks {

  def convertToInstructions(prefix: String, configReport: Seq[(ModuleID, Artifact, File)], filters: Seq[InstructionFilter]): (Set[InstructionFilter], Seq[BundleInstructions]) = {

    def autoDetect(mid: ModuleID, file: File) = {
      val (jar, nameVer) = BndManifestGenerator.parseDetails(file)
      if (nameVer.isDefined) UseBundle(Some(mid), file, jar)
      else RewriteManifest(Some(mid), jar, prefix + mid.name, convertRevision(mid.revision), ManifestInstructions.Default)
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
              val version = versionO.orElse(nameVer.map(_._2)).getOrElse(convertRevision(mid.revision))
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

  val devManifestTask = Def.task {
    (fullClasspath in Compile).value
    val classesDir = (classDirectory in Compile).value
    val manifestDir = classesDir / "META-INF"
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
    BundleLocation(classesDir)
  }

  lazy val repoAdminTaskRunner = Def.setting {
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

  lazy val cachedRepoLookupTask = Def.taskDyn[Repository] {
    val instructions = osgiInstructions.value
    val obrDir = osgiRepositoryDir.value
    val cacheFile = obrDir / "bundle.cache"
    val binDir = obrDir / "bundles"
    val indexFile = obrDir / "index.xml"
    val cacheData = BndManifestGenerator.serialize(instructions)
    if (cacheFile.exists() && indexFile.exists() && IO.read(cacheFile) == cacheData) {
      Def.task {
        osgiRepoAdmin.value(_.loadRepository(indexFile))
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
      osgiRepoAdmin.value { repoAdmin =>
        val repo = repoAdmin.createRepository(jars.map(BundleLocation.apply))
        val reasons = repoAdmin.checkConsistency(repo)
        if (reasons.isEmpty)
        {
          IO.write(cacheFile, cacheData)
          repoAdmin.writeRepository(repo, indexFile)
          repo
        } else {
          writeErrors(reasons, logger)
          sys.error("Failed consistency check")
        }
      }
    }
  }

  def convertRevision(revision: String): Version = {
    VersionNumber(revision) match {
      case VersionNumber((nums, _, _)) => new Version(nums.slice(0, 3).padTo(3, 0).mkString(".") + ".SNAPSHOT")
    }
  }

  def orderedDependencies(modList: Seq[ModuleReport]): List[ModuleReport] = {
    val modMap = modList.map(m ⇒ (m.module, m)).toMap
    val ordered = Dag.topologicalSort(modList.map(_.module))(m ⇒ modMap.get(m).map(_.callers.map(_.caller)).getOrElse(Seq.empty)).reverse
    ordered.flatMap(modMap.get)
  }


  lazy val createInstructionsTask = Def.task {
    val configFilter = osgiRepositoryConfigurations.value
    val report = update.value
    val ordered = orderedDependencies(report.configurations.flatMap { cr =>
      if (configFilter(cr.configuration)) cr.modules else Nil
    })
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

  lazy val osgiDependencyClasspathTask = Def.task[Classpath] {
    val repo = osgiRepository.value
    val deps = osgiDependencies.value
    osgiRepoAdmin.value { repoAdmin =>
      repoAdmin.resolveRequirements(Seq(repo), deps)
    }.map(_.map(_.file).classpath) valueOr { reasons =>
      writeErrors(reasons, streams.value.log)
      sys.error("Error looking up dependencies")
    }
  }

  lazy val osgiRunTask = Def.inputTask[Unit] {
    val devBundles = osgiRunBundles.value

    val runner = osgiRepoAdmin.value
    val libraryRepo = osgiRepository.value
    runner { ra =>
      val devRepo = ra.createRepository(devBundles)
      val requirements = osgiRunRequirements.value

      val startConfig = ra.resolveStartConfig(Seq(devRepo, libraryRepo), devBundles, requirements, osgiRunLevels.value).valueOr { e =>
        writeErrors(e, streams.value.log)
        sys.error("Failed to lookup run config")
      }
      val props = (envVars in run).value
      props.foreach {
        case (n, v) => System.setProperty(n, v)
      }

      FelixRunner.embed(startConfig.copy(frameworkLevel = osgiRunFrameworkLevel.value, defaultLevel = osgiRunDefaultLevel.value), IO.createTemporaryDirectory) {
        _.getBundle(0).adapt(classOf[Framework]).waitForStop(0L)
      }
    }
  }

}