package wav.devtools.sbt

import scala.util.{Failure, Success, Try}

package object karaf {

  private[karaf] implicit def handled[T](`try`: Try[T]): T =
    `try` match {
      case Success(o) => o
      case Failure(t) => sys.error(t.fillInStackTrace().toString)
    }

}
