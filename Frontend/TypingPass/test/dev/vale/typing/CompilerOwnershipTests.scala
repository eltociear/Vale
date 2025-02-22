package dev.vale.typing

import dev.vale._
import OverloadResolver.FindFunctionFailure
import dev.vale.postparsing.CodeNameS
import dev.vale.typing.ast.RestackifyTE
import dev.vale.typing.env.ReferenceLocalVariableT
import dev.vale.typing.names.CodeVarNameT
import dev.vale.vassert
import dev.vale.typing.templata._
import dev.vale.typing.types._
import org.scalatest.{FunSuite, Matchers}

import scala.collection.immutable.List
import scala.io.Source

class CompilerOwnershipTests extends FunSuite with Matchers {
  // TODO: pull all of the typingpass specific stuff out, the unit test-y stuff

  def readCodeFromResource(resourceFilename: String): String = {
    val is = Source.fromInputStream(getClass().getClassLoader().getResourceAsStream(resourceFilename))
    vassert(is != null)
    is.mkString("")
  }


  test("Parenthesized method syntax will move instead of borrow") {
    val compile = CompilerTestCompilation.test(
      """
        |
        |struct Bork { a int; }
        |func doSomething(bork Bork) int {
        |  return bork.a;
        |}
        |func main() int {
        |  bork = Bork(42);
        |  return (bork).doSomething();
        |}
        |""".stripMargin)
    val coutputs = compile.expectCompilerOutputs()
  }

  test("Calling a method on a returned own ref will supply owning arg") {
    val compile = CompilerTestCompilation.test(
      """
        |
        |struct Bork { a int; }
        |func doSomething(bork Bork) int {
        |  return bork.a;
        |}
        |func main() int {
        |  return Bork(42).doSomething();
        |}
        |""".stripMargin)
    val coutputs = compile.expectCompilerOutputs()
  }

  test("Explicit borrow method call") {
    val compile = CompilerTestCompilation.test(
      """
        |
        |struct Bork { a int; }
        |func doSomething(bork &Bork) int {
        |  return bork.a;
        |}
        |func main() int {
        |  return Bork(42)&.doSomething();
        |}
        |""".stripMargin)
    val coutputs = compile.expectCompilerOutputs()
  }

  test("Calling a method on a local will supply borrow ref") {
    val compile = CompilerTestCompilation.test(
      """
        |
        |struct Bork { a int; }
        |func doSomething(bork &Bork) int {
        |  return bork.a;
        |}
        |func main() int {
        |  bork = Bork(42);
        |  return bork.doSomething();
        |}
        |""".stripMargin)
    val coutputs = compile.expectCompilerOutputs()
  }

  test("Calling a method on a member will supply borrow ref") {
    val compile = CompilerTestCompilation.test(
      """
        |
        |struct Zork { bork Bork; }
        |struct Bork { a int; }
        |func doSomething(bork &Bork) int {
        |  return bork.a;
        |}
        |func main() int {
        |  zork = Zork(Bork(42));
        |  return zork.bork.doSomething();
        |}
        |""".stripMargin)
    val coutputs = compile.expectCompilerOutputs()
  }

  test("No derived or custom drop gives error") {
    val compile = CompilerTestCompilation.test(
      """
        |
        |
        |#!DeriveStructDrop
        |struct Muta { }
        |
        |exported func main() {
        |  Muta();
        |}
      """.stripMargin)
    compile.getCompilerOutputs().expectErr() match {
      case CouldntFindFunctionToCallT(_, FindFunctionFailure(CodeNameS(StrI("drop")), _, _)) =>
    }
  }

  test("Opt with undroppable contents") {
    val compile = CompilerTestCompilation.test(
      """
        |#!DeriveInterfaceDrop
        |sealed interface Opt<T> where T Ref { }
        |
        |#!DeriveStructDrop
        |struct Some<T> where T Ref { value T; }
        |
        |impl<T> Opt<T> for Some<T>;
        |
        |abstract func drop<T>(virtual opt Opt<T>)
        |where func drop(T)void;
        |
        |func drop<T>(opt Some<T>)
        |where func drop(T)void
        |{
        |  [x] = opt;
        |}
        |
        |abstract func get<T>(virtual opt Opt<T>) T;
        |func get<T>(opt Some<T>) T {
        |  [value] = opt;
        |  return value;
        |}
        |
        |#!DeriveStructDrop
        |struct Spaceship { }
        |
        |exported func main() {
        |  s Opt<Spaceship> = Some<Spaceship>(Spaceship());
        |  // Drops the ship manually
        |  [ ] = (s).get();
        |}
        |
        |""".stripMargin)
    compile.expectCompilerOutputs()
  }

  test("Opt with undroppable mutable ref contents") {
    // This is here because we had a bug where if we had a Opt<&T> and there was no drop(T)
    // it would error. It should be fine dropping a &T because any borrow is droppable.

    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.drop.*;
        |
        |#!DeriveInterfaceDrop
        |sealed interface Opt<T Ref> { }
        |
        |#!DeriveStructDrop
        |struct Some<T Ref> { value T; }
        |
        |impl<T> Opt<T> for Some<T>;
        |
        |abstract func drop<T>(virtual opt Opt<T>)
        |where func drop(T)void;
        |
        |func drop<T>(opt Some<T>)
        |where func drop(T)void
        |{
        |  [x] = opt;
        |}
        |
        |#!DeriveStructDrop
        |struct Spaceship { }
        |
        |struct ContainerWithDerivedDrop {
        |  maybeThing Opt<&Spaceship>;
        |}
        |
        |exported func main() {
        |  ship = Spaceship();
        |  c = ContainerWithDerivedDrop(Some<&Spaceship>(&ship));
        |  // Drops c automatically here. This should work, because it found a drop(&Spaceship)
        |  // specifically from builtins' drop.vale.
        |
        |  // And we'll manually drop this, though its not really what the test is testing.
        |  [ ] = ship;
        |}
        |
        |""".stripMargin)
    compile.expectCompilerOutputs()
  }

  test("Restackify") {
    // Allow set on variables that have been moved already, which is useful for linear style.
    val compile =
      CompilerTestCompilation.test(readCodeFromResource("programs/restackify.vale"))
    val main = compile.expectCompilerOutputs().lookupFunction("main")
    Collector.only(main, {
      case RestackifyTE(ReferenceLocalVariableT(CodeVarNameT(StrI("ship")), _, _), _) =>
    })
  }

  test("Loop restackify") {
    // Allow set on variables that have been moved already, which is useful for linear style.
    val compile =
      CompilerTestCompilation.test(readCodeFromResource("programs/loop_restackify.vale"))
    val main = compile.expectCompilerOutputs().lookupFunction("main")
    Collector.only(main, {
      case RestackifyTE(ReferenceLocalVariableT(CodeVarNameT(StrI("ship")), _, _), _) =>
    })
  }

  test("Destructure restackify") {
    // Allow set on variables that have been moved already, which is useful for linear style.
    val compile =
      CompilerTestCompilation.test(readCodeFromResource("programs/destructure_restackify.vale"))
    val main = compile.expectCompilerOutputs().lookupFunction("main")
    Collector.only(main, {
      case RestackifyTE(ReferenceLocalVariableT(CodeVarNameT(StrI("ship")), _, _), _) =>
    })
  }
}

