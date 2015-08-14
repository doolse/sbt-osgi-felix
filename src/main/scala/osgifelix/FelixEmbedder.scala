package osgifelix

import java.io.File
import java.net.URI

import org.apache.felix.framework.Felix
import org.osgi.framework.{Constants, BundleContext}
import org.osgi.framework.startlevel.{BundleStartLevel, FrameworkStartLevel}

import scala.collection.JavaConverters._

/**
 * Created by jolz on 11/08/15.
 */

object BundleLocation {
  def apply(theFile: File): BundleLocation = new BundleLocation {
    def file = theFile

    def location = {
      if (file.isDirectory) {
        "reference:" + file.toURI.toString
      } else file.toURI.toString
    }
  }
}

trait BundleLocation {
  def location: String

  def file: File
}

case class BundleStartConfig(started: Map[Int, Seq[BundleLocation]] = Map.empty, installed: Map[Int, Seq[BundleLocation]] = Map.empty,
                             defaultInstall: Seq[BundleLocation] = Seq.empty, defaultStart: Seq[BundleLocation] = Seq.empty,
                             systemPackages: Iterable[String] = Seq.empty, frameworkLevel: Int = 1, defaultLevel: Int = 1)

object FelixEmbedder {
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
    def install(bundleLocs: Map[Int, Seq[BundleLocation]], start: Boolean) = {
      bundleLocs.foreach {
        case (level, bundles) => bundles.foreach { b =>
          val bundle = context.installBundle(b.location)
          bundle.adapt(classOf[BundleStartLevel]).setStartLevel(level)
          if (start) bundle.start()
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
}
