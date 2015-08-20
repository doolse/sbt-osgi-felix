package osgifelix

import aQute.bnd.version.Version
import sbt._

/**
 * Created by jolz on 13/08/15.
 */
trait InstructionFilters {

  def rewrite(moduleName: String, imports: String = "*", exports: String = "*;version=VERSION"): InstructionFilter =
    rewriteFilter(moduleName, moduleFilter(name = moduleName), imports, exports)

  def rewriteCustom(moduleName: String, insts: ManifestInstructions): InstructionFilter =
    RewriteFilter(moduleName, moduleFilter(name = moduleName), insts)

  def rewriteFilter(filterName: String, filter: DependencyFilter, imports: String = "*", exports: String = "*;version=VERSION"): InstructionFilter =
    RewriteFilter(filterName, filter, ManifestInstructions(imports = imports, exports = exports))

  def ignore(moduleName: String) = IgnoreFilter(moduleName, moduleFilter(name = moduleName))

  def ignoreAll(firstModule: String, next: String*) = IgnoreFilter(firstModule, moduleFilter(name = next.foldLeft[NameFilter](firstModule)((b,n) => b|n)))

  def ignoreCustom(name: String, filter: DependencyFilter) = IgnoreFilter(name, filter)

  def create(moduleNames: NameFilter, symbolicName: String, version: String, imports: String = "*", exports: String = "*;version=VERSION") =
    CreateFilter(symbolicName, moduleFilter(name = moduleNames), symbolicName, new Version(version), ManifestInstructions(imports = imports, exports = exports), false)

  def createCustom(moduleNames: NameFilter, symbolicName: String, version: String, processDefault: Boolean = false, instructions: ManifestInstructions) =
    CreateFilter(symbolicName, moduleFilter(name = moduleNames), symbolicName, new Version(version), instructions, processDefault)
}

sealed trait InstructionFilter {
  def filter: DependencyFilter

  def filterName: String
}

case class RewriteFilter(filterName: String, filter: DependencyFilter, instructions: ManifestInstructions, name: Option[String] = None, version: Option[Version] = None) extends InstructionFilter

case class CreateFilter(filterName: String, filter: DependencyFilter, name: String, version: Version, instructions: ManifestInstructions, processDefault: Boolean) extends InstructionFilter

case class IgnoreFilter(filterName: String, filter: DependencyFilter) extends InstructionFilter
