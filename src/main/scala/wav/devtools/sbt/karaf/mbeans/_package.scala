package wav.devtools.karaf

import org.apache.karaf

package object mbeans {

  val DefaultServiceUrl = ServiceUrl("localhost", 1099, "karaf-root")
  val DefaultContainerArgs = ContainerArgs(DefaultServiceUrl.toString, "karaf", "karaf")

  val ConfigService   = new MBeanService("org.apache.karaf:type=config,name=*", classOf[karaf.config.core.ConfigMBean])
  val BundlesService  = new MBeanService("org.apache.karaf:type=bundle,name=*", classOf[karaf.bundle.core.BundlesMBean])
  val FeaturesService = new MBeanService("org.apache.karaf:type=feature,name=*", classOf[karaf.features.management.FeaturesServiceMBean])
  val SystemService   = new MBeanService("org.apache.karaf:type=system,name=*", classOf[karaf.system.management.SystemMBean])

}