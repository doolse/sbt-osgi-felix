package wav.devtools.karaf.manager

import java.io._

import wav.devtools.karaf.mbeans._
import concurrent.duration._

object KarafContainer {

  case class Configuration(
    url: ServiceUrl,
    containerArgs: ContainerArgs,
    workingDir: String,
    commandArgs: Seq[String])

  def Default = {
    val `karaf.base` = System.getProperty("karaf.base", "NOT_SET")
    assert(`karaf.base` != "NOT_SET", "karaf.base system property not set.")
    assert(new File(`karaf.base`).isDirectory(), `karaf.base` + " not found")
    val config = KarafContainer.createDefaultConfig(`karaf.base`)
    new KarafContainer(config)
  }

  def createDefaultConfig(karafHome: String, args: Seq[String] = Seq.empty): Configuration =
    Configuration(
       DefaultServiceUrl,
       DefaultContainerArgs,
       karafHome,
       javaArgs(karafHome, args))

  // see bin/karaf for up-to-date args.
  private def javaArgs(karafBase: String, args: Seq[String]): Seq[String] = {
    val bootDir = new File(new File(karafBase), "lib/boot")
    val classPath = filterFileNames(bootDir)(_.endsWith(".jar")).map(new File(bootDir, _))
    Seq(
       s"-classpath", classPath.mkString(File.pathSeparator),
       s"-Dkaraf.home=$karafBase",
       s"-Dkaraf.base=$karafBase",
       s"-Dkaraf.etc=$karafBase/etc",
       s"-Dkaraf.data=$karafBase/data",
       s"-Dkaraf.instances=$karafBase/instances",
       s"-Dkaraf.startLocalConsole=false",
       s"-Dkaraf.startRemoteShell=true",
       s"-Dkaraf.clean.all=true",
       s"-Djava.io.tmpdir=$karafBase/data/tmp",
       s"-Djava.util.logging.config.file=$karafBase/etc/java.util.logging.properties",
       s"-Djava.endorsed.dirs=$karafBase/lib/endorsed") ++
      args :+ s"org.apache.karaf.main.Main"
  }

  private def filterFileNames(dir: File)(filter: String => Boolean): Seq[String] =
    dir.list(new FilenameFilter() {
      override def accept(dir: File, name: String): Boolean = filter(name)
    })

}

import KarafContainer._

class KarafContainer(val config: Configuration) {

  val java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"

  val command = Seq(java) ++ config.commandArgs

  private var process: Process = null

  def start(): Unit = {
    val builder = new ProcessBuilder(command: _*)
    builder.directory(new File(config.workingDir))
    builder.redirectErrorStream(true)
    process = builder.start()
  }

  def stop(): Unit =
    if (process != null && process.isAlive) {
      val S = new ExtendedKarafJMXClient(config.containerArgs)
      S.System(_.halt())
      process.destroyForcibly()
    }

  def isAlive: Boolean = process != null && process.isAlive()

  // TODO: collect logs in a non-blocking fashion.
  def log: String =
    if (process == null) null
    else {
      var writer = new StringWriter()
      if (process.getInputStream().read() != -1) {
        org.apache.commons.io.IOUtils.copy(process.getInputStream(), writer, "UTF-8")
      }
      writer.toString()
    }

}


