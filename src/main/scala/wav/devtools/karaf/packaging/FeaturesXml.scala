package wav.devtools.karaf.packaging

import org.osgi.framework.{Version, VersionRange}

/**
 * All defaults are based off the 1.3.0.xsd
 * - Required fields don't have optional values
 * - Bool's are false by default unless otherwise specified.
 * - In FeaturesXmlFormats default attributes are not written
 */
object FeaturesXml {

  import Util._

  sealed trait FeaturesOption

  case class Repository(url: String)
    extends FeaturesOption

  sealed trait FeatureOption

  implicit class StringVersion(v: String) {

    private val normalized =
      Option(v).map(_.trim).nonEmptyString.filterNot(_ == "*").getOrElse("0.0.0")

    lazy val version: Version = Version.parseVersion(normalized)

    lazy val versionRange: VersionRange =
      new VersionRange(normalized)

  }

  case class Feature(
    name: String,
    version: Version = Version.emptyVersion /* 0.0.0 */,
    deps: Set[FeatureOption] = Set.empty,
    description: String = null
  ) extends FeaturesOption {
    def toDep: Dependency =
      Dependency(name)
  }

  val emptyFeature = Feature(null)

  sealed trait ConditionalOption

  case class Bundle(
    url: String,
    dependency: Boolean = false, /* true if it's a runtime dependency */
    prerequisite: Boolean = false,
    start: Boolean = true,
    `start-level`: Int = 0
    ) extends FeatureOption with ConditionalOption

  val emptyBundle = Bundle(null)

  // Wrapping a bundle doesn't require instructions, if none are provided defaults are set by the runtime.
  // https://ops4j1.jira.com/wiki/display/paxurl/Wrap+Protocol#WrapProtocol-defaultinstructions
  def WrappedBundle(
    url: String,
    instructions: Map[String, String] = Map.empty,
    instructionsUrl: Option[String] = None,
    dependency: Boolean = false,
    prerequisite: Boolean = false,
    start: Boolean = true,
    `start-level`: Int = 0): Bundle = {
    val instUrl = if (instructionsUrl.nonEmpty) ("," + instructionsUrl.get) else ""
    val inst = if (instructions.nonEmpty) ("$" + instructions.map(e => e._1 + "=" + e._2).mkString("&")) else ""
    Bundle("wrap:" + url + instUrl + inst, dependency, prerequisite, start, `start-level`)
  }

  val WrappedBundlePattern = "wrap:([a-z]+:[[^,$$].]*)(,[a-z]+:[[^$$].]*)?([$$].*)?".r

  case class Dependency(
    name: String,
    version: VersionRange = "".versionRange,
    prerequisite: Boolean = false,
    dependency: Boolean = false
    ) extends FeatureOption

  val emptyDependency = Dependency(null)

  case class Config(
    name: String,
    value: String,
    append: Boolean = false
    ) extends FeatureOption with ConditionalOption

  val emptyConfig = Config(null, null)

  case class ConfigFile(
    finalname: String,
    value: String,
    `override`: Boolean = false
    ) extends FeatureOption with ConditionalOption

  val emptyConfigFile = ConfigFile(null, null)

  case class Conditional(
    condition: String,
    deps: Set[ConditionalOption]
    ) extends FeatureOption

  val emptyConditional = Conditional(null, Set.empty)

  def feature(name: String, version: String, deps: Set[FeatureOption] = Set.empty): Feature =
    Feature(name, Version.parseVersion(version), deps)

  private[packaging] val emptyFeaturesXml = FeaturesXml(null, Seq.empty)

}

case class FeaturesXml(name: String, elems: Seq[FeaturesXml.FeaturesOption] = Nil) {
  import FeaturesXml._
  lazy val repositories: Seq[Repository] = elems.collect { case f: Repository => f }
  lazy val features: Seq[Feature] = elems.collect { case f: Feature => f }
}