package osgifelix

import java.io.File
import java.net.URI

import org.apache.felix.bundlerepository.{Capability, Resource}

/**
 * Created by jolz on 26/08/15.
 */
object BundleLocation {
  def apply(file: File): BundleLocation = BundleLocation(file, {
    val uriString = file.toURI.toString
    if (file.isDirectory) {
      "reference:" + uriString
    } else uriString
  })
}

case class BundleLocation(file: File, location: String)

object ResolvedBundleLocation {
  def apply(file: File):ResolvedBundleLocation = ResolvedBundleLocation(BundleLocation(file), true)
  def apply(resource: Resource):ResolvedBundleLocation = ResolvedBundleLocation(BundleLocation(new File(URI.create(resource.getURI))),
    resource.getCapabilities.exists(_.getName == Capability.FRAGMENT))
}

case class ResolvedBundleLocation(bl: BundleLocation, fragment: Boolean)
