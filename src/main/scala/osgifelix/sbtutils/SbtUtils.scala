package osgifelix.sbtutils

import aQute.bnd.version.Version
import osgifelix.BundleInstructions
import sbt.{ConfigurationReport, Dag, ModuleReport, VersionNumber}
import argonaut._

/**
 * Created by jolz on 11/08/15.
 */
object SbtUtils {
  def convertRevision(revision: String): Version = {
    VersionNumber(revision) match {
      case VersionNumber((nums, _, _)) => new Version(nums.slice(0, 3).padTo(3, 0).mkString(".") + ".SNAPSHOT")
    }
  }

  def orderedDependencies(configReport: ConfigurationReport): List[ModuleReport] = {
    val modList = configReport.modules
    val modMap = modList.map(m ⇒ (m.module, m)).toMap
    val ordered = Dag.topologicalSort(modList.map(_.module))(m ⇒ modMap.get(m).map(_.callers.map(_.caller)).getOrElse(Seq.empty)).reverse
    ordered.flatMap(modMap.get)
  }


}
