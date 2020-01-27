package osgifelix

import java.io.File
import java.net.URI

import aQute.bnd.version.Version
import com.typesafe.sbt.osgi.OsgiKeys._
import com.typesafe.sbt.osgi.OsgiManifestHeaders
import org.apache.felix.bundlerepository.{Resource, RepositoryAdmin, Reason, Repository}
import aQute.bnd.osgi.{Analyzer, Jar}
import aQute.bnd.osgi.Constants._
import java.util.Properties
import org.osgi.framework.launch.Framework
import sbt._
import sbt.complete.DefaultParsers.spaceDelimited
import scalaz.Id.Id
import Keys._
import osgifelix.OsgiFelixPlugin.autoImport._
import osgifelix.OsgiFelixPlugin.jarCacheKey
import sbt.io.Using
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
        val matchedFilters = filters.filter(_.filter(ConfigRef(""), mid, art))
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
                case (mid, art, file) if filter(ConfigRef(""), mid, art) => (file, IgnoreFilter("generated", filter = moduleFilter(organization = mid.organization, name = mid.name, revision = mid.revision)))
              }
              val optionalDefault = if (processDefault) Seq(autoDetect(mid, file)) else Nil
              (optionalDefault :+ CreateBundle(List(mid), allFiles.toList.map(_._1), name, version, instructions), Some(f, allFiles.map(_._2)))
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
    Memo.weakHashMapMemo[File, Jar](new Jar(_))
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
    (Compile / fullClasspath).value
    val classesDir = (Compile / classDirectory).value
    val manifestDir = classesDir / "META-INF"
    IO.createDirectory(manifestDir)
    val manifestFile = manifestDir / "MANIFEST.MF"
    val headers = manifestHeaders.value
    val addHeaders = additionalHeaders.value
    val cp = (Compile / dependencyClasspath).value
    val nameStr = name.value
    streams.value.log.info("Writing manifest for " + nameStr)
    val manifest = OsgiTasks.bundleTask(headers, addHeaders, cp, classesDir,
      (Compile / resourceDirectories).value, embeddedJars.value, (Global / jarCacheKey).value, streams.value)
    Using.fileOutputStream()(manifestFile) {
      manifest.write
    }
    BundleLocation(classesDir)
  }

  lazy val repoAdminTaskRunner = Def.task {
    val storage = IO.temporaryDirectory / "felix-repo-admin"
    val file = IO.classLocationFile(classOf[RepositoryAdmin])
    FelixRepositories.createRunner(Seq(file), storage)
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
    val obrDir = (osgiRepositories / artifactPath).value
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
    val _artifacts = ordered.flatMap { mr =>
      mr.artifacts.collectFirst {
        case (artifact, file) if typeFilter.accept(artifact.`type`) => (mr.module, artifact, file)
      }
    }
    val rules = osgiRepositoryRules.value
    val pfx = osgiNamePrefix.value
    val uj = (Compile / unmanagedJars).value.seq.map(_.data)
    val artifacts = uj.map { f =>
      ("unmanaged" % f.getName % "1.0", Artifact(f.getName, "jar", "jar"), f)
    } ++ _artifacts
    val (unused, insts) = OsgiTasks.convertToInstructions(pfx, artifacts, rules)
    val logger = streams.value.log
    if (unused.nonEmpty) {
      unused.foreach { r =>
        logger.warn(s"OSGi repository rule '${r}' is not used")
      }
    }
    insts
  }

  def osgiDependencyClasspathTask(config: ConfigKey) = Def.task[Classpath] {
    val repos = (config / osgiRepositories).value
    val deps = (config / osgiDependencies).value
    val logger = streams.value.log
    osgiRepoAdmin.value { repoAdmin =>
      repoAdmin.resolveRequirements(repos, deps)
    }.map(_.map(_.bl.file).classpath) valueOr { reasons =>
      writeErrors(reasons, logger)
      sys.error("Error looking up dependencies")
    }
  }

  def osgiApplicationRepos(bundleScope: Scope) = Def.task[Repository] {
    val runner = osgiRepoAdmin.value
    val bundles = (bundleScope / osgiBundles).value
    runner { _.createRepository(bundles) }
  }

  def osgiStartConfigTask(bundleScope: Scope) = Def.task[BundleStartConfig] {
    val reqBundles = (bundleScope / osgiRequiredBundles).value
    val runner = osgiRepoAdmin.value
    val repos = (bundleScope / osgiRepositories).value
    val logger = streams.value.log
    runner { ra =>
      val requirements = (bundleScope / osgiDependencies).value

      val startConfig = ra.resolveStartConfig(repos, reqBundles, requirements, osgiRunLevels.value, osgiRunDefaultLevel.value).valueOr { e =>
        writeErrors(e, logger)
        sys.error("Failed to lookup start config")
      }
      startConfig.copy(frameworkStartLevel = osgiRunFrameworkLevel.value)
    }
  }

  lazy val osgiRunTask = Def.inputTask[Unit] {
      val log = (run / streams).value.log
      val startConfig = (run / osgiStartConfig).value
      val props = (run / envVars).value
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
    val config = (DeployLauncher / osgiStartConfig).value
    val dir = (osgiDeploy / artifactPath).value
    val (ops, cmdLine) = FelixRunner.writeLauncher(config, dir)
    (dir, ops, cmdLine)
  }

  lazy val packageDeploymentTask = Def.task[File] {
    val zipFile = (DeployLauncher / packageBin / artifactPath).value
    val (dir, _, _) = osgiDeploy.value
    val files = (dir.allPaths) pair(Path.relativeTo(dir), false)
    IO.zip(files, zipFile)
    zipFile
  }

  def showStartup(scope: Scope) = Def.task[Unit] {
    val startConfig = (scope / osgiStartConfig).value
    val log = streams.value.log
    startConfig.start.toSeq.sortBy(_._1).foreach {
      case (runLevel,bundles) =>
        val resources = bundles collect { case ResolvedBundleLocation(_, _, Some((r, _))) => r }
        log.info(runLevel.toString)
        resources.sortBy(_.getSymbolicName).map(r => s" ${r.getSymbolicName} [${r.getVersion}]").foreach(m => log.info(m))
    }
  }

  def showDependencies(scope: Scope) = Def.inputTask[Unit] {
    val pluginNames = spaceDelimited("<bundle names>").parsed
    val log = streams.value.log
    val startConfig = (scope / osgiStartConfig).value
    val bundleMap = startConfig.start.values.flatMap {
      bundles => bundles.collect {
        case bl@ResolvedBundleLocation(_, _, Some((res, reasons))) => (res.getSymbolicName, bl)
      }
    }.toMap

    def showDeps(bundles: Seq[ResolvedBundleLocation], seen: Set[String], level: Int):Unit = {
      val nextNames = bundles.flatMap {
        case ResolvedBundleLocation(_,_, Some((res, reasons))) => reasons.map(_.getResource.getSymbolicName)
      }.toSet diff seen
      val nextDeps = nextNames.collect {
        case bsn if bundleMap.contains(bsn) => bundleMap(bsn)
      }
      bundles.foreach {
        case ResolvedBundleLocation(_, _, Some((res, reasons))) =>
          val spaces = (0 until level).map(_ => ' ').mkString
          val bsn = res.getSymbolicName
          log.info(s"${spaces}${bsn}")
          reasons.foreach { r =>
            val req = r.getRequirement
            val depBsn = r.getResource.getSymbolicName
            if (bsn != depBsn)
            log.info(s"${spaces}- ${req.getFilter} for $depBsn")
          }
      }
      if (nextDeps.nonEmpty) showDeps(nextDeps.toSeq, seen ++ nextNames, level+1)
    }
    val topLevel = bundleMap.filterKeys(pluginNames.toSet).values.toSeq
    showDeps(topLevel, pluginNames.toSet, 0)
  }

  def packageObrTask(config: Configuration) = Def.task[File] {
    val thirdParty = (config / osgiRepositories).value
    val bundles = (config / osgiPackageOBR/ osgiBundles).value
    val dest = (config / osgiPackageOBR / artifactPath).value
    IO.delete(dest)
    val bundleDest = dest / "bundles"
    val destIndex = dest / "index.xml"
    val allJars = thirdParty.flatMap(_.getResources).map(r => new File(URI.create(r.getURI))) ++
                  bundles.map(_.file)
    val nonJars = allJars.filterNot(_.isFile)
    if (nonJars.nonEmpty) sys.error("The following locations are not jars:\n"+nonJars.mkString("\n"))
    val bls = allJars.map { jf =>
      val destJar = bundleDest / jf.getName
      IO.copyFile(jf, destJar)
      BundleLocation(destJar)
    }
    osgiRepoAdmin.value { repoAdmin =>
      val repo = repoAdmin.createRepository(bls)
      repoAdmin.writeRepository(repo, destIndex)
    }
    destIndex
  }
}