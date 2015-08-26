package osgifelix

import java.io.{FileReader, FileWriter, File}
import java.net.{URL, URI}
import java.util.jar.JarFile

import org.apache.felix.bundlerepository.Resolver
import org.apache.felix.bundlerepository._
import org.osgi.framework.{VersionRange, BundleContext}
import org.osgi.util.tracker.ServiceTracker
import sbt.{Resolver => _, _}

import scalaz.\/
import scalaz.syntax.either._

/**
 * Created by jolz on 11/08/15.
 */

trait FelixRepoRunner {
  def apply[A](f: FelixRepositories => A): A
}

object FelixRepositories {
  def runRepoAdmin(bundles: Seq[File], storageDir: File): FelixRepoRunner = {
    val config = BundleStartConfig(systemPackages = Seq("org.apache.felix.bundlerepository;version=2.1"), defaultStart = bundles.map(ResolvedBundleLocation.apply))
    new FelixRepoRunner {
      override def apply[A](f: (FelixRepositories) => A): A = FelixRepositories.synchronized {
        FelixRunner.embed(config, storageDir)(c => f(new FelixRepositories(c)))
      }
    }
  }
}


sealed trait OsgiRequirement

case class BundleRequirement(name: String, version: Option[VersionRange] = None) extends OsgiRequirement

case class PackageRequirement(name: String, version: Option[VersionRange] = None) extends OsgiRequirement

case class FragmentsRequirement(hostName: String) extends OsgiRequirement

class FelixRepositories(bundleContext: BundleContext) {

  lazy val repoAdmin = {
    val tracker = new ServiceTracker[RepositoryAdmin, RepositoryAdmin](bundleContext, classOf[RepositoryAdmin], null)
    tracker.open(true)
    tracker.waitForService(1000L)
  }

  def writeRepository(repo: Repository, indexFile: File) = {
    val helper = repoAdmin.getHelper
    val writer = new FileWriter(indexFile)
    helper.writeRepository(repo, writer)
    writer.close()
  }

  def loadRepository(indexFile: File): Repository = {
    val fr = new FileReader(indexFile)
    try {
      repoAdmin.getHelper.readRepository(fr)
    }
    finally {
      fr.close
    }
  }

  def resourcesFromLocations(locations: Seq[BundleLocation]) = {
    val helper = repoAdmin.getHelper
    locations.map { j =>
      val file = j.file
      if (file.isDirectory) {
        val attr = Using.fileInputStream(file / JarFile.MANIFEST_NAME) { finp =>
          new java.util.jar.Manifest(finp).getMainAttributes
        }
        val res = helper.createResource(attr)
        res.getProperties.asInstanceOf[java.util.Map[String, String]].put(Resource.URI, file.toURI.toString)
        res
      } else helper.createResource(file.toURI.toURL)
    }
  }

  def createRepository(jarFiles: Seq[BundleLocation]) = {
    val helper = repoAdmin.getHelper
    helper.repository(resourcesFromLocations(jarFiles).toArray)
  }

  def checkConsistency(repo: Repository) = {
    val resolver = repoAdmin.resolver(Array(repo, repoAdmin.getSystemRepository))
    repo.getResources.foreach(resolver.add)
    resolver.resolve()
    resolver.getUnsatisfiedRequirements
  }

  private def withVersion(filterStr: String, ver: Option[VersionRange]) = ver match {
    case Some(range) => s"(&$filterStr${range.toFilterString("version")})"
    case None => filterStr
  }

  private def setupRequirements(requirements: Seq[OsgiRequirement], repos: Seq[Repository]) = {
    val resolver = repoAdmin.resolver((repos :+ repoAdmin.getSystemRepository).toArray)
    val helper = repoAdmin.getHelper
    val reqs = requirements.map {
      case BundleRequirement(name, ver) => helper.requirement(Capability.BUNDLE, withVersion(s"(symbolicname=$name)", ver))
      case PackageRequirement(name, ver) => helper.requirement(Capability.PACKAGE, withVersion(s"(package=$name)", ver))
      case FragmentsRequirement(name) => helper.requirement(Capability.FRAGMENT, s"(host=$name)")
    }
    reqs.foreach(resolver.add)
    (resolver, helper)
  }

  def resolveRequirements(repos: Seq[Repository], requirements: Seq[OsgiRequirement]): Array[Reason] \/ Seq[ResolvedBundleLocation] = {
    val (resolver, helper) = setupRequirements(requirements, repos)
    val success = resolver.resolve(Resolver.NO_OPTIONAL_RESOURCES)
    if (success) {
      val systemBundle = ResolvedBundleLocation(IO.classLocationFile[BundleContext])
      (resolver.getRequiredResources.map(ResolvedBundleLocation.apply).toSeq ++ Seq(systemBundle)).right
    } else resolver.getUnsatisfiedRequirements.left
  }

  def resolveStartConfig(repos: Seq[Repository], resources: Seq[BundleLocation], requirements: Seq[OsgiRequirement],
                         startBundles: Map[Int, Seq[BundleRequirement]]): Array[Reason] \/ BundleStartConfig = {
    val allReq = requirements ++ startBundles.flatMap(_._2)
    val (resolver, helper) = setupRequirements(allReq, repos)
    resourcesFromLocations(resources).foreach(resolver.add)
    val bundleLevelMap = startBundles.flatMap {
      case ((level, reqs)) => reqs.map(r => (r.name, level))
    }
    val success = resolver.resolve(Resolver.NO_OPTIONAL_RESOURCES)
    if (success) {
      val runMap = (resolver.getRequiredResources ++ resolver.getAddedResources).map { r =>
        val bl = ResolvedBundleLocation(r)
        val runLevel = if (bl.fragment) 0 else bundleLevelMap.get(r.getSymbolicName).getOrElse(0)
        (runLevel, bl)
      }.groupBy(_._1).mapValues(_.map(_._2).toSeq)
      BundleStartConfig(started = runMap - 0, defaultStart = runMap.get(0).getOrElse(Seq.empty)).right
    } else resolver.getUnsatisfiedRequirements.left

  }
}
