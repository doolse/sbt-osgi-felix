package wav.devtools.sbt.karaf

import javax.management.remote.JMXConnector

import sbt._
import wav.devtools.karaf.mbeans._

object KarafKeys {

  lazy val karafStartServer       = taskKey[Unit]("Start the server and clean data and caches")
  lazy val karafStopServer        = taskKey[Unit]("Stop the server and clean data and caches")
  lazy val karafResetServer       = taskKey[Unit]("Restart the server and clean data and caches")
  lazy val karafStatus            = taskKey[Unit]("Relevant status information")
  lazy val karafContainerArgs     = settingKey[ContainerArgs]("The remote Karaf container to connect to.")
  lazy val karafUpdateBundle      = taskKey[Unit]("Update the project's bundle")
  lazy val karafUpdateBundleName  = settingKey[(String,String)]("(SymbolicName,Version)")
  lazy val karafDeployFeature     = taskKey[Unit]("Deploy the project's features.xml to the configured karaf container")
  lazy val karafUndeployFeature   = taskKey[Unit]("Undeploy the project's features.xml in the configured karaf container")

}

object SbtKaraf extends AutoPlugin {

  object autoImport {

    import packaging.SbtKarafPackaging.autoImport._

    val KarafKeys = wav.devtools.sbt.karaf.KarafKeys

    def defaultKarafSettings: Seq[Setting[_]] =
      defaultKarafPackagingSettings ++
        KarafDefaults.karafSettings
  }

  override def requires =
    packaging.SbtKarafPackaging

  override lazy val projectSettings =
    autoImport.defaultKarafSettings

}