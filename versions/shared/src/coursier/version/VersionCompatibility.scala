package coursier.version

import scala.annotation.nowarn

/**
 * Represents a reconciliation strategy given a dependency conflict.
 */
sealed abstract class VersionCompatibility {
  def isCompatible(constraint: String, version: String): Boolean

  @nowarn
  final def name: String =
    this match {
      case VersionCompatibility.Always => "always compatible"
      case VersionCompatibility.Strict => "strict"
      case VersionCompatibility.SemVerSpec => "strict semantic versioning"
      case VersionCompatibility.EarlySemVer | VersionCompatibility.SemVer =>
        "early semantic versioning"
      case VersionCompatibility.Default | VersionCompatibility.PackVer =>
        "package versioning policy"
    }

  def minimumCompatibleVersion(version: String): String
}

object VersionCompatibility {

  case object Default extends VersionCompatibility {
    def isCompatible(constraint: String, version: String): Boolean =
      PackVer.isCompatible(constraint, version)
    def minimumCompatibleVersion(version: String): String =
      PackVer.minimumCompatibleVersion(version)
  }

  case object Always extends VersionCompatibility {
    def isCompatible(constraint: String, version: String): Boolean =
      true
    def minimumCompatibleVersion(version: String): String =
      "0"
  }

  /**
    * Strict version reconciliation.
    *
    * This particular instance behaves the same as [[Default]] when used by
    * [[coursier.core.Resolution]]. Actual strict conflict manager is handled
    * by `coursier.params.rule.Strict`, which is set up by `coursier.Resolve`
    * when a strict reconciliation is added to it.
    */
  case object Strict extends VersionCompatibility {
    def isCompatible(constraint: String, version: String): Boolean =
      constraint == version || {
        val c = VersionParse.versionConstraint(constraint)
        val v = Version(version)
        if (c.interval == VersionInterval.zero)
          c.preferred.contains(v)
        else
          c.interval.contains(v)
      }
    def minimumCompatibleVersion(version: String): String =
      version
  }

  /**
    * Early Semantic Versioning version reconciliation.
    */
  @deprecated("Use EarlySemVer or SemVerSpec instead. This will be removed in the future version.", "0.3.0")
  case object SemVer extends VersionCompatibility {
    def isCompatible(constraint: String, version: String): Boolean =
      EarlySemVer.isCompatible(constraint, version)
    def minimumCompatibleVersion(version: String): String =
      EarlySemVer.minimumCompatibleVersion(version)
  }

  /**
    * Early Semantic Versioning version reconciliation.
    */
  case object EarlySemVer extends VersionCompatibility {
    private def significativePartLength(v: Version): Int =
      if (v.items.headOption.exists(_.isEmpty)) 2 else 1
    def isCompatible(constraint: String, version: String): Boolean =
      constraint == version || {
        val c = VersionParse.versionConstraint(constraint)
        val v = Version(version)
        if (c.interval == VersionInterval.zero)
          c.preferred.exists { wanted =>
            val toCompare = significativePartLength(v)
            wanted.items.forall(_.isNumber) &&
            wanted.items.take(toCompare) == v.items.take(toCompare) && {
              import Ordering.Implicits._
              wanted.items.drop(toCompare) <= v.items.drop(toCompare)
            }
          }
        else
          c.interval.contains(v)
      }
    def minimumCompatibleVersion(version: String): String = {
      val v = Version(version)
      val toCompare = significativePartLength(v)
      val candidateOpt = Some(v.items.take(toCompare))
        .filter(_.forall(_.isNumber))
        .map(_.collect { case n: Version.Numeric => n })
        .map(items => items.map(_.repr).mkString("."))
        .filter(s => Version(s).compareTo(v) <= 0)
      candidateOpt.getOrElse(version)
    }
  }

  /**
    * Semantic versioning version reconciliation, closer to the semantic versioning spec.
    *
    * Unlike `SemVer`, assumes 0.x versions are not compatible with each other.
    */
  case object SemVerSpec extends VersionCompatibility {
    def isCompatible(constraint: String, version: String): Boolean =
      constraint == version || {
        val c = VersionParse.versionConstraint(constraint)
        val v = Version(version)
        if (c.interval == VersionInterval.zero)
          c.preferred.exists { wanted =>
            wanted.items.forall(_.isNumber) &&
            wanted.items.take(1) == v.items.take(1) &&
            v.items.take(1).exists(!_.isEmpty) && {
              import Ordering.Implicits._
              wanted.items.drop(1) <= v.items.drop(1)
            }
          }
        else
          c.interval.contains(v)
      }
    def minimumCompatibleVersion(version: String): String = {
      val v = Version(version)
      val candidateOpt = Some(v.items.take(1))
        .filter(items => items.nonEmpty && items.forall(_.isNumber) && items.forall(!_.isEmpty))
        .map(_.collect { case n: Version.Numeric => n })
        .map(items => items.map(_.repr).mkString("."))
        .filter(s => Version(s).compareTo(v) <= 0)
      candidateOpt.getOrElse(version)
    }
  }

  case object PackVer extends VersionCompatibility {
    def isCompatible(constraint: String, version: String): Boolean =
      constraint == version || {
        val c = VersionParse.versionConstraint(constraint)
        val v = Version(version)
        if (c.interval == VersionInterval.zero)
          c.preferred.exists(_.items.take(2) == v.items.take(2))
        else
          c.interval.contains(v)
      }
    def minimumCompatibleVersion(version: String): String = {
      val v = Version(version)
      val candidateOpt = Some(v.items.take(2))
        .filter(_.forall(_.isNumber))
        .map(_.collect { case n: Version.Numeric => n })
        .map(items => items.map(_.repr).mkString("."))
        .filter(s => Version(s).compareTo(v) <= 0)
      candidateOpt.getOrElse(version)
    }
  }

  def apply(input: String): Option[VersionCompatibility] =
    input match {
      case "default" => Some(Default)
      case "always" => Some(Always)
      case "strict" => Some(Strict)
      case "early-semver" => Some(EarlySemVer)
      case "semver-spec" => Some(SemVerSpec)
      case "pvp" => Some(PackVer)
      case "semver" => sys.error(s"""'semver' is ambiguous.
                                    |Based on the Semantic Versioning 2.0.0, 0.y.z updates are all initial development and thus
                                    |0.6.0 and 0.6.1 would NOT maintain any compatibility, but in Scala ecosystem it is
                                    |common to start adopting binary compatibility even in 0.y.z releases.
                                    |
                                    |Specify 'early-semver' for the early variant.
                                    |Specify 'semver-spec' for the spec-correct SemVer.""".stripMargin)
      case _ => None
    }
}
