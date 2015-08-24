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

* Use normal SBT libraryDependencies to get your dependencies
* Rewrite manifests & Create bundles from your dependencies (using BND)
* Put the dependencies into an OSGi Bundle Repository using [Apache Felix](http://felix.apache.org/)
* Validate the bundles resolution in the OBR repository
* Lookup compilation dependencies using the [Felix Bundle Repository](http://felix.apache.org/documentation/subprojects/apache-felix-osgi-bundle-repository.html) resolver
* Run your code using the [Felix launcher](http://felix.apache.org/documentation/subprojects/apache-felix-framework/apache-felix-framework-launching-and-embedding.html)
* Deploy your project as a runnable jar

## Quickstart

Add the plugin in `project/plugins.sbt`

```
TODO: when published
```

Add your library dependencies to your build:

`build.sbt`

```
import osgifelix._



libraryDependencies ++= Seq(
      "org.elasticsearch" % "elasticsearch" % "1.2.1",
      "com.sonian" % "elasticsearch-zookeeper" % "1.2.0",
      "org.apache.zookeeper" % "zookeeper" % "3.4.6")

```

Add your rewrite rules:

```
osgiFilterRules := Seq(
    rewrite("zookeeper", imports = "org.ieft.jgss.*,org.apache.log4j.jmx.*;resolution:=optional,*"),
    create("elasticsearch" | "lucene*", symbolicName = "elasticsearch", version = "1.2.1", "com.vividsolutions.jts.*;resolution:=optional,org.hyperic.sigar;resolution:=optional,org.apache.regexp;resolution:=optional,*", "org.apache.lucene.*,org.elasticsearch.*,org.tartarus.snowball.*")
)
```