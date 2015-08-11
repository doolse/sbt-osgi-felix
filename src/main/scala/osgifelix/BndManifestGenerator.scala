package osgifelix

import java.io.File
import java.text.SimpleDateFormat
import java.util.jar.Attributes.Name
import java.util.jar.Manifest
import java.util.{Date, Properties, TimeZone}

import aQute.bnd.osgi.{Analyzer, Builder, Jar}
import aQute.bnd.version.Version
import sbt._

import scala.collection.convert.WrapAsJava._
import scalaz.Kleisli.kleisli
import scalaz.{Monad, ReaderT}
import scalaz.syntax.monad._

trait BundleProgress[F[_]] {
  def info(msg: String): F[Unit]
}


object BndManifestGenerator {
  def genQualifier = {
    val format = "yyyyMMddHHmm"
    val now = System.currentTimeMillis()
    val tz = TimeZone.getTimeZone("UTC")
    val sdf = new SimpleDateFormat(format)
    sdf.setTimeZone(tz)
    sdf.format(new Date(now))
  }

  def parseDetails(jf: File): (Jar, Option[(String, Version)]) = {
    val jar = new Jar(jf)
    val verName = Option(jar.getBsn).map(sn => (sn, new Version(jar.getVersion)))
    (jar, verName)
  }

  def buildJars[F[_] : Monad](jars: Seq[BundleInstructions], binDir: File, srcsDir: File): ReaderT[F, BundleProgress[F], Iterable[ProcessedJar]] = kleisli { (logger: BundleProgress[F]) =>

    val stampStr = genQualifier

    def addQualifier(version: Version) = new Version(version.getMajor, version.getMinor, version.getMicro, stampStr)

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
      if (insts.extraProperties.nonEmpty) {
        val props = new Properties()
        props.putAll(insts.extraProperties)
        analyzer.setProperties(props)
      }
    }


    def removeSigningIfNeeded(jar: Jar) {
      if (jar.getResource("META-INF/BCKEY.SF") != null) {
        jar.getManifest.getEntries.clear()
        logger.info("Jar is signed, removing signing information")
        jar.remove("META-INF/BCKEY.SF")
        jar.remove("META-INF/BCKEY.DSA")
      }
    }

    def buildJar(jars: Iterable[File], bsn: String, ver: Version, insts: ManifestInstructions, previousJars: Iterable[Jar]): ProcessedJar = {
      val version = addQualifier(ver)
      val jarName = s"${bsn}_$version}.jar"
      logger.info(s"Building $jarName")
      val analyzer = new Builder
      setupAnalyzer(analyzer, previousJars ++ jars.map(new Jar(_)), insts, bsn, version)
      val jar = analyzer.build()
      val jf = new File(binDir, jarName)
      jar.write(jf)
      ProcessedJar(bsn, version, jar, jf)
    }

    def rewriteManifest(jar: Jar, bsn: String, ver: Version, prevJars: Iterable[Jar], insts: ManifestInstructions): ProcessedJar = {
      val version = addQualifier(ver)
      val jarName = s"${jar.getName}.jar"
      logger.info(s"Rewriting $jarName (${bsn}_$version)")
      val analyzer = new Analyzer
      val man = jar.getManifest
      if (man != null) {
        Set("Import-Package", "Export-Package", "Fragment-Host") foreach { name ⇒
          man.getMainAttributes.remove(new Name(name))
        }
        removeSigningIfNeeded(jar)
      }
      analyzer.setJar(jar)
      setupAnalyzer(analyzer, prevJars, insts, bsn, version)
      val manifest = analyzer.calcManifest()
      jar.setManifest(manifest)
      val jf = new File(binDir, jarName)
      jar.write(jf)
      ProcessedJar(bsn, version, jar, jf)
    }

    def getDetails(jar: Jar) = {
      val bsn = jar.getBsn
      val version = new Version(jar.getVersion)
      val jarName = s"${bsn}_$version.jar"
      (bsn, version, binDir / jarName)
    }

    def writeExistingBundle(jar: Jar, f: Manifest ⇒ Boolean) = {
      val man = jar.getManifest
      if (f(man)) {
        changeAttribute("Bundle-Version", addQualifier(new Version(jar.getVersion)).toString)(man)
      }
      removeSigningIfNeeded(jar)
      val (bsn, version, jarFile) = getDetails(jar)
      jar.write(jarFile)
      ProcessedJar(bsn, version, jar, jarFile)
    }

    val allProcessedJars = jars.foldLeft(Iterable.empty[ProcessedJar]) { (prevBundles, nextJar) ⇒
      val prevJars = prevBundles.map(_.jar)

      val bundle = nextJar match {
        case CreateBundle(jars, bsn, ver, _, insts) => buildJar(jars, bsn, ver, insts, prevJars)
        case RewriteManifest(jar, bsn, ver, _, insts) => rewriteManifest(jar, bsn, ver, prevJars, insts)
        case EditManifest(jar, _, editor) => writeExistingBundle(new Jar(jar), editor)
        case CopyBundle(jf, existingJar, _) =>
          val (bsn, version, jarFile) = getDetails(existingJar)
          logger.info(s"Copying ${jarFile.getName} (${bsn}_$version)")
          IO.copyFile(jf, jarFile, true)
          ProcessedJar(bsn, version, existingJar, jarFile)
      }
      nextJar.sources.headOption.foreach { sourceJar =>
        val destName = new File(srcsDir, sourceJar.getName)
        val sj = new Jar(sourceJar)
        val sa = new Analyzer()
        val sname = Option(sj.getBsn)
        if (!sname.isDefined) {
          logger.info("Creating source bundle for " + sourceJar.getName)
          sa.setJar(sj)
          val symbName = bundle.bsn
          val version = bundle.version
          sa.setBundleSymbolicName(symbName + ".source")
          sa.setBundleVersion(version)
          sa.setProperty("Eclipse-SourceBundle", s"""$symbName;version="$version";roots:="."""")
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
