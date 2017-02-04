package wav.devtools.karaf.mbeans

import javax.management.openmbean.{CompositeData, TabularData}

import org.apache.karaf

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, future}

object MBeanExtensions {

  private implicit class RichCompositeData(data: CompositeData) {
    def contains(key: String): Boolean =
      data.containsKey(key)

    def getValue[T](key: String): Option[T] =
      if (contains(key)) Some(data.get(key).asInstanceOf[T]) else None
  }

  private def get(table: TabularData, keys: AnyRef*): Option[CompositeData] =
    Option(table.get(keys.toArray))

  implicit class RichBundlesMBean(mbean: karaf.bundle.core.BundlesMBean) {

    def list: Set[Bundle] =
      (for  {
        entry <- mbean.getBundles.values.iterator
        b <- toBundle(entry.asInstanceOf[CompositeData])
      } yield b).toSet

    private def toBundle(data: CompositeData): Option[Bundle] =
      for {
        id <- data.getValue[Long]("ID")
        name <- data.getValue[String]("Name")
        version <- data.getValue[String]("Version")
        state <- data.getValue[String]("State")
      } yield Bundle(id.toInt, name, version, BundleState.withName(state))

  }

  implicit class RichSystemMBean(mbean: karaf.system.management.SystemMBean) {
    def awaitStartLevel(level: Int, atMost: FiniteDuration = MBeanConnection.DEFAULT_WAIT): Unit = {
      val f = future {
        while(mbean.getStartLevel < level)
          Thread.sleep(MBeanConnection.POLL_INTERVAL.toMillis)
      }
      Await.result(f,atMost)
    }
  }

}