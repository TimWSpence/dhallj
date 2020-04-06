package org.dhallj.imports

import org.dhallj.parser.Dhall.parse
import org.dhallj.imports._
import cats.effect.{ContextShift, IO, Resource}
import cats.implicits._
import munit.FunSuite
import org.dhallj.core.Expr
import org.http4s.client.blaze._
import org.http4s.client._

import scala.concurrent.ExecutionContext.global

class ImportResolutionSuite extends FunSuite {

  implicit val cs: ContextShift[IO] = IO.contextShift(global)

  implicit val client: Resource[IO, Client[IO]] = BlazeClientBuilder[IO](global).resource

  test("Local import") {
    val expr = parse("let x = /local/package.dhall in x")
    val expected = parse("let x = 1 in x").normalize

    assert(resolve(expr) == expected)
  }

  test("Local -> local relative import") {
    val expr = parse("let x = /local-local-relative/package.dhall in x")
    val expected = parse("let x = 1 in x").normalize

    assert(resolve(expr) == expected)
  }

  test("Local -> local absolute import") {
    val expr = parse("let x = /local-local-absolute/package.dhall in x")
    val expected = parse("let x = 1 in x").normalize

    assert(resolve(expr) == expected)
  }

  test("Local -> remote import") {
    val expr = parse("let any = /local-remote/package.dhall in any Natural Natural/even [2,3,5]")
    val expected = parse("True").normalize

    assert(resolve(expr) == expected)
  }

  test("Remote import") {
    val expr = parse(
      "let any = https://raw.githubusercontent.com/dhall-lang/dhall-lang/master/Prelude/List/any in any Natural Natural/even [2,3,5]"
    )
    val expected = parse("True").normalize

    assert(resolve(expr) ==  expected)
  }

  test("Multiple imports") {
    val expr = parse("let x = /multiple-imports/package.dhall in x")
    val expected = parse("let x = [1,2] in x").normalize

    assert(resolve(expr) == expected)
  }

  test("Import as text") {
    val expr = parse("let x = /text-import/package.dhall as Text in x")
    val expected = parse("\"let x = 1 in x\"").normalize

    assert(resolve(expr) == expected)
  }

  test("Import as local location") {
    val expr = parse("let x = /foo/bar.dhall as Location in x")
    val expected = parse("< Local : Text | Remote : Text | Environment : Text | Missing >.Local \"/foo/bar.dhall\"").normalize

    assert(resolve(expr) == expected)
  }

  test("Import as remote location") {
    val expr = parse("let x = http://example.com/foo.dhall as Location in x")
    val expected = parse("< Local : Text | Remote : Text | Environment : Text | Missing >.Remote \"http://example.com/foo.dhall\"").normalize

    assert(resolve(expr) == expected)
  }

  test("Import as env location") {
    val expr = parse("let x = env:foo as Location in x")
    val expected = parse("< Local : Text | Remote : Text | Environment : Text | Missing >.Environment \"foo\"").normalize

    assert(resolve(expr) == expected)
  }

  test("Cyclic imports".fail) {
    val expr = parse("let x = /cyclic-relative-paths/package.dhall in x")
    val expected = parse("True").normalize

    assert(resolve(expr) == expected)
  }

  test("Cyclic imports - all relative".fail) {
    val expr = parse("let x = /cyclic-relative-paths/package.dhall in x")
    val expected = parse("True").normalize

    assert(resolve(expr) == expected)
  }

  private def resolve(e: Expr): Expr =
    client.use { c =>
      implicit val http: Client[IO] = c

      e.resolveImports[IO](ResolutionConfig(FromResources)).map(_.normalize)
    }.unsafeRunSync

}
