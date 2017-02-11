package wav.devtools.karaf.packaging

import org.osgi.framework.{Version, VersionRange}

import scala.xml.Elem

trait XmlFormat[T] {
  def write(value: T): Option[Elem]

  def read(elem: Elem): Option[T] =
    this._read
      .andThen(Some(_: T))
      .applyOrElse(elem, (_: Elem) => None)

  val _read: PartialFunction[Elem, T]

}

private[packaging] case class ValueWriter[T](value: T, default: T) {
  import Util._
  // if the selected value matches the default return None. An empty string will return None
  def apply[V](get: T => V, str: V => String = (_: V).toString): Option[String] = {
    val v = get(value)
    if (v == get(default)) None
    else Some(str(v)).nonEmptyString
  }
}

private[packaging] case class AttrReader[T](e: Elem, default: T) {
  import Util._
  def apply[V](attrName: String, conversion: String => V, getDefault: T => V): V =
    e.attributes.asAttrMap.get(attrName).nonEmptyString
      .map(conversion)
      .getOrElse(getDefault(default))
}

object FeaturesXmlFormats {

  import Util._
  import FeaturesXml._

  object repositoryFormat extends XmlFormat[Repository] {
    def write(r: Repository) =
      Some(<repository>{r.url}</repository>)
    val _read: PartialFunction[Elem, Repository] = {
      case e: Elem if e.label == "repository" => Repository(e.text)
    }
  }

  object bundleFormat extends XmlFormat[Bundle] {
    def write(b: Bundle) = {
      val url = MavenUrl.unapply(b.url).getOrElse(b.url)
      val str = ValueWriter(b, emptyBundle)
      Some(setAttrs(<bundle>{url}</bundle>, Map(
        "dependency" -> str(_.dependency),
        "prerequisite" -> str(_.prerequisite),
        "start" -> str(_.start),
        "start-level" -> str(_.`start-level`)
      )))
    }
    val _read: PartialFunction[Elem, Bundle] = {
      case e: Elem if e.label == "bundle" =>
        val rd = AttrReader(e,emptyBundle)
        Bundle(e.text,
          rd("dependency", _.toBoolean, _.dependency),
          rd("prerequisite", _.toBoolean, _.prerequisite),
          rd("start", _.toBoolean, _.start),
          rd("start-level", _.toInt, _.`start-level`))
    }
  }

  object dependencyFormat extends XmlFormat[Dependency] {
    def write(d: Dependency) = {
      val str = ValueWriter(d, emptyDependency)
      Some(setAttrs(<feature>{d.name}</feature>, Map(
        "version" -> str(_.version),
        "prerequisite" -> str(_.prerequisite),
        "dependency" -> str(_.dependency)
      )))
    }
    val _read: PartialFunction[Elem, Dependency] = {
      case e: Elem if e.label == "feature" =>
        val rd = AttrReader(e,emptyDependency)
        Dependency(e.text,
          rd("version", new VersionRange(_), _.version),
          rd("prerequisite", _.toBoolean, _.prerequisite),
          rd("dependency", _.toBoolean, _.dependency))
    }
  }

  object configFormat extends XmlFormat[Config] {
    def write(c: Config) = {
      val str = ValueWriter(c, emptyConfig)
      Some(setAttrs(<config>{c.value}</config>, Map(
        "name" -> Some(c.name),
        "append" -> str(_.append)
      )))
    }
    val _read: PartialFunction[Elem, Config] = {
      case e: Elem if e.label == "config" =>
        val rd = AttrReader(e,emptyConfig)
        Config(
          rd("name", identity, _.name),
          e.text,
          rd("append", _.toBoolean, _.append))
    }
  }

  object configFileFormat extends XmlFormat[ConfigFile] {
    def write(cf: ConfigFile) = {
      val str = ValueWriter(cf, emptyConfigFile)
      Some(setAttrs(<configfile>{cf.value}</configfile>, Map(
        "finalname" -> Some(cf.finalname),
        "override" -> str(_.`override`)
      )))
    }
    val _read: PartialFunction[Elem, ConfigFile] = {
      case e: Elem if e.label == "configfile" =>
        val rd = AttrReader(e,emptyConfigFile)
        ConfigFile(
          rd("finalname", identity, _.finalname),
          e.text,
          rd("override", _.toBoolean, _.`override`))
    }
  }

  object featureFormat extends XmlFormat[Feature] {

    def write(f: Feature) = {
      val str = ValueWriter(f, emptyFeature)
      Some(setAttrs(<feature>{
        f.deps.collect {
          case d: Dependency => dependencyFormat.write(d)
          case b: Bundle => bundleFormat.write(b)
          case c: Config => configFormat.write(c)
        }.flatten
        }</feature>, Map(
        "name" -> Some(f.name),
        "version" -> str(_.version),
        "description" -> str(_.description)
      )))
    }

    def _readDep(e: Elem): Option[FeatureOption] =
      dependencyFormat._read
        .orElse(bundleFormat._read)
        .orElse(configFormat._read)
        .andThen(Some(_))
        .applyOrElse(e, (_: Elem) => None)

    val _read: PartialFunction[Elem, Feature] = {
      case e: Elem if e.label == "feature" =>
        val rd = AttrReader(e,emptyFeature)
        Feature(
          rd("name", identity, _.name),
          rd("version", Version.parseVersion, _.version),
          e.child.collect { case e: Elem => _readDep(e) }.flatten.toSet,
          rd("description", identity, _.description))
    }

  }

  object featuresFormat extends XmlFormat[FeaturesXml] {

    def write(fd: FeaturesXml) = {
      Some(setAttrs(<features>{
        fd.elems.collect {
          case r: Repository => repositoryFormat.write(r)
          case f: Feature => featureFormat.write(f)
        }.flatten
        }</features>, Map(
        "name" -> Some(fd.name),
        "xmlns" -> Some("http://karaf.apache.org/xmlns/features/v1.3.0")
      )))
    }

    def _readDep(e: Elem): Option[FeaturesOption] =
      repositoryFormat._read
        .orElse(featureFormat._read)
        .andThen(Some(_))
        .applyOrElse(e, (_: Elem) => None)

    val _read: PartialFunction[Elem, FeaturesXml] = {
      case e: Elem if e.label == "features" =>
        val rd = AttrReader(e,emptyFeaturesXml)
        FeaturesXml(
          rd("name", identity, _.name),
          e.child.collect { case e: Elem => _readDep(e) }.flatten)
    }
  }

  val featuresSchemas =
    Seq(
      "1.3.0", /* is a super set of 1.2.0 */
      "1.2.0")
      .map(v => v -> (s"http://karaf.apache.org/xmlns/features/v$v" -> s"org/apache/karaf/features/karaf-features-$v.xsd"))

  val (featuresXsdUrl, featuresXsd) = featuresSchemas.head._2

  def makeFeaturesXml[N <: scala.xml.Node](featuresXml: FeaturesXml): Elem =
    featuresFormat.write(featuresXml).get

  def readFeaturesXml[N <: scala.xml.Node](source: N): Option[FeaturesXml] =
    (source \\ "features" collectFirst {
      case e: Elem => featuresFormat.read(e)
    }).flatten

}