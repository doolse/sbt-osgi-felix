package wav.devtools.karaf.mbeans

import java.io.IOException
import java.rmi.NoSuchObjectException
import java.util.HashMap
import javax.management.remote.{JMXConnector, JMXConnectorFactory, JMXServiceURL}
import javax.management.{JMX, ObjectInstance, ObjectName}
import javax.naming.{ServiceUnavailableException, CommunicationException}
import collection.JavaConversions.asScalaSet
import concurrent.{Await, future}
import concurrent.duration._
import util.{Success, Failure, Try}
import concurrent.ExecutionContext.Implicits.global

object MBeanConnection {

  val POLL_INTERVAL = 500.millis

  val DEFAULT_WAIT = 30.seconds

  @throws(classOf[Exception])
  def tryConnect(creds: ContainerArgs, atMost: FiniteDuration = MBeanConnection.DEFAULT_WAIT): Try[JMXConnector] = {
    val environment = new HashMap[String, Array[String]]
    environment.put(JMXConnector.CREDENTIALS, Array[String](creds.user, creds.pass))
    val f = future {
      var result: Try[JMXConnector] = Failure(new ServiceUnavailableException(s"Could not connect to ${creds.serviceUrl}"))
      while(result.isFailure) {
        result = Try(JMXConnectorFactory.connect(new JMXServiceURL(creds.serviceUrl), environment))
        if (result.isFailure) Thread.sleep(POLL_INTERVAL.toMillis)
      }
      result
    }
    Await.result(f,atMost)
  }

  @throws(classOf[Exception])
  def tryGet[T](connector: JMXConnector, query: String, clazz: Class[T], atMost: FiniteDuration): Try[T] = {
    val f = future {
      var result: Try[T] = Failure(new NoSuchObjectException(s"${clazz.getName} not found, query: $query"))
      while(result.isFailure) {
        try {
          val mbsc = connector.getMBeanServerConnection
          val names: Set[ObjectInstance] = mbsc.queryMBeans(new ObjectName(query), null).toSet
          if (names.toSeq.length > 0)
            result = Success(JMX.newMBeanProxy(mbsc, names.toSeq(0).getObjectName, clazz, true))
        }
        if (result.isFailure) Thread.sleep(POLL_INTERVAL.toMillis)
      }
      result
    }
    Await.result(f,atMost)
  }

}

class MBeanService[T](val mbeanQuery: String, val clazz: Class[T]) {
  def newInvoker(connector: () => Try[JMXConnector]): MBeanInvoker[T] =
    new MBeanInvoker(this,connector)
}

class MBeanInvoker[T](s: MBeanService[T], connector: () => Try[JMXConnector]) {

  private def closeThenFail[T](c: JMXConnector): PartialFunction[Throwable, Try[T]] =
    { case t: Throwable => c.close(); Failure(t)}

  def apply[R](f: T => R, atMost: FiniteDuration = MBeanConnection.DEFAULT_WAIT): Try[R] =
    for {
      c <- connector()
      _ = require(c != null, "Connection established")
      mbean <- MBeanConnection.tryGet[T](c, s.mbeanQuery, s.clazz, atMost)
        .recoverWith(closeThenFail(c))
      result <- Try(f(mbean))
        .recoverWith(closeThenFail(c))
      _ = c.close()
    } yield result

}

case class Bundle(bundleId: Int, name: String, version: String, state: BundleState.Value)

case class ContainerArgs(serviceUrl: String, user: String, pass: String)

object ServiceUrl {

  val Pattern = """service:jmx:rmi:///jndi/rmi://(.*):(.*)/(.*)""".r

  def unapply(url: String): Option[ServiceUrl] =
    url match {
      case Pattern(host, port, instanceName) =>
        Some(ServiceUrl(host, port.toInt, instanceName))
      case _ => None
    }
}

case class ServiceUrl(host: String, port: Int, instanceName: String) {
  override def toString: String =
    s"service:jmx:rmi:///jndi/rmi://${host}:${port}/${instanceName}"
}

object BundleState extends Enumeration {
  val Error = Value("Error")
  val Uninstalled = Value("Uninstalled")
  val Installed = Value("Installed")
  val Starting = Value("Starting")
  val Stopping = Value("Starting")
  val Resolved = Value("Resolved")
  val Active = Value("Active")

  private val lifecycle = Seq(Error, Installed, Resolved, Active)

  def inState(expected: BundleState.Value, actual: BundleState.Value): Boolean =
    lifecycle.indexOf(actual) >= lifecycle.indexOf(expected)
}