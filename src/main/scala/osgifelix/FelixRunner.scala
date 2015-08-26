package osgifelix

import java.io.File
import java.net.URI
import java.util.Properties

import org.apache.felix.bundlerepository.Resource
import org.apache.felix.framework.Felix
import org.apache.felix.main.AutoProcessor
import org.osgi.framework.wiring.BundleRevision
import org.osgi.framework.{Constants, BundleContext}
import org.osgi.framework.startlevel.{BundleStartLevel, FrameworkStartLevel}
import sbt._

import scala.collection.JavaConverters._

/**
 * Created by jolz on 11/08/15.
 */

case class BundleStartConfig(started: Map[Int, Seq[ResolvedBundleLocation]] = Map.empty, installed: Map[Int, Seq[ResolvedBundleLocation]] = Map.empty,
                             defaultInstall: Seq[ResolvedBundleLocation] = Seq.empty, defaultStart: Seq[ResolvedBundleLocation] = Seq.empty,
                             systemPackages: Iterable[String] = Seq.empty, frameworkLevel: Int = 1, defaultLevel: Int = 1)

object FelixRunner {
  def embed[A](startConfig: BundleStartConfig, storageDir: File)(f: BundleContext => A): A = {
    val sysO = startConfig.systemPackages.headOption.map(_ => startConfig.systemPackages.mkString(","))
    val configMap: Map[String, String] = Seq(
      Some(Constants.FRAMEWORK_STORAGE -> storageDir.getAbsolutePath),
      sysO.map(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA -> _),
      Some(Constants.FRAMEWORK_BEGINNING_STARTLEVEL -> startConfig.frameworkLevel.toString)
    ).flatten.toMap
    val felix = new Felix(configMap.asJava)
    felix.init
    val fsl = felix.adapt(classOf[FrameworkStartLevel])
    fsl.setInitialBundleStartLevel(startConfig.defaultLevel)
    val context = felix.getBundleContext
    def install(bundleLocs: Map[Int, Seq[ResolvedBundleLocation]], start: Boolean) = {
      bundleLocs.foreach {
        case (level, bundles) => bundles.foreach { b =>
          val bundle = context.installBundle(b.bl.location)
          if ((bundle.adapt(classOf[BundleRevision]).getTypes & BundleRevision.TYPE_FRAGMENT) == 0) {
            bundle.adapt(classOf[BundleStartLevel]).setStartLevel(level)
            if (start) bundle.start()
          }
        }
      }
    }
    install(startConfig.installed, false)
    install(startConfig.started, true)
    install(Map(startConfig.defaultLevel -> startConfig.defaultInstall), false)
    install(Map(startConfig.defaultLevel -> startConfig.defaultStart), true)
    felix.start()
    try {
      f(context)
    }
    finally {
      felix.stop()
      felix.waitForStop(10000L)
    }
  }

  def getBundlesWithDefaults(startConfig: BundleStartConfig) = {
    val dftLevel = startConfig.defaultLevel
    def withDft(bmap: Map[Int, Seq[ResolvedBundleLocation]], dft: Seq[ResolvedBundleLocation]) =
      bmap.updated(dftLevel, bmap.getOrElse(dftLevel, Seq.empty) ++ dft).filter(_._2.nonEmpty)
    (withDft(startConfig.started, startConfig.defaultStart), withDft(startConfig.installed, startConfig.defaultInstall))
  }

  def bundleOption(name: String, bundles: Seq[ResolvedBundleLocation]) = {
    name -> bundles.collect {
      case rbl if !rbl.fragment => (rbl.bl.location)
    }.mkString(" ")
  }

  def doStart(levels: Map[Int, Seq[ResolvedBundleLocation]]) = levels.map {
    case (level, bnds) => bundleOption(s"felix.auto.start.$level", bnds)
  }

  def doInstall(levels: Map[Int, Seq[ResolvedBundleLocation]]) = levels.map {
    case (level, bnds) => bundleOption(s"felix.auto.install.$level", bnds)
  }

  def forker(startConfig: BundleStartConfig): (ForkOptions, Seq[String]) = {
    val (allStarts, allInstalls) = getBundlesWithDefaults(startConfig)
    val defaults = Seq(Constants.FRAMEWORK_BEGINNING_STARTLEVEL -> startConfig.frameworkLevel)

    val installs = doInstall(allInstalls)
    val starts = doStart(allStarts)

    val jvmArgs = (defaults ++ installs ++ starts).map {
      case (n, v) => s"-D$n=$v"
    }
    val startJar = IO.classLocationFile(classOf[AutoProcessor])
    (ForkOptions(runJVMOptions = jvmArgs ++ Seq("-jar", startJar.getAbsolutePath)), Seq(IO.createTemporaryDirectory.getAbsolutePath))
  }


  def writeLauncher(startConfig: BundleStartConfig, dir: File): (ForkOptions, Seq[String]) = {
    IO.delete(dir)
    val bundleDir = dir / "bundle"
    def writeBundles(bundles: Seq[ResolvedBundleLocation]) = {
      bundles.map { rbl =>
        val jarFile = rbl.bl.file
        if (jarFile.isDirectory) sys.error("Bundles must be jars")
        val outJar = bundleDir / jarFile.getName
        IO.copyFile(jarFile, outJar)
        rbl.copy(bl = BundleLocation(outJar, "file:${user.dir}/"+dir.toURI.relativize(outJar.toURI).toString))
      }
    }
    val (_allStarts, _allInstalls) = getBundlesWithDefaults(startConfig)
    val allStarts = _allStarts.mapValues(writeBundles)
    val allInstalls = _allInstalls.mapValues(writeBundles)
    val allBundles = allStarts.flatMap(_._2) ++ allInstalls.flatMap(_._2)
    val (ops, autoAction) = if (allInstalls.isEmpty) {
      val ops = doStart(allStarts - startConfig.defaultLevel)
      (ops, "install,start")
    } else {
      (doStart(allStarts) ++ doInstall(allInstalls - startConfig.defaultLevel), "install")
    }
    val props = new Properties
    props.putAll(ops.asJava)
    props.put(AutoProcessor.AUTO_DEPLOY_ACTION_PROPERTY, autoAction)
    props.put(Constants.FRAMEWORK_BEGINNING_STARTLEVEL, startConfig.frameworkLevel.toString)
    props.put(AutoProcessor.AUTO_DEPLOY_STARTLEVEL_PROPERTY, startConfig.defaultLevel.toString)
    IO.write(props, "Launcher config", dir / "conf/config.properties")

    val startJar = IO.classLocationFile(classOf[AutoProcessor])
    val outStartJar = dir / "lib" / startJar.getName
    IO.copyFile(startJar, outStartJar)
    (ForkOptions(workingDirectory = Some(dir), runJVMOptions = Seq("-jar", outStartJar.getAbsolutePath)), Seq.empty)
  }
}
