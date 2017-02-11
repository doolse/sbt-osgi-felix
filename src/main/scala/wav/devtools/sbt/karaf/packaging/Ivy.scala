package wav.devtools.sbt.karaf.packaging

import org.apache.ivy.core.module.descriptor.{Artifact => IvyArtifact, DefaultArtifact}
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.report.DownloadStatus
import org.apache.ivy.core.resolve.DownloadOptions
import org.apache.ivy.plugins.resolver.DependencyResolver
import sbt._
import sbt.mavenint.MavenRepositoryResolver
import wav.devtools.karaf.packaging.MavenUrl

import scala.collection.JavaConversions._

private[packaging] object Ivy {

  // Delegates to sbt-maven-resolver MavenRepositoryResolver.download
  def downloadMavenArtifact(url: MavenUrl, rs: Seq[Resolver], ivy: IvySbt, logger: Logger, opts: UpdateOptions): Option[File] =
    rs.collect { case m: MavenRepository => m }
      .flatMap { r =>
        ivy.withIvy(logger) { ivy =>
          val resolver = opts.resolverConverter((r, ivy.getSettings, logger))
          tryDownload(url, logger, resolver)
        }
      }
      .headOption

  private def tryDownload(url: MavenUrl, logger: Logger, resolver: DependencyResolver): Iterable[File] = {
    val report = resolver.download(Array(toIvyArtfact(url)), new DownloadOptions())
    val success = report.getArtifactsReports(DownloadStatus.SUCCESSFUL).headOption
    if (success.isEmpty)
      logger.warn(report.getArtifactsReports.mkString("\n"))
    success.map(_.getLocalFile)
  }

  private def toIvyArtfact(url: MavenUrl): IvyArtifact = {
    val extra = url.classifer match {
      case Some(classifier) => Map(
        "classifier" -> classifier,
        // Satisfy: MavenRepositoryResolver.getClassifier
        MavenRepositoryResolver.CLASSIFIER_ATTRIBUTE -> classifier
      )
      case _ => Map.empty
    }
    val mrid = ModuleRevisionId.newInstance(
      url.groupId,
      url.artifactId,
      url.version)
    val t = url.`type` getOrElse "jar"
    val ext = url.`type` getOrElse "jar"
    new DefaultArtifact(mrid, null, url.artifactId, t, ext, extra)
  }

}
