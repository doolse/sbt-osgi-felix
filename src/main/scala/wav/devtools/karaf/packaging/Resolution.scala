package wav.devtools.karaf.packaging

import org.osgi.framework.Version
import wav.devtools.karaf.packaging.FeaturesXml._

import scala.annotation.tailrec

object Resolution {

  def satisfies(constraint: Dependency, feature: Feature): Boolean =
    constraint.name == feature.name && (
      constraint.version.isEmpty || {
        var vr = constraint.version
        !vr.isEmpty() && (feature.version == Version.emptyVersion || vr.includes(feature.version))
      })

  def selectFeatureDeps(dep: Dependency, fs: Set[Feature]): Set[Dependency] =
    fs.filter(satisfies(dep, _)).flatMap(_.deps).collect { case dep: Dependency => dep }

  def selectFeatures(requested: Set[Dependency], fs: Set[Feature]): Either[Set[Dependency], Set[Feature]] = {
    val unsatisfied = for {
      constraint <- requested
      if (fs.forall(f => !satisfies(constraint, f)))
    } yield constraint
    if (unsatisfied.nonEmpty) Left(unsatisfied)
    else Right(
      for {
        constraint <- requested
        feature <- fs
        if (satisfies(constraint, feature))
      } yield feature
    )
  }

  @tailrec
  def resolveFeatures(requested: Set[Dependency], fs: Set[Feature], resolved: Set[Feature] = Set.empty): Either[Set[Dependency], Set[Feature]] = {
    if (requested.isEmpty) return Right(resolved)
    val result = selectFeatures(requested, fs)
    if (result.isLeft) result
    else {
      val Right(selection) = result
      val selectedRefs = selection.map(_.toDep)
      val resolvedRefs = resolved.map(_.toDep)
      val resolved2 = selection ++ resolved
      val unresolved = selectedRefs.flatMap(selectFeatureDeps(_, fs)) -- resolvedRefs
      resolveFeatures(unresolved, fs, resolved2)
    }
  }

  def mustResolveFeatures(selected: Either[Set[Dependency], Set[Feature]]): Set[Feature] = {
    selected match {
      case Left(unresolved) => sys.error(s"The following features could not be resolved: $unresolved")
      case Right(resolved) =>
        val duplicates = resolved.toSeq
          .map(_.name)
          .groupBy(identity)
          .mapValues(_.size)
          .filter(_._2 > 1)
          .keys
        if (duplicates.nonEmpty)
          sys.error(s"Could not select a unique feature for the following: $duplicates")
    }
    val Right(resolved) = selected
    resolved
  }

}