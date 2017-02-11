package wav.devtools.karaf.packaging

case class MavenUrl(groupId: String, artifactId: String, version: String, `type`: Option[String] = None, classifer: Option[String] = None) {

  import Util._

  override def toString: String = {
    val url = s"mvn:$groupId/$artifactId/$version"
    (`type`.nonEmptyString, classifer.nonEmptyString) match {
      case (Some(t), Some(c)) => s"$url/$t/$c"
      case (Some(t), None) => s"$url/$t"
      case (None, Some(c)) => s"$url//$c"
      case _ => url
    }
  }

  def toMavenUrl: MavenUrl =
    MavenUrl.unapply(toString).get

}

object MavenUrl {
  private val UrlPattern = """mvn:([[^/].]+)/([[^/].]+)/([[^/].]+)(/[[^/].]+)?(//?[[^/].]+)?""".r

  def unapply(url: String): Option[MavenUrl] =
    url match {
      case UrlPattern(g, a, v, null, null) =>
        Some(MavenUrl(g, a, v, None, None))
      case UrlPattern(g, a, v, t, null)
        if (t != null && t.startsWith("/")) =>
        Some(MavenUrl(g, a, v, Some(t.substring(1)), None))
      case UrlPattern(g, a, v, null, c)
        if (c != null && c.startsWith("//")) =>
        Some(MavenUrl(g, a, v, None, Some(c.substring(2))))
      case UrlPattern(g, a, v, t, c) =>
        Some(MavenUrl(g, a, v, Some(t.substring(1)), Some(c.substring(1))))
      case _ => None
    }

}