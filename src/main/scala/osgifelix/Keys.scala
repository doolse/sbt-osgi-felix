package osgifelix

import org.apache.felix.bundlerepository.Repository
import sbt._

/**
 * Created by jolz on 13/08/15.
 */
object Keys {
  lazy val originalUpdate = taskKey[UpdateReport]("Original update report")
  lazy val osgiRepository = taskKey[Repository]("Repository for resolving osgi dependencies")
  lazy val osgiInstructions = taskKey[Seq[BundleInstructions]]("Instructions for BND")
  lazy val osgiDependencies = settingKey[Seq[OsgiRequirement]]("OSGi dependencies")
  lazy val osgiFilterRules = settingKey[Seq[InstructionFilter]]("Filters for generating BND instructions")
  lazy val osgiPrefix = settingKey[String]("Prefix for generated bundle names")


}
