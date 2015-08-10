package osgifelix

import java.io.File
import java.text.SimpleDateFormat
import java.util.{Properties, Date, TimeZone}
import java.util.jar.Attributes.Name
import java.util.jar.Manifest

import aQute.bnd.osgi.{Builder, Analyzer, Jar}
import aQute.bnd.version.Version
import sbt._
import scala.collection.convert.WrapAsJava._

import scalaz.ReaderT
import scalaz.Monad
import scalaz.Kleisli.kleisli
import scalaz.syntax.monad._

trait BundleProgress {
  def info[F[_]](msg: String): F[Unit]
}


object BndManifestGenerator {
  val stampStr = {
    val format = "yyyyMMddHHmm"
    val now = System.currentTimeMillis()
    val tz = TimeZone.getTimeZone("UTC")
    val sdf = new SimpleDateFormat(format)
    sdf.setTimeZone(tz)
    sdf.format(new Date(now))
  }

  def addQualifier(version: Version) = new Version(version.getMajor, version.getMinor, version.getMicro, stampStr)

  def calcVersion(inpVersion: String, revision: Option[String]): Version = {
    val version = if (!inpVersion.isEmpty)
      new Version(inpVersion)
    else revision.fold(new Version())(rev ⇒ VersionNumber(rev) match {
      case VersionNumber((nums, _, _)) ⇒
        new Version(nums.slice(0, 3).padTo(3, 0).mkString(".") + ".SNAPSHOT")
    })
    version.getQualifier match {
      case null | "SNAPSHOT" ⇒ addQualifier(version)
      case _ ⇒ version
    }
  }

  def changeAttribute(name: String, value: String)(man: Manifest): Boolean = {
    val mainAttributes = man.getMainAttributes
    val oldVal = mainAttributes.getValue(name)
    val changed = oldVal != value
    if (changed) mainAttributes.putValue(name, value)
    changed
  }

  def setupAnalyzer(analyzer: Analyzer, prevJars: Iterable[Jar], insts: ManifestInstructions, name: String, version: Version): Unit = {
    analyzer.setProperty("Require-Capability", "osgi.ee; filter:=\"(&(osgi.ee=JavaSE)(version=1.7))\"");
    analyzer.setClasspath(prevJars.toArray)
    analyzer.setImportPackage(insts.imports)
    analyzer.setExportPackage(insts.exports.replaceAll("VERSION", version.getWithoutQualifier.toString))
    analyzer.setBundleSymbolicName(name)
    if (insts.privates != "") analyzer.setPrivatePackage(insts.privates)
    insts.fragment.foreach {
      analyzer.set("Fragment-Host", _)
    }
    analyzer.setBundleVersion(version)
    if (!insts.extraProperties.isEmpty) {
      val props = new Properties()
      props.putAll(insts.extraProperties)
      analyzer.setProperties(props)
    }
  }


