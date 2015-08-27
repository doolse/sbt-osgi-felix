# sbt-osgi-felix

So you like OSGi bundles for their ability to help you enforce modularisation.

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

scalaVersion := "2.11.6"
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
lazy val osgiRepository = taskKey[Repository]("Repository for resolving OSGi dependencies")
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
osgiDependencies in Compile:= bundleReqs(""org.eclipse.equinox.registry")
```
Chooses the bundle jar based only on it's `symbolicName`. It's definitely better to just depend on a package from the plugin instead however.
```scala 
osgiDependencies in Compile:= Seq(packageReq("scalaz", Some("[7.1,7.2)"))
```
Here we depend on the package `scalaz` but also specify a version range which must be satisfied. The range is in [OSGi version range format](http://stackoverflow.com/questions/8353771/osgi-valid-version-ranges).

## Running and Deployment

TODO



