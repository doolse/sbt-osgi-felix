package wav.devtools.karaf.manager

import java.net.URI
import javax.management.remote.JMXConnector
import wav.devtools.karaf.mbeans, mbeans._
import concurrent.duration._

import scala.util.Try

trait KarafJMXClient {

  val args: ContainerArgs

  protected val connector: () => Try[JMXConnector] =
    () => MBeanConnection.tryConnect(args)

  val Bundles = BundlesService.newInvoker(connector)

  val Config = ConfigService.newInvoker(connector)

  val Features = FeaturesService.newInvoker(connector)

  val System = SystemService.newInvoker(connector)

}

class ExtendedKarafJMXClient(val args: ContainerArgs) extends KarafJMXClient {

  import wav.devtools.karaf.mbeans.MBeanExtensions._

  def startFeature(repo: String, name: String, version: String): Try[Unit] =
    for {
      _ <- System(_.awaitStartLevel(13))
      _ <- Features({ s =>
        s.addRepository(repo)
        s.installFeature(name, version)
      })
    } yield ()

  def updateBundle(bundle: mbeans.Bundle): Try[Unit] =
    Bundles({ s =>
      val existing = s.list.find(_.name == bundle.name)
      require(existing.isDefined, s"${bundle.name} should be installed.")
      existing.foreach(b => s.update(b.bundleId.toString))
      val updated = s.list.find(_.name == bundle.name)
      require(updated.isDefined, s"${bundle.name} should be updated.")
      if (!BundleState.inState(bundle.state, updated.get.state))
      sys.error(s"Bundle state for `${bundle.name}` should be ${bundle.state}, actual ${updated.get.state}")
    })

}


