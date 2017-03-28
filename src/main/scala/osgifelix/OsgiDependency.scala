package osgifelix

import sbt.ModuleID

//: ----------------------------------------------------------------------------------
//: Copyright © 2016 Philip Andrew https://github.com/PhilAndrew  All Rights Reserved.
//: ----------------------------------------------------------------------------------

final case class OsgiDependency(name: String, sbtModules: Seq[ModuleID], packageRequirements: Seq[String], mustBeJarFilesForKaraf: Seq[String] = Seq(), moduleRequirements: Seq[String] = Seq()) {

}