  def buildJars[F[_] : Monad](jars: Seq[BundleInstructions], prefix: String, binDir: File, srcsDir: File): ReaderT[F, BundleProgress, Iterable[ProcessedJar]] = kleisli { (logger: BundleProgress) =>
    def removeSigningIfNeeded(jar: Jar) {
      if (jar.getResource("META-INF/BCKEY.SF") != null) {
        jar.getManifest.getEntries.clear()
        logger.info("Jar is signed, removing signing information")
        jar.remove("META-INF/BCKEY.SF")
        jar.remove("META-INF/BCKEY.DSA")
      }
    }

    def buildJar(jars: Iterable[File], insts: ManifestInstructions, previousJars: Iterable[Jar]): ProcessedJar = {
      val version = calcVersion(insts.version, None)
      val jarName = s"${insts.symbolicName}_${version}.jar"
      logger.info("Building new jar " + jarName)
      val analyzer = new Builder
      setupAnalyzer(analyzer, previousJars ++ jars.map(new Jar(_)), insts, insts.symbolicName, version)
      val jar = analyzer.build()
      jar.write(new File(binDir, jarName))
      ProcessedJar(Some(insts.symbolicName, version.toString), jar)
    }

    def rewriteManifest(jar: Jar, prevJars: Iterable[Jar], insts: ManifestInstructions, name: String, jarVersion: Option[String]): ProcessedJar = {
      val version = calcVersion(insts.version, jarVersion)
      val symbName = if (insts.symbolicName == "") s"$prefix${name}" else insts.symbolicName
      val jarName = s"${symbName}_${version}.jar"
      logger.info("Creating bundle for " + jarName)
      val analyzer = new Analyzer
      val man = jar.getManifest
      if (man != null) {
        Set("Import-Package", "Export-Package", "Fragment-Host") foreach { name ⇒
          man.getMainAttributes.remove(new Name(name))
        }
        removeSigningIfNeeded(jar)
      }
      analyzer.setJar(jar)
      setupAnalyzer(analyzer, prevJars, insts, symbName, version)
      val manifest = analyzer.calcManifest()
      jar.setManifest(manifest)
      jar.write(new File(binDir, jarName))
      ProcessedJar(Some(symbName, version.toString), jar)
    }

    def getDetails(jar: Jar) = {
      val bsn = jar.getBsn
      val version = jar.getVersion
      val jarName = s"${bsn}_${new Version(version)}.jar"
      ((bsn, version), binDir / jarName)
    }

    def writeExistingBundle(jar: Jar, f: Manifest ⇒ Boolean) = {
      val man = jar.getManifest
      if (f(man)) {
        changeAttribute("Bundle-Version", addQualifier(new Version(jar.getVersion)).toString)(man)
      }
      removeSigningIfNeeded(jar)
      val (details, jarFile) = getDetails(jar)
      jar.write(jarFile)
      ProcessedJar(Some(details), jar)
    }

    val allProcessedJars = jars.foldLeft(Iterable.empty[ProcessedJar]) { (prevBundles, nextJar) ⇒
      val prevJars = prevBundles.map(_.jar)

      val bundle = nextJar match {
        case CreateBundle(jars, _, insts) => buildJar(jars, insts, prevJars)
        case RewriteManifest(jar, _, name, jarVersion, insts) => rewriteManifest(new Jar(jar), prevJars, insts, name, jarVersion)
        case EditManifest(jar, _, editor) => writeExistingBundle(new Jar(jar), editor)
        case CopyBundle(jf, _) =>
          val existingJar = new Jar(jf)
          val (details, jarFile) = getDetails(existingJar)
          if (jf.isDirectory) IO.copyDirectory(jf, new File(binDir, jf.getName))
          else IO.copyFile(jf, jarFile, true)
          ProcessedJar(Some(details), existingJar)
      }
      for {
        (symbName, symbVersion) ← bundle.bundleId
        sourceJar ← nextJar.sources.headOption
      } yield {
        val destName = new File(srcsDir, sourceJar.getName)
        val sj = new Jar(sourceJar)
        val sa = new Analyzer()
        val sname = Option(sj.getBsn)
        if (!sname.isDefined) {
          logger.info("Creating source bundle for " + sourceJar.getName)
          sa.setJar(sj)
          sa.setBundleSymbolicName(symbName + ".source")
          sa.setBundleVersion(symbVersion)
          sa.setProperty("Eclipse-SourceBundle", s"""$symbName;version="$symbVersion";roots:="."""")
          sa.setExportPackage("!*")
          val srcMan = sa.calcManifest()
          srcMan.getMainAttributes.remove(new Name("Export-Package"))
          srcMan.getMainAttributes.remove(new Name("Private-Package"))
          sj.setManifest(srcMan)
          sj.write(destName)
        } else IO.copyFile(sourceJar, destName, true)
      }
      Some(bundle) ++ prevBundles
    }
    allProcessedJars.point[F]
  }

}
