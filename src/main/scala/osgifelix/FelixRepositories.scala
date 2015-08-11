package osgifelix

import java.io.{FileWriter, File}

import org.apache.felix.bundlerepository.{Repository, RepositoryAdmin}
import org.osgi.framework.BundleContext
import org.osgi.util.tracker.ServiceTracker

/**
 * Created by jolz on 11/08/15.
 */

object FelixRepositories {
  def runRepoAdmin[A](bundles: Seq[File])(f: FelixRepositories => A) = {
    val config = BundleStartConfig(systemPackages = Seq("org.apache.felix.bundlerepository;version=2.1"), defaultStart = bundles.map(b => BundleLocation(b.toURI)))
    FelixEmbedder.embed(config)(bc => f(new FelixRepositories(bc)))
  }
}

class FelixRepositories(bundleContext: BundleContext) {

  lazy val repoAdmin = {
    val tracker = new ServiceTracker[RepositoryAdmin, RepositoryAdmin](bundleContext, classOf[RepositoryAdmin], null)
    tracker.open(true)
    tracker.waitForService(1000L)
  }

  def writeRepo(repo: Repository, indexFile: File) = {
    val helper = repoAdmin.getHelper
    val writer = new FileWriter(indexFile)
    helper.writeRepository(repo, writer)
    writer.close()
  }

  def createIndexedRepository(jarFiles: Seq[File]) = {
    val helper = repoAdmin.getHelper
    val resources = jarFiles.map(j => helper.createResource(j.toURI.toURL))
    helper.repository(resources.toArray)
  }

  def checkConsistency(repo: Repository) = {
    val resolver = repoAdmin.resolver(Array(repo, repoAdmin.getSystemRepository))
    repo.getResources.foreach(resolver.add)
    resolver.resolve()
    resolver.getUnsatisfiedRequirements
  }
}
