# sbt-osgi-felix

So you like OSGi bundles for their ability to help you enforce modularisation and/or create an easily extensible system.

You also like Scala and think SBT is the best build tool for your needs.

**But..**

Getting hold of bundles of the latest versions of libraries isn't exactly easy. Many projects export their jars as bundles already
but the majority don't and some of the bundles that do might just have a slightly broken manifest.

There have been efforts in the past to get create bundle repositories with correct OSGi manifests but unfortunately they
just don't get updated enough or are already dead. See: http://ebr.springsource.com/repository/app/ and
http://www.osgi.org/Repository/HomePage

So this is where sbt-osgi-felix comes in.

## Features

* Use normal SBT libraryDependencies to create bundles
* Rewrite/Create Manifests from your dependencies (using BND)
* Put the dependencies into an OSGi Bundle Repository using [Apache Felix](http://felix.apache.org/)
* Validate the resolution of the bundles in the repository
* Lookup compilation/run/deployment dependencies using the [Felix Bundle Repository](http://felix.apache.org/documentation/subprojects/apache-felix-osgi-bundle-repository.html) resolver
* Run and deploy your code using the [Felix launcher](http://felix.apache.org/documentation/subprojects/apache-felix-framework/apache-felix-framework-launching-and-embedding.html)

## Quickstart

Add the plugin in `project/plugins.sbt`

TODO: when published

`build.sbt`

Include the settings for creating an OBR repository and resolving against it.

```scala
defaultSingleProjectSettings
```

Add your library dependencies to your build:

```scala
libraryDependencies ++= Seq(
  "org.elasticsearch" % "elasticsearch" % "1.2.1",
  "com.sonian" % "elasticsearch-zookeeper" % "1.2.0",
  "org.slf4j" % "slf4j-simple" % "1.7.12",
  "org.slf4j" % "slf4j-api" % "1.7.12",
  "org.slf4j" % "jcl-over-slf4j" % "1.7.12",
  "org.slf4j" % "log4j-over-slf4j" % "1.7.12",
  "org.apache.zookeeper" % "zookeeper" % "3.4.6")
```

Add your rewrite rules:

```scala
osgiRepositoryRules := Seq(
  rewrite("zookeeper", imports = "org.ieft.jgss.*,org.apache.log4j.jmx.*;resolution:=optional,*"),
  create("elasticsearch" | "lucene*", symbolicName = "elasticsearch", version = "1.2.1",
    imports = "com.vividsolutions.jts.*;resolution:=optional,org.hyperic.sigar;resolution:=optional,org.apache.regexp;resolution:=optional,*",
    exports = "org.apache.lucene.*,org.elasticsearch.*,org.tartarus.snowball.*"),
  ignoreAll("log4j", "slf4j-log4j12")
)
```

Finally, use the felix resolver to select jars for your compile path:
```
osgiDependencies in Compile:= packageReqs("org.elasticsearch.client")
```

## Repository definition

Rather than directly depending on the maven artifacts you define using `libraryDependencies`, jars will be indexed inside
an OSGi Bundle Repository created by felix. 

```scala
lazy val osgiRepositories = taskKey[Seq[Repository]]("Repository for resolving OSGi dependencies")
```
The default behaviour of this task is to take the ordered list of instructions from:
```scala
lazy val osgiRepositoryInstructions = taskKey[Seq[BundleInstructions]]("Instructions for building the bundles in the OBR repository")
```
Which is a list of instructions to either use an pre-existing bundle or rewrite/create a new bundle. This list is built from all jars in the `Compile` configuration and uses rules defined in the `osgiRepositoryRules` task key to determine what to do with each jar. 

```scala
lazy val osgiRepositoryRules = settingKey[Seq[InstructionFilter]]("Filters for generating BND instructions")
```
An `InstructionFilter` has a `DependencyFilter` for matching the artifacts and can designate one of three operations: 
* `Rewrite` will rewrite the manifest of a single jar leaving the rest of the jar untouched
* `Create` can combine one or more jars together and writes a manifest
* `Ignore` simply throws the jar away

By default, if a jar is not matched either:
* The jar is just used as is if it is already a bundle
* The jar is re-written with imports and exports of "*", and the symbolic name is generated from the `osgiNamePrefix` setting + module name, the maven version number is also used (if it's a number).

`Create` and `Rewrite` use a `ManifestInstructions` which are a simple representation of rules to pass to BND for generating the OSGi manifest: (additional properties can be added to `extraProperties`)

```scala
case class ManifestInstructions(imports: String = "*", exports: String = "*;version=VERSION",
                                privates: String = "", fragment: Option[String] = None,  extraProperties: Map[String, String] = Map.empty)
```
There are several helper functions for creating `InstructionFilter`s for the most common rules.

#### Examples
```scala
rewrite("zookeeper", imports = "org.ieft.jgss.*,org.apache.log4j.jmx.*;resolution:=optional,*")
```
Creates an `InstructionFilter` which will match any jar with `zookeeper` as it's maven module name and sets up the imports of the `ManifestInstructions`.

```scala
create("elasticsearch" | "lucene*", symbolicName = "elasticsearch", version = "1.2.1",
    imports = "com.vividsolutions.jts.*;resolution:=optional,org.hyperic.sigar;resolution:=optional,org.apache.regexp;resolution:=optional,*",
    exports = "org.apache.lucene.*,org.elasticsearch.*,org.tartarus.snowball.*")
```
This builds a new jar out of the jar with `elasticsearch` as it's name and any jars who's module name starts with `lucene`. When creating a jar you must specify the symbolicName and version to generate.

After generating all the jars and adding them to the OBR repository, a validation is done on the repository to make sure that all the bundles resolve correctly and will die with the reasons given if they don't.

## Depending on OSGi bundles

In order to depend on the bundles in the repository you must supply the felix resolver with some `OsgiRequirement`s
```scala
lazy val osgiDependencies = settingKey[Seq[OsgiRequirement]]("OSGi dependencies")
```

These need to be defined in the usual `Compile` and `Test` configurations and the jars will end up going on the `unmanagedClasspath`. Note: they should probably go on the `managedClasspath` but due to the IntelliJ Idea SBT support not recognising them properly without the original maven metadata, they are on the unmanaged.

Again there are a bunch of helper functions for the common resolving cases:
```scala
def packageReqs(name: String*)
def bundleReqs(name: String*)
def bundleReq(name: String, version: Option[String])
def packageReq(name: String, version: Option[String])
def fragmentsFor(name: String)
```

#### Examples
```scala 
osgiDependencies in Compile:= packageReqs("org.elasticsearch.client", "org.slf4j")
```
Will find the bundles which export the listed packages and add them to your classpath, no particular version is selected so the latest will be selected.
```scala 
osgiDependencies in Compile:= bundleReqs("org.eclipse.equinox.registry")
```
Chooses the bundle jar based only on it's `symbolicName`. It's definitely better to just depend on a package from the plugin instead however.
```scala 
osgiDependencies in Compile:= Seq(packageReq("scalaz", Some("[7.1,7.2)"))
```
Here we depend on the package `scalaz` but also specify a version range which must be satisfied. The range is in [OSGi version range format](http://stackoverflow.com/questions/8353771/osgi-valid-version-ranges).

## Running your bundles

At some point you'll probably need to run the code you've developed, which means starting up an OSGi Framework. 
OSGi doesn't really have the concept of a `static void main(String[] args)` method, instead you have `BundleActivator` 
and [Bundle Start Levels](http://eclipsesource.com/blogs/2009/06/10/osgi-and-start-levels/). 

This will override the default `run` task to start up an embedded Felix framework to run your bundles. The default 
configuration will start a framework by first creating "dev" OSGi manifests (using the standard sbt-osgi keys) in all 
your bundle projects classes folders and starting those bundles along with any of their required bundles. 

OSGi doesn't specify a simple way of passing "command line" type parameters to your application, so in the absence of 
that you can supply system properties by setting values in the `envVars` setting for the `run` task. *Note: OSGi does
however offer a service oriented configuration management service*.

#### Advanced run configuration

The default settings may not suit your needs so you may need to customise the start up configuration by overriding some 
of the following keys:

```scala
lazy val osgiRequiredBundles = taskKey[Seq[BundleLocation]]("OSGi bundles required for running")
lazy val osgiRunLevels = settingKey[Map[Int, Seq[BundleRequirement]]]("OSGi run level configuration")
lazy val osgiRunFrameworkLevel = settingKey[Int]("OSGi framework run level")
lazy val osgiRunDefaultLevel = settingKey[Int]("OSGi default run level")
lazy val osgiRunRequirements = settingKey[Seq[OsgiRequirement]]("OSGi runtime resolver requirements")

case class BundleStartConfig(start: Map[Int, Seq[ResolvedBundleLocation]],
                             install: Map[Int, Seq[ResolvedBundleLocation]],
                             extraSystemPackages: Iterable[String], frameworkStartLevel: Int = 1)
                             
lazy val osgiStartConfig = taskKey[BundleStartConfig]("OSGi framework start configuration")
```

The `run` task itself uses `osgiStartConfig` scope to the run task itself, however by default this is built by the 
following algorithm, *Note: wherever scoped is mentioned we're talking about the `run` task's scope*:

* Create a resolver from the `osgiRepositories` and bundles specified in the *scoped* `osgiBundles` task.
* Resolve all required bundles by resolving *scoped* `osgiRequiredBundles` and `osgiDependencies`.
* Use the default run level from `osgiDefaultLevel` for bundles unless overridden in `osgiRunLevels`.
* Set the framework start run level using `osgiRunFrameworkLevel`.

#### Advanced config example

```scala
osgiDependencies in run := Seq(fragmentsFor("slf4j.api"))
osgiRunFrameworkLevel := 6
osgiRunDefaultLevel := 3
envVars in run := Map("zookeeper.location" -> "localhost:2181",
  "cmd.args" -> "topics system,tomcat,syswait indexing,task,thumbnails")
osgiRunLevels := Map(
  1 -> bundleReqs("org.eclipse.equinox.common"),
  4 -> bundleReqs("org.eclipse.equinox.registry"),
  5 -> bundleReqs("com.foo.pluginsystem")
)
```

## Deploying a launchable OSGi framework

Unfortunately because the OSGi spec doesn't mandate a particular file format/layout for deploying a set of bundles as
an application, each of the OSGi framework vendors have had to create their own standard for deplying and launching 
an OSGi application. Some of the choices available are:

* The [Equinox](http://www.eclipse.org/equinox/framework/) (Eclipse) launcher - Native launchers and splash screens
* The [BND launcher](http://bnd.bndtools.org/chapters/300-launching.html) - Can create `java -jar` compatible archives. 
* The [Apache Felix launcher](http://felix.apache.org/documentation/subprojects/apache-felix-framework/apache-felix-framework-launching-and-embedding.html) - Simple no frills launcher

I've chosen the Felix launcher for this plugin but that is mainly because the bundle repository and resolver are already
being used and it's a simple enough process to write the directory layout required. The following task will create
a launchable directory and return you a ForkOptions object and Seq of command line parameters required for 
launching it.

```scala
lazy val osgiDeploy = taskKey[(File, ForkOptions, Seq[String])]("Deploy an OSGi launcher to directory")
```

### Packaging

In order to prevent clashes with the existing packaging tasks, an extra configuration has been included for packaging
up the launch directory as a .zip file. 

```scala
lazy val DeployLauncher = config("deployLauncher")
```

So you can create a .zip file by executing `deployLauncher:package`

**Note:** In order to use the generated zip, your launching scripts will need to be aware of the `java` command needed to
launch it, which in the case of Apache Felix is:

* `unzip <zipfile> -d launcher`
* `cd launcher`
* `java <extra props and jvm args> -jar lib/org.apache.felix.main-5.0.0.jar`

### Multi project configuration

So far all the examples have assumed using the default settings provided by `defaultSingleProjectSettings` but in the 
real world your application will be built of multiple bundles (it defeats the purpose of OSGi if you just use a single
set of sources!). So generally what you will want is a project layout like this:

* A single `root` project which:
  * Defines the third party dependencies needed by your application
  * Contains the run/deployment configuration
* Multiple projects which point back to the `root` project's OBR Repository for resolving dependencies and
  define their own bundles using `sbt-osgi` settings.
  
#### Sample advanced config

TODO
