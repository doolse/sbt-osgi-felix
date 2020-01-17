package osgifelix

import java.io.File
import java.text.SimpleDateFormat
import java.util.jar.Attributes.Name
import java.util.jar.Manifest
import java.util.{Date, Properties, TimeZone}

import aQute.bnd.osgi.{Analyzer, Builder, Jar}
import aQute.bnd.version.Version
import argonaut._
import argonaut.Argonaut._
import sbt._

import scala.collection.convert.WrapAsJava._
import scalaz.Kleisli.kleisli
import scalaz.{Monad, ReaderT}
import scalaz.syntax.monad._

trait BundleProgress[F[_]] {
  def info(msg: String): F[Unit]
}


object BndManifestGenerator {

  implicit val fileEncode = EncodeJson[File](_.getAbsolutePath.asJson)
  implicit val jarEncode = EncodeJson[Jar](_.getName.asJson)
  implicit val verEncode = EncodeJson[Version](_.toString.asJson)
  implicit val modIdEncode = EncodeJson[ModuleID] {
  mid => s"${mid.organization} % ${mid.name} % ${mid.revision}".asJson
  }
  implicit val instEncode = EncodeJson.derive[ManifestInstructions]
  implicit val rewriteEncode = EncodeJson.derive[RewriteManifest]
  implicit val createEncode = EncodeJson.derive[CreateBundle]
  implicit val copyEncode = EncodeJson.derive[UseBundle]
  implicit val manifestEncode = EncodeJson.derive[ManifestOnly]
  implicit val bundleEncode = EncodeJson[BundleInstructions] {
      case a: RewriteManifest => jSingleObject("rewrite", a.asJson)
      case a: CreateBundle => jSingleObject("create", a.asJson)
      case a: UseBundle => jSingleObject("use", a.asJson)
      case a: ManifestOnly => jSingleObject("manifest", a.asJson)
  }

  def serialize(instructions: Seq[BundleInstructions]): String = {
    instructions.toList.asJson.nospaces
  }

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

  def buildJars[F[_] : Monad](jars: Seq[BundleInstructions], binDir: File): ReaderT[F, BundleProgress[F], Iterable[ProcessedJar]] = kleisli { (logger: BundleProgress[F]) =>

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

    def buildJar(modIds: Seq[ModuleID], jars: Iterable[File], bsn: String, ver: Version, insts: ManifestInstructions, previousJars: Iterable[Jar]): ProcessedJar = {
      val version = addQualifier(ver)
      val jarName = s"${bsn}_$version.jar"
      logger.info(s"Building $jarName")
      val analyzer = new Builder
      setupAnalyzer(analyzer, previousJars ++ jars.map(new Jar(_)), insts, bsn, version)
      val jar = analyzer.build()
      val jf = new File(binDir, jarName)
      jar.write(jf)
      ProcessedJar(modIds, bsn, version, jar, jf)
    }

    def rewriteManifest(modId: Option[ModuleID], jar: Jar, bsn: String, ver: Version, prevJars: Iterable[Jar], insts: ManifestInstructions): ProcessedJar = {
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
      ProcessedJar(modId.toSeq, bsn, version, jar, jf)
    }

    def getDetails(jar: Jar) = {
      val bsn = jar.getBsn
      val version = new Version(jar.getVersion)
      (bsn, version)
    }

    val allProcessedJars = jars.foldLeft(Iterable.empty[ProcessedJar]) { (prevBundles, nextJar) ⇒
      val prevJars = prevBundles.map(_.jar)

      val bundle = nextJar match {
        case CreateBundle(modIds, jars, bsn, ver, insts) => buildJar(modIds, jars, bsn, ver, insts, prevJars)
        case RewriteManifest(modId, jar, bsn, ver, insts) => rewriteManifest(modId, jar, bsn, ver, prevJars, insts)
        case ManifestOnly(modId, bsn, ver, headers) =>
          val jar = new Jar(bsn)
          val builder = new Builder()
          builder.setJar(jar)
          builder.setBundleSymbolicName(bsn)
          val version = addQualifier(ver)
          builder.setBundleVersion(version)
          headers.foreach {
            case (key,v) => builder.setProperty(key, v)
          }
          jar.setManifest(builder.calcManifest())
          val file = binDir / s"${bsn}_$version.jar"
          jar.write(file)
          ProcessedJar(Seq.empty, bsn, version, jar, file)
        case UseBundle(modId, jf, existingJar) =>
          val (bsn, version) = getDetails(existingJar)
          logger.info(s"Using ${jf.getName} (${bsn}_$version)")
          ProcessedJar(modId.toSeq, bsn, version, existingJar, jf)
      }
      Some(bundle) ++ prevBundles
    }
    allProcessedJars.point[F]
  }

}
