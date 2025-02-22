package dev.vale.postparsing

import dev.vale.{Err, FileCoordinateMap, Interner, Ok, RangeS, SourceCodeUtils, StrI, vassertSome, vfail, vregionmut}
import dev.vale.options.GlobalOptions
import dev.vale.parsing._
import dev.vale.postparsing._
import org.scalatest.{FunSuite, Matchers}

import scala.collection.immutable.List

class PostParsingRuleTests extends FunSuite with Matchers {
  private def compile(code: String, interner: Interner = new Interner()): ProgramS = {
    val compile = PostParserTestCompilation.test(code, interner)
    compile.getScoutput() match {
      case Err(e) => {
        val codeMap = compile.getCodeMap().getOrDie()
        vfail(PostParserErrorHumanizer.humanize(
          SourceCodeUtils.humanizePos(codeMap, _),
          SourceCodeUtils.linesBetween(codeMap, _, _),
          SourceCodeUtils.lineRangeContaining(codeMap, _),
          SourceCodeUtils.lineContaining(codeMap, _),
          e))
      }
      case Ok(t) => t.expectOne()
    }
  }

  private def compileForError(code: String): ICompileErrorS = {
    PostParserTestCompilation.test(code).getScoutput() match {
      case Err(e) => e
      case Ok(t) => vfail("Successfully compiled!\n" + t.toString)
    }
  }

  test("Predict simple templex") {
    val program =
      compile(
        """
          |func main(a int) {}
          |""".stripMargin)
    val main = program.lookupFunction("main")

    vassertSome(main.runeToPredictedType.get(main.params.head.pattern.coordRune.get.rune)) shouldEqual
      CoordTemplataType()
  }

  test("Can know rune type from simple equals") {
    val interner = new Interner()
    val program =
      compile(
        """
          |func main<T, Y>(a T)
          |where Y = T {}
          |""".stripMargin, interner)
    val main = program.lookupFunction("main")

    vassertSome(main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("T"))))) shouldEqual
      CoordTemplataType()
    vassertSome(main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("Y"))))) shouldEqual
      CoordTemplataType()
  }

  test("Predict knows type from Or rule") {
    val interner = new Interner()
    val program =
      compile(
        """
          |func main<M Ownership>(a int)
          |where M = any(own, borrow) {}
          |""".stripMargin, interner)
    val main = program.lookupFunction("main")

    vassertSome(main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("M"))))) shouldEqual
      OwnershipTemplataType()
  }

  test("Predict CoordComponent types") {
    val interner = new Interner()
    vregionmut() // Put back in with regions
    // val program =
    //   compile(
    //     """
    //       |func main<T>(a T)
    //       |where T = Ref[O, R, K], O Ownership, R Region, K Kind {}
    //       |""".stripMargin, interner)
    // Take out with regions
    val program =
      compile(
        """
          |func main<T>(a T)
          |where T = Ref[O, K], O Ownership, K Kind {}
          |""".stripMargin, interner)
    val main = program.lookupFunction("main")

    vassertSome(main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("T"))))) shouldEqual CoordTemplataType()
    vassertSome(main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("O"))))) shouldEqual OwnershipTemplataType()
    vregionmut() // Put back in with regions
    // vassertSome(main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("R"))))) shouldEqual RegionTemplataType()
    vassertSome(main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("K"))))) shouldEqual KindTemplataType()
  }

  test("Predict Call types") {
    val interner = new Interner()
    val program =
      compile(
        """
          |func main<A, B>(p1 A, p2 B)
          |where A = T<B>, T = Option, A = int {}
          |""".stripMargin, interner)
    val main = program.lookupFunction("main")

    vassertSome(main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("A"))))) shouldEqual CoordTemplataType()
    vassertSome(main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("B"))))) shouldEqual CoordTemplataType()
    // We can't know if T it's a Coord->Coord or a Coord->Kind type.
    main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("T")))) shouldEqual None
  }

  // Not sure if this test is useful anymore, since we say M, V, N's types up-front now
  test("Predict array sequence types") {
    val interner = new Interner()
    val program =
      compile(
        """
          |func main<M Mutability, V Variability, N Int, E>(t T)
          |where T Ref = [#N]<M, V>E {}
          |""".stripMargin, interner)
    val main = program.lookupFunction("main")

    vassertSome(main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("M"))))) shouldEqual MutabilityTemplataType()
    vassertSome(main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("V"))))) shouldEqual VariabilityTemplataType()
    vassertSome(main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("N"))))) shouldEqual IntegerTemplataType()
    vassertSome(main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("E"))))) shouldEqual CoordTemplataType()
    vassertSome(main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("T"))))) shouldEqual CoordTemplataType()
  }

  // Not sure if this test is useful anymore, since we say Kind up-front now
  test("Predict for isInterface") {
    val interner = new Interner()
    val program =
      compile(
        """
          |func main<A Kind, B Kind>()
          |where A = isInterface(B) {}
          |""".stripMargin, interner)
    val main = program.lookupFunction("main")

    vassertSome(main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("A"))))) shouldEqual KindTemplataType()
    vassertSome(main.runeToPredictedType.get(CodeRuneS(interner.intern(StrI("B"))))) shouldEqual KindTemplataType()
  }
}
