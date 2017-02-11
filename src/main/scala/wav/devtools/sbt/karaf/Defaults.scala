package wav.devtools.sbt.karaf

import java.util.concurrent.atomic.AtomicReference

import sbt.Keys._
import sbt._
import wav.devtools.karaf.manager._
import wav.devtools.karaf.mbeans._
import KarafKeys._
import packaging.KarafPackagingKeys._

object KarafDefaults {

  lazy val karafContainerArgsSetting = Def.setting(DefaultContainerArgs)

  private val C = Def.setting(new ExtendedKarafJMXClient(karafContainerArgs.value))

  lazy val karafResetServerTask = Def.task(handled(C.value.System(_.rebootCleanAll("now"))))

  // TODO: refresh by url
  lazy val karafUpdateBundleTask = Def.task {
    val (n,v) = karafUpdateBundleName.value
    val b = Bundle(-1, n, v, BundleState.Active)
    handled(C.value.updateBundle(b))
  }

  lazy val karafDeployFeatureTask = Def.task {
    val ff = featuresFile.value
    require(ff.isDefined, "`featuresFile` must produce a features file")
    val repo = ff.get.getAbsoluteFile.toURI.toString
    handled(C.value.startFeature(repo, name.value, version.value))
  }

  lazy val karafUndeployFeatureTask = Def.task {
    val ff = featuresFile.value
    require(ff.isDefined, "`featuresFile` must produce a features file")
    val repo = ff.get.getAbsoluteFile.toURI.toString
    handled(C.value.Features(_.removeRepository(repo, true)))
  }

  private lazy val karafContainer = settingKey[AtomicReference[Option[KarafContainer]]]("The managed karaf container")

  lazy val karafStartServerTask = Def.task {
    val log = streams.value.log
    log.warn("Ignoring `karafContainerArgsSetting`")
    val ref = karafContainer.value
    if (ref.get.isEmpty) {
      val karafBase = unpackKarafDistribution.value
      val config = KarafContainer.createDefaultConfig(karafBase.getAbsolutePath)
      log.debug(config.toString)
      val container = new KarafContainer(config)
      container.start()
      Thread.sleep(500)
      if (container.isAlive) ref.set(Some(container))
      else sys.error(container.log)
    }
  }

  lazy val karafStopServerTask = Def.task {
    val ref = karafContainer.value
    if (ref.get.isDefined) {
      val Some(container) = ref.get
      container.stop()
      ref.set(None)
    }
  }

  private lazy val defaultBundleName = Def.setting((organization.value + "." + name.value).replace("-", "."))

  lazy val karafSettings: Seq[Setting[_]] =
    Seq(karafContainer := new AtomicReference(None),
       karafStartServer := karafStartServerTask.value,
       karafStopServer := karafStopServerTask.value,
       karafResetServer := karafResetServerTask.value,
       karafStatus := println(karafContainer.value.get.foreach(c => println("Alive: " + c.isAlive))),
       karafContainerArgs := karafContainerArgsSetting.value,
       karafDeployFeature := karafDeployFeatureTask.value,
       karafUndeployFeature := karafUndeployFeatureTask.value,
       karafUpdateBundleName := Tuple2(defaultBundleName.value, version.value),
       karafUpdateBundle := karafUpdateBundleTask.value,
       karafUpdateBundle <<= karafUpdateBundle dependsOn (karafDeployFeature))

}