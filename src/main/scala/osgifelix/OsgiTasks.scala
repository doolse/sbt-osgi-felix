package osgifelix

import java.io.File

import aQute.bnd.version.Version
import com.typesafe.sbt.osgi.OsgiKeys._
import com.typesafe.sbt.osgi.OsgiManifestHeaders
import org.apache.felix.bundlerepository.{RepositoryAdmin, Reason, Repository}
import aQute.bnd.osgi.{Analyzer, Jar, Builder}
import aQute.bnd.osgi.Constants._
import java.util.Properties
import org.osgi.framework.launch.Framework
import sbt._
import scalaz.Id.Id
import Keys._
import osgifelix.OsgiFelixPlugin.autoImport._
import osgifelix.OsgiFelixPlugin.jarCacheKey

import scalaz.Memo

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

  lazy val jarCacheTask = Def.task {
    Memo.immutableHashMapMemo[File, Jar](new Jar(_))
  }

  def bundleTask(
                  headers: OsgiManifestHeaders,
                  additionalHeaders: Map[String, String],
                  fullClasspath: Seq[Attributed[File]],
                  classesDir: File,
                  resourceDirectories: Seq[File],
                  embeddedJars: Seq[File],
                  jarCache: File => Jar,
                  streams: TaskStreams): java.util.jar.Manifest = {
    val builder = new Analyzer
    builder.setJar(classesDir)
    fullClasspath.map { jc =>
      jarCache(jc.data)
    }.foreach(builder.addClasspath)
    builder.setProperties(headersToProperties(headers, additionalHeaders))
    includeResourceProperty(resourceDirectories.filter(_.exists), embeddedJars) foreach (dirs =>
      builder.setProperty(INCLUDERESOURCE, dirs)
      )
    bundleClasspathProperty(embeddedJars) foreach (jars =>
      builder.setProperty(BUNDLE_CLASSPATH, jars)
      )
    builder.calcManifest()
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

  val devManifestTask = Def.task {
    (fullClasspath in Compile).value
    val classesDir = (classDirectory in Compile).value
    val manifestDir = classesDir / "META-INF"
    IO.createDirectory(manifestDir)
    val manifestFile = manifestDir / "MANIFEST.MF"
    val headers = manifestHeaders.value
    val addHeaders = additionalHeaders.value
    val cp = (dependencyClasspath in Compile).value
    val nameStr = name.value
    streams.value.log.info("Writing manifest for " + nameStr)
    val manifest = OsgiTasks.bundleTask(headers, addHeaders, cp, classesDir,
      (resourceDirectories in Compile).value, embeddedJars.value, (jarCacheKey in Global).value, streams.value)
    Using.fileOutputStream()(manifestFile) {
      manifest.write
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

  lazy val cachedRepoLookupTask = Def.taskDyn[Seq[Repository]] {
    val instructions = osgiRepositoryInstructions.value
    val obrDir = (artifactPath in osgiRepositories).value
    val cacheFile = obrDir / "bundle.cache"
    val binDir = obrDir / "bundles"
    val indexFile = obrDir / "index.xml"
    val cacheData = BndManifestGenerator.serialize(instructions)
    if (cacheFile.exists() && indexFile.exists() && IO.read(cacheFile) == cacheData) {
      Def.task {
        Seq(osgiRepoAdmin.value(_.loadRepository(indexFile)))
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
          Seq(repo)
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
    val rules = osgiRepositoryRules.value
    val pfx = osgiNamePrefix.value
    val (unused, insts) = OsgiTasks.convertToInstructions(pfx, artifacts, rules)
    if (unused.nonEmpty) {
      val logger = streams.value.log
      unused.foreach { r =>
        logger.warn(s"OSGi repository rule '${r}' is not used")
      }
    }
    insts
  }

  def osgiDependencyClasspathTask(config: ConfigKey) = Def.task[Classpath] {
    val repos = (osgiRepositories in config).value
    val deps = (osgiDependencies in config).value
    osgiRepoAdmin.value { repoAdmin =>
      repoAdmin.resolveRequirements(repos, deps)
    }.map(_.map(_.bl.file).classpath) valueOr { reasons =>
      writeErrors(reasons, streams.value.log)
      sys.error("Error looking up dependencies")
    }
  }

  def osgiApplicationRepos(bundleScope: Scope) = Def.task[Repository] {
    val runner = osgiRepoAdmin.value
    val bundles = (osgiBundles in bundleScope).value
    runner {
      ra => ra.createRepository(bundles)
    }
  }

  def osgiStartConfigTask(bundleScope: Scope) = Def.task[BundleStartConfig] {
    val reqBundles = (osgiRequiredBundles in bundleScope).value
    val runner = osgiRepoAdmin.value
    val repos = (osgiRepositories in bundleScope).value
    runner { ra =>
      val requirements = (osgiDependencies in bundleScope).value

      val startConfig = ra.resolveStartConfig(repos, reqBundles, requirements, osgiRunLevels.value, osgiRunDefaultLevel.value).valueOr { e =>
        writeErrors(e, streams.value.log)
        sys.error("Failed to lookup start config")
      }
      startConfig.copy(frameworkStartLevel = osgiRunFrameworkLevel.value)
    }
  }

  lazy val osgiRunTask = Def.inputTask[Unit] {
      val log = (streams in run).value.log
      val startConfig = (osgiStartConfig in run).value
      val props = (envVars in run).value
      props.foreach {
        case (n, v) => System.setProperty(n, v)
      }

      log.info("Launching embedded OSGi framework...")
      FelixRunner.embed(startConfig, IO.createTemporaryDirectory) {
      log.info("Waiting for framework to stop")
        _.getBundle(0).adapt(classOf[Framework]).waitForStop(0L)
      }
  }

  lazy val osgiDeployTask = Def.task[(File, ForkOptions, Seq[String])] {
    val config = (osgiStartConfig in DeployLauncher).value
    val dir = (artifactPath in osgiDeploy).value
    val (ops, cmdLine) = FelixRunner.writeLauncher(config, dir)
    (dir, ops, cmdLine)
  }

  lazy val packageDeploymentTask = Def.task[File] {
    val zipFile = (artifactPath in (DeployLauncher, packageBin)).value
    val (dir, _, _) = osgiDeploy.value
    val files = (dir ***) pair(relativeTo(dir), false)
    IO.zip(files, zipFile)
    zipFile
  }

  def showStartup(scope: Scope) = Def.task[Unit] {
    val startConfig = (osgiStartConfig in scope).value
    val log = streams.value.log
    startConfig.start.toSeq.sortBy(_._1).foreach {
    case (runLevel,bundles) =>
     val resources = osgiRepoAdmin.value { repoAdmin =>
      repoAdmin.resourcesFromLocations(bundles.map(_.bl))
    }
    log.info(runLevel.toString)
    resources.sortBy(_.getSymbolicName).map(r => s" ${r.getSymbolicName}").foreach(m => log.info(m))
    }
  }

}