package wav.devtools.karaf.packaging

import java.net.URI

case class KarafDistribution(url: URI, artifactName: String, contentPath: String) {
  override def toString(): String =
    s"KarafDistribution($url,$artifactName,$contentPath)"
}