package wav.devtools.sbt.karaf.packaging

import sbt.{Artifact, ModuleID}
import wav.devtools.karaf.packaging.FeaturesXml._

import scala.util.{Success, Failure, Try}

trait PluginSyntax {

  def FeatureID(o: String, n: String, v: String, a: Option[String] = None): ModuleID =
    ModuleID(o, n, v, explicitArtifacts = Seq(Artifact(a getOrElse n, "xml", "xml", "features")))

  def feature(name: String, version: String = "*", prerequisite: Boolean = false, dependency: Boolean = false): Dependency =
    Try(version.versionRange) match {
      case Success(vr) => Dependency(name, vr,
        dependency = dependency,
        prerequisite = prerequisite)
      case Failure(ex) =>
        sys.error(s"The referenced feature ${name} does not have a valid version identifier: " + ex.getMessage)
        ???
    }

  implicit class RichModuleID(m: sbt.ModuleID) {
    def toWrappedBundle(instructions: Map[String, String] = Map.empty, instructionsUrl: Option[String] = None): ModuleID = {
      require(
        instructions.nonEmpty || instructionsUrl.nonEmpty,
        s"$m wrapBundle must have instructions set")
      val WrappedBundlePattern(url,instUrl,insts) = WrappedBundle("scheme:NOT_SET", instructions, instructionsUrl).url
      val attrValue = nullToEmpty(instUrl) + nullToEmpty(insts)
      m.copy(extraAttributes = m.extraAttributes + (SbtResolution.WRAP_BUNDLE_INSTRUCTIONS -> attrValue))
    }
  }

  val FeaturesXml = wav.devtools.karaf.packaging.FeaturesXml

  val MavenUrl = wav.devtools.karaf.packaging.MavenUrl

}