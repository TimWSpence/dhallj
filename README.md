# Dhall for Java

[![Build status](https://img.shields.io/travis/travisbrown/dhallj/master.svg)](https://travis-ci.org/travisbrown/dhallj)
[![Gitter](https://img.shields.io/badge/gitter-join%20chat-green.svg)](https://gitter.im/dhallj/)
[![Maven Central](https://img.shields.io/maven-central/v/org.dhallj/dhall-core.svg)](https://maven-badges.herokuapp.com/maven-central/org.dhallj/dhall-core)

This project is an implementation of the [Dhall][dhall-lang] configuration language for the Java
Virtual Machine.

The core modules have no external dependencies and are compatible with Java 7 and later versions.
There are also several [Scala][scala] modules that are published for Scala 2.12 and 2.13.

This project has been supported in part by [Permutive][permutive]. Please see our
[monthly reports][permutive-medium] for updates on the work of the Permutive Community Engineering
team.

## Table of contents

* [Status](#status)
* [Getting started](#getting-started)
* [Converting to other formats](#converting-to-other-formats)
* [Import resolution](#import-resolution)
* [Command-line interface](#command-line-interface)
* [Other stuff](#other-stuff)
* [Developing](#developing)
* [Community](#community)
* [Copyright and license](#copyright-and-license)

## Status

We support [Dhall 15.0.0][dhall-15], including the `with` keyword and record puns.

We're running the [Dhall acceptance test suites][dhall-tests] for parsing, normalization,
[CBOR][cbor] encoding and decoding, hashing, and type inference (everything except imports), and
currently 1,139 of 1,143 tests are passing. There are three open issues that track the acceptance
tests that are not passing or that we're not running yet:
[#5](https://github.com/travisbrown/dhallj/issues/5),
[#6](https://github.com/travisbrown/dhallj/issues/6), and
[#8](https://github.com/travisbrown/dhallj/issues/8).

There are several known issues:

* The parser does not support [at least one known corner case](https://github.com/travisbrown/dhallj/issues/1).
* The parser [cannot parse deeply nested structures](https://github.com/travisbrown/dhallj/issues/2) (records, etc., although note that indefinitely long lists are fine).
* The type checker is [also not stack-safe](https://github.com/travisbrown/dhallj/issues/3) (this should be fixed soon).
* Exported JSON (or YAML) [doesn't exactly match `dhall-to-json`](https://github.com/travisbrown/dhallj/issues/4).
* In some cases printing Dhall expressions [produces invalid code](https://github.com/travisbrown/dhallj/issues/7).
* Import resolution is not provided in the core modules, and is a work in progress.

While we think the project is reasonably well-tested, it's very new, is sure to be full of bugs, and
nothing about the API should be considered stable at the moment. Please use responsibly.

## Getting started

The easiest way to try things out is to add the Scala wrapper module to your build.
If you're using [sbt][sbt] that would look like this:

```scala
libraryDependencies ++= "org.dhallj" %% "dhall-scala" % "0.1.0"
```

## Converting to other formats

DhallJ currently includes several ways to export Dhall expressions to other formats. The core module
includes very basic support for printing Dhall expressions as JSON:

```scala
scala> import org.dhallj.core.converters.JsonConverter
import org.dhallj.core.converters.JsonConverter

scala> import org.dhallj.parser.DhallParser.parse
import org.dhallj.parser.DhallParser.parse

scala> val expr = parse("(λ(n: Natural) → [n, n + 1, n + 2]) 100")
expr: org.dhallj.core.Expr.Parsed = (λ(n : Natural) → [n, n + 1, n + 2]) 100

scala> JsonConverter.toCompactString(expr.normalize)
res0: String = [100,101,102]
```

This conversion supports the same subset of Dhall expressions as [`dhall-to-json`][dhall-json] (e.g.
it can't produce JSON representation of functions, which means the normalization in the example
above is necessary—if we hadn't normalized the conversion would fail).

There's also a module that provides integration with [Circe][circe], allowing you to convert Dhall
expressions directly to (and from) `io.circe.Json` values without intermediate serialization to
strings:

```scala
scala> import org.dhallj.circe.Converter
import org.dhallj.circe.Converter

scala> import io.circe.syntax._
import io.circe.syntax._

scala> Converter(expr.normalize)
res0: Option[io.circe.Json] =
Some([
  100,
  101,
  102
])

scala> Converter(List(true, false).asJson)
res1: org.dhallj.core.Expr = [True, False]
```

Another module supports converting to any JSON representation for which you have a [Jawn][jawn]
facade. For example, the following build configuration would allow you to export [spray-json]
values:

```scala
libraryDependencies ++= Seq(
  "org.dhallj"    %% "dhall-jawn" % "0.1.0",
  "org.typelevel" %% "jawn-spray" % "1.0.0"
)
```

And then:

```scala
scala> import org.dhallj.jawn.JawnConverter
import org.dhallj.jawn.JawnConverter

scala> import org.typelevel.jawn.support.spray.Parser
import org.typelevel.jawn.support.spray.Parser

scala> val toSpray = new JawnConverter(Parser.facade)
toSpray: org.dhallj.jawn.JawnConverter[spray.json.JsValue] = org.dhallj.jawn.JawnConverter@be3ffe1d

scala> toSpray(expr.normalize)
res0: Option[spray.json.JsValue] = Some([100,101,102])
```

Note that unlike the dhall-circe module, the integration provided by dhall-jawn is only one way
(you can convert Dhall expressions to JSON values, but not the other way around).

We also support YAML export via [SnakeYAML][snake-yaml] (which doesn't require a Scala dependency):

```scala
scala> import org.dhallj.parser.DhallParser.parse
import org.dhallj.parser.DhallParser.parse

scala> import org.dhallj.yaml.YamlConverter
import org.dhallj.yaml.YamlConverter

scala> val expr = parse("{foo = [1, 2, 3], bar = [4, 5]}")
expr: org.dhallj.core.Expr.Parsed = {foo = [1, 2, 3], bar = [4, 5]}

scala> println(YamlConverter.toYamlString(expr))
foo:
- 1
- 2
- 3
bar:
- 4
- 5
```

It's not currently possible to convert to YAML without the SnakeYAML dependency, although we may support a simplified
version of this in the future (something similar to what we have for JSON in the core module).

## Import resolution

There are currently two modules that implement import resolution (to different degrees).

### dhall-imports

The first is dhall-imports, which is a Scala library built on [cats-effect] that uses [http4s] for
its HTTP client. This module is intended to be a complete implementation of the
[import resolution and caching specification][dhall-imports].

It requires a bit of ceremony to set up:

```scala
import cats.effect.{ContextShift, IO, Resource}
import org.dhallj.core.Expr
import org.dhallj.imports._
import org.dhallj.parser.DhallParser
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import scala.concurrent.ExecutionContext

implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

val client: Resource[IO, Client[IO]] = BlazeClientBuilder[IO](ExecutionContext.global).resource
```

And then if we have some definitions like this:

```scala
val concatSepImport = DhallParser.parse("https://prelude.dhall-lang.org/Text/concatSep")

val parts = DhallParser.parse("""["foo", "bar", "baz"]""")
val delimiter = Expr.makeTextLiteral("-")
```

We can use them with a function from the Dhall Prelude like this:

```scala
scala> val resolved = client.use { implicit c =>
     |   concatSepImport.resolveImports[IO]()
     | }
resolved: cats.effect.IO[org.dhallj.core.Expr] = IO$-633277477

scala> val result = resolved.map { concatSep =>
     |   Expr.makeApplication(concatSep, Array(delimiter, parts)).normalize
     | }
result: cats.effect.IO[org.dhallj.core.Expr] = <function1>

scala> result.unsafeRunSync
res0: org.dhallj.core.Expr = "foo-bar-baz"
```

(Note that we could use dhall-scala to avoid the use of `Array` above.)

### dhall-imports-mini

The other implementation is dhall-imports-mini, which is a Java library that
depends only on the core and parser modules, but that doesn't support
remote imports or caching.

The previous example could be rewritten as follows using dhall-imports-mini
and a local copy of the Prelude:

```scala
import org.dhallj.core.Expr
import org.dhallj.imports.mini.Resolver
import org.dhallj.parser.DhallParser

val concatSep = Resolver.resolve(DhallParser.parse("./dhall-lang/Prelude/Text/concatSep"), false)

val parts = DhallParser.parse("""["foo", "bar", "baz"]""")
val delimiter = Expr.makeTextLiteral("-")
```

And then:

```scala
scala> Expr.makeApplication(concatSep, Array(delimiter, parts)).normalize
res0: org.dhallj.core.Expr = "foo-bar-baz"
```

It's likely that eventually we'll provide a complete pure-Java implementation of import resolution,
but this isn't currently a high priority for us.

## Command-line interface

We include a command-line interface that supports some common operations. It's currently similar to
the official `dhall` and `dhall-to-json` binaries, but with many fewer options.

If [GraalVM Native Image][graal-native-image] is available on your system, you can build the CLI as
a native binary (thanks to [sbt-native-packager]).

```bash
$ sbt cli/graalvm-native-image:packageBin

$ cd cli/target/graalvm-native-image/

$ du -h dhall-cli
8.2M    dhall-cli

$ time ./dhall-cli hash --normalize --alpha <<< "λ(n: Natural) → [n, n + 1]"
sha256:a8d9326812aaabeed29412e7b780dc733b1e633c5556c9ea588e8212d9dc48f3

real    0m0.009s
user    0m0.000s
sys     0m0.009s

$ time ./dhall-cli type <<< "{foo = [1, 2, 3]}"
{foo : List Natural}

real    0m0.003s
user    0m0.000s
sys     0m0.003s

$ time ./dhall-cli json <<< "{foo = [1, 2, 3]}"
{"foo":[1,2,3]}

real    0m0.005s
user    0m0.004s
sys     0m0.001s
```

Even on the JVM it's close to usable, although you can definitely feel the slow startup:

```bash
$ cd ..

$ time java -jar ./cli-assembly-0.1.0-SNAPSHOT.jar hash --normalize --alpha <<< "λ(n: Natural) → [n, n + 1]"
sha256:a8d9326812aaabeed29412e7b780dc733b1e633c5556c9ea588e8212d9dc48f3

real    0m0.104s
user    0m0.106s
sys     0m0.018s
```

There's probably not really any reason you'd want to use `dhall-cli` right now, but I think it's a
pretty neat demonstration of how Graal can make Java (or Scala) a viable language for building
native CLI applications.

## Other stuff

### dhall-testing

The dhall-testing module provides support for property-based testing with [ScalaCheck][scalacheck]
in the form of `Arbitrary` (and `Shrink`) instances:

```scala
scala> import org.dhallj.core.Expr
import org.dhallj.core.Expr

scala> import org.dhallj.testing.instances._
import org.dhallj.testing.instances._

scala> import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary

scala> Arbitrary.arbitrary[Expr].sample
res0: Option[org.dhallj.core.Expr] = Some(Optional (Optional (List Double)))

scala> Arbitrary.arbitrary[Expr].sample
res1: Option[org.dhallj.core.Expr] = Some(Optional (List <neftfEahtuSq : Double | kg...
```

It includes (fairly basic) support for producing both well-typed and probably-not-well-typed
expressions, and for generating arbitrary values of specified Dhall types:

```scala
scala> import org.dhallj.testing.WellTypedExpr
import org.dhallj.testing.WellTypedExpr

scala> Arbitrary.arbitrary[WellTypedExpr].sample
res2: Option[org.dhallj.testing.WellTypedExpr] = Some(WellTypedExpr(8436008296256993755))

scala> genForType(Expr.Constants.BOOL).flatMap(_.sample)
res3: Option[org.dhallj.core.Expr] = Some(True)

scala> genForType(Expr.Constants.BOOL).flatMap(_.sample)
res4: Option[org.dhallj.core.Expr] = Some(False)

scala> genForType(Expr.makeApplication(Expr.Constants.LIST, Expr.Constants.INTEGER)).flatMap(_.sample)
res5: Option[org.dhallj.core.Expr] = Some([+1522471910085416508, -9223372036854775809, ...
```

This module is currently fairly minimal, and is likely to change substantially in future releases.

### dhall-javagen and dhall-prelude

The dhall-javagen module lets you take a DhallJ representation of a Dhall expression and use it to
generate Java code that will build the DhallJ representation of that expression.

This is mostly a toy, but it allows us for example to distribute a "pre-compiled" jar containing the
Dhall Prelude:

```scala
scala> import java.math.BigInteger
import java.math.BigInteger

scala> import org.dhallj.core.Expr
import org.dhallj.core.Expr

scala> val ten = Expr.makeNaturalLiteral(new BigInteger("10"))
ten: org.dhallj.core.Expr = 10

scala> val Prelude = org.dhallj.prelude.Prelude.instance
Prelude: org.dhallj.core.Expr = ...

scala> val Natural = Expr.makeFieldAccess(Prelude, "Natural")
Natural: org.dhallj.core.Expr = ...

scala> val enumerate = Expr.makeFieldAccess(Natural, "enumerate")
enumerate: org.dhallj.core.Expr = ...

scala> Expr.makeApplication(enumerate, ten).normalize
res0: org.dhallj.core.Expr = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]
```

Note that the resulting jar (which is available from Maven Central as dhall-prelude) is many times
smaller than either the Prelude source or the Prelude serialized as CBOR.

## Developing

The project includes the currently-supported version of the Dhall language repository as a
submodule, so if you want to run the acceptance test suites, you'll need to clone recursively:

```bash
git clone --recurse-submodules git@github.com:travisbrown/dhallj.git
```

Or if you're like me and always forget to do this, you can initialize the submodule after cloning:

```bash
git submodule update --init
```

This project is built with [sbt][sbt], and you'll need to have sbt [installed][sbt-installation]
on your machine.

We're using the [JavaCC][javacc] parser generator for the parsing module, and we have
[our own sbt plugin][sbt-javacc] for integrating JavaCC into our build. This plugin is open source
and published to Maven Central, so you don't need to do anything to get it, but you will need to run
it manually the first time you build the project (or any time you update the JavaCC grammar):

```
sbt:root> javacc
Java Compiler Compiler Version 7.0.5 (Parser Generator)
File "Provider.java" does not exist.  Will create one.
File "StringProvider.java" does not exist.  Will create one.
File "StreamProvider.java" does not exist.  Will create one.
File "TokenMgrException.java" does not exist.  Will create one.
File "ParseException.java" does not exist.  Will create one.
File "Token.java" does not exist.  Will create one.
File "SimpleCharStream.java" does not exist.  Will create one.
Parser generated with 0 errors and 1 warnings.
[success] Total time: 0 s, completed 12-Apr-2020 08:48:53
```

After this is done, you can run the tests:

```
sbt:root> test
...
[info] Passed: Total 1319, Failed 0, Errors 0, Passed 1314, Skipped 5
[success] Total time: 36 s, completed 12-Apr-2020 08:51:07
```

Note that a few tests require the [dhall-haskell] `dhall` CLI. If you don't have it installed on
your machine, these tests will be skipped.

There are also a few additional slow tests that must be run manually:

```
sbt:root> slow:test
...
[info] Passed: Total 4, Failed 0, Errors 0, Passed 4
[success] Total time: 79 s (01:19), completed 12-Apr-2020 08:52:41
```

## Community

This project supports the [Scala code of conduct][code-of-conduct] and wants all of its channels
(Gitter, GitHub, etc.) to be inclusive environments.

## Copyright and license

All code in this repository is available under the [3-Clause BSD License][bsd-license].

Copyright [Travis Brown][travisbrown] and [Tim Spence][timspence], 2020.

[bsd-license]: https://opensource.org/licenses/BSD-3-Clause
[cats-effect]: https://github.com/typelevel/cats-effect
[cbor]: https://cbor.io/
[circe]: https://github.com/circe/circe
[code-of-conduct]: https://www.scala-lang.org/conduct/
[dhall-15]: https://github.com/dhall-lang/dhall-lang/releases/tag/v15.0.0
[dhall-haskell]: https://github.com/dhall-lang/dhall-haskell
[dhall-imports]: https://github.com/dhall-lang/dhall-lang/blob/master/standard/imports.md
[dhall-json]: https://docs.dhall-lang.org/tutorials/Getting-started_Generate-JSON-or-YAML.html
[dhall-tests]: https://github.com/dhall-lang/dhall-lang/tree/master/tests
[dhall-lang]: https://dhall-lang.org/
[discipline]: https://github.com/typelevel/discipline
[graal-native-image]: https://www.graalvm.org/docs/reference-manual/native-image/
[http4s]: https://http4s.org
[javacc]: https://javacc.github.io/javacc/
[jawn]: https://github.com/typelevel/jawn
[permutive]: https://permutive.com
[permutive-medium]: https://medium.com/permutive
[sbt]: https://www.scala-sbt.org
[sbt-installation]: https://www.scala-sbt.org/1.x/docs/Setup.html
[sbt-javacc]: https://github.com/travisbrown/sbt-javacc
[sbt-native-packager]: https://github.com/sbt/sbt-native-packager
[scala]: https://www.scala-lang.org
[scalacheck]: https://www.scalacheck.org/
[snake-yaml]: https://bitbucket.org/asomov/snakeyaml/
[spray-json]: https://github.com/spray/spray-json
[timspence]: https://github.com/TimWSpence
[travisbrown]: https://twitter.com/travisbrown
