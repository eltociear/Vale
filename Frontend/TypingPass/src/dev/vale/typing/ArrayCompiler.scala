package dev.vale.typing

import dev.vale.parsing.ast.MutableP
import dev.vale.postparsing._
import dev.vale.postparsing.rules.{IRulexSR, RuneParentEnvLookupSR, RuneUsage}
import dev.vale.typing.expression.CallCompiler
import dev.vale.typing.function.DestructorCompiler
import dev.vale.typing.types._
import dev.vale.{CodeLocationS, Err, Interner, Keywords, Ok, PackageCoordinate, Profiler, RangeS, Result, StrI, vassert, vassertOne, vassertSome, vimpl, vregionmut}
import dev.vale.typing.types._
import dev.vale.typing.templata.{ITemplataT, _}
import OverloadResolver.FindFunctionFailure
import dev.vale.highertyping.HigherTypingPass.explicifyLookups
import dev.vale.typing.ast.{DestroyImmRuntimeSizedArrayTE, DestroyStaticSizedArrayIntoFunctionTE, FunctionCallTE, NewImmRuntimeSizedArrayTE, ReferenceExpressionTE, RuntimeSizedArrayLookupTE, StaticArrayFromCallableTE, StaticArrayFromValuesTE, StaticSizedArrayLookupTE}
import dev.vale.typing.env._
import dev.vale.typing.names._
import dev.vale.typing.templata._
import dev.vale.typing.ast._
import dev.vale.typing.citizen.StructCompilerCore
import dev.vale.typing.function.FunctionCompiler.EvaluateFunctionSuccess
import dev.vale.typing.types._
import dev.vale.typing.templata._

import scala.collection.immutable.{List, Set}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class ArrayCompiler(
    opts: TypingPassOptions,
    interner: Interner,
    keywords: Keywords,
    inferCompiler: InferCompiler,
    overloadResolver: OverloadResolver,
    destructorCompiler: DestructorCompiler,
    templataCompiler: TemplataCompiler) {

  val runeTypeSolver = new RuneTypeSolver(interner)

  vassert(overloadResolver != null)

  def evaluateStaticSizedArrayFromCallable(
    coutputs: CompilerOutputs,
    callingEnv: IInDenizenEnvironmentT,
    parentRanges: List[RangeS],
    callLocation: LocationInDenizen,
    rulesWithImplicitlyCoercingLookupsS: Vector[IRulexSR],
    maybeElementTypeRuneA: Option[IRuneS],
    sizeRuneA: IRuneS,
    mutabilityRune: IRuneS,
    variabilityRune: IRuneS,
    callableTE: ReferenceExpressionTE,
    verifyConclusions: Boolean):
  StaticArrayFromCallableTE = {
    val runeTypingEnv =
      new IRuneTypeSolverEnv {
        override def lookup(range: RangeS, nameS: IImpreciseNameS):
        Result[IRuneTypeSolverLookupResult, IRuneTypingLookupFailedError] = {
          vimpl()
          //          vassertOne(callingEnv.lookupNearestWithImpreciseName(nameS, Set(TemplataLookupContext))).tyype
        }
      }
    //    val builtinNamespaceCoord =
//      interner.intern(PackageCoordinate(keywords.emptyString, Vector.empty))
//    val declaringEnv =
//      PackageEnvironment.makeTopLevelEnvironment(callingEnv.globalEnv, builtinNamespaceCoord)
    val runeAToTypeWithImplicitlyCoercingLookupsS =
      runeTypeSolver.solve(
        opts.globalOptions.sanityCheck,
        opts.globalOptions.useOptimizedSolver,
        runeTypingEnv,
        parentRanges,
        false,
        rulesWithImplicitlyCoercingLookupsS,
        List(),
        true,
        Map()) match {
        case Ok(r) => r
        case Err(e) => throw CompileErrorExceptionT(HigherTypingInferError(parentRanges, e))
      }

    val runeAToType =
      mutable.HashMap[IRuneS, ITemplataType]((runeAToTypeWithImplicitlyCoercingLookupsS.toSeq): _*)
    // We've now calculated all the types of all the runes, but the LookupSR rules are still a bit
    // loose. We intentionally ignored the types of the things they're looking up, so we could know
    // what types we *expect* them to be, so we could coerce.
    // That coercion is good, but lets make it more explicit.
    val ruleBuilder = ArrayBuffer[IRulexSR]()
    explicifyLookups(
      runeTypingEnv,
      runeAToType, ruleBuilder, rulesWithImplicitlyCoercingLookupsS) match {
      case Err(RuneTypingTooManyMatchingTypes(range, name)) => throw CompileErrorExceptionT(TooManyTypesWithNameT(range :: parentRanges, name))
      case Err(RuneTypingCouldntFindType(range, name)) => throw CompileErrorExceptionT(CouldntFindTypeT(range :: parentRanges, name))
      case Ok(()) =>
    }
    val rulesA = ruleBuilder.toVector

    val CompleteCompilerSolve(_, templatas, _, Vector()) =
      inferCompiler.solveExpectComplete(
        InferEnv(callingEnv, parentRanges, callLocation, callingEnv),
        coutputs,
        rulesA,
        runeAToType.toMap,
        parentRanges,
        callLocation,
        Vector(),
        Vector(),
        true,
        true,
        Vector())

    val size = ITemplataT.expectInteger(vassertSome(templatas.get(sizeRuneA)))
    val mutability = ITemplataT.expectMutability(vassertSome(templatas.get(mutabilityRune)))
    val variability = ITemplataT.expectVariability(vassertSome(templatas.get(variabilityRune)))
    val prototype =
      overloadResolver.getArrayGeneratorPrototype(
        coutputs, callingEnv, parentRanges, callLocation, callableTE, true)
    val ssaMT = resolveStaticSizedArray(mutability, variability, size, prototype.returnType)

    maybeElementTypeRuneA.foreach(elementTypeRuneA => {
      val expectedElementType = getArrayElementType(templatas, elementTypeRuneA)
      if (prototype.returnType != expectedElementType) {
        throw CompileErrorExceptionT(UnexpectedArrayElementType(parentRanges, expectedElementType, prototype.returnType))
      }
    })

    val expr2 = ast.StaticArrayFromCallableTE(ssaMT, callableTE, prototype)
    expr2
  }

  def evaluateRuntimeSizedArrayFromCallable(
    coutputs: CompilerOutputs,
    callingEnv: NodeEnvironmentT,
      parentRanges: List[RangeS],
      callLocation: LocationInDenizen,
    rulesWithImplicitlyCoercingLookupsS: Vector[IRulexSR],
    maybeElementTypeRune: Option[IRuneS],
    mutabilityRune: IRuneS,
    sizeTE: ReferenceExpressionTE,
    maybeCallableTE: Option[ReferenceExpressionTE],
    verifyConclusions: Boolean):
  ReferenceExpressionTE = {

    val runeTypingEnv = TemplataCompiler.createRuneTypeSolverEnv(callingEnv)

    val runeAToTypeWithImplicitlyCoercingLookupsS =
      runeTypeSolver.solve(
        opts.globalOptions.sanityCheck,
        opts.globalOptions.useOptimizedSolver,
        runeTypingEnv,
        parentRanges,
        false,
        rulesWithImplicitlyCoercingLookupsS,
        List(),
        true,
        Map(mutabilityRune -> MutabilityTemplataType()) ++
            maybeElementTypeRune.map(_ -> CoordTemplataType())) match {
        case Ok(r) => r
        case Err(e) => throw CompileErrorExceptionT(HigherTypingInferError(parentRanges, e))
      }

    val runeAToType =
      mutable.HashMap[IRuneS, ITemplataType]((runeAToTypeWithImplicitlyCoercingLookupsS.toSeq): _*)
    // We've now calculated all the types of all the runes, but the LookupSR rules are still a bit
    // loose. We intentionally ignored the types of the things they're looking up, so we could know
    // what types we *expect* them to be, so we could coerce.
    // That coercion is good, but lets make it more explicit.
    val ruleBuilder = ArrayBuffer[IRulexSR]()
    explicifyLookups(
      runeTypingEnv,
      runeAToType, ruleBuilder, rulesWithImplicitlyCoercingLookupsS) match {
      case Err(RuneTypingTooManyMatchingTypes(range, name)) => throw CompileErrorExceptionT(TooManyTypesWithNameT(range :: parentRanges, name))
      case Err(RuneTypingCouldntFindType(range, name)) => throw CompileErrorExceptionT(CouldntFindTypeT(range :: parentRanges, name))
      case Ok(()) =>
    }
    val rulesA = ruleBuilder.toVector

    val CompleteCompilerSolve(_, templatas, _, Vector()) =
      inferCompiler.solveExpectComplete(
        InferEnv(callingEnv, parentRanges, callLocation, callingEnv), coutputs, rulesA, runeAToType.toMap, parentRanges, callLocation, Vector(), Vector(), true, true, Vector())
    val mutability = ITemplataT.expectMutability(vassertSome(templatas.get(mutabilityRune)))

//    val variability = getArrayVariability(templatas, variabilityRune)

    if (maybeElementTypeRune.isEmpty) {
      // Temporary until we can figure out MSAE.
      throw CompileErrorExceptionT(RangedInternalErrorT(parentRanges, "Must specify element for arrays."))
    }

    mutability match {
      case PlaceholderTemplataT(_, MutabilityTemplataType()) => vimpl()
      case MutabilityTemplataT(ImmutableT) => {
        val callableTE =
          maybeCallableTE match {
            case None => {
              throw CompileErrorExceptionT(NewImmRSANeedsCallable(parentRanges))
            }
            case Some(c) => c
          }

        val prototype =
          overloadResolver.getArrayGeneratorPrototype(
            coutputs, callingEnv, parentRanges, callLocation, callableTE, true)
        val rsaMT = resolveRuntimeSizedArray(prototype.returnType, mutability)

        maybeElementTypeRune.foreach(elementTypeRuneA => {
          val expectedElementType = getArrayElementType(templatas, elementTypeRuneA)
          if (prototype.returnType != expectedElementType) {
            throw CompileErrorExceptionT(UnexpectedArrayElementType(parentRanges, expectedElementType, prototype.returnType))
          }
        })

        NewImmRuntimeSizedArrayTE(rsaMT, sizeTE, callableTE, prototype)
      }
      case MutabilityTemplataT(MutableT) => {
        val EvaluateFunctionSuccess(prototype, conclusions) =
          overloadResolver.findFunction(
            callingEnv
              .addEntries(
                interner,
                Vector(
                  (interner.intern(RuneNameT(CodeRuneS(keywords.M))), TemplataEnvEntry(MutabilityTemplataT(MutableT)))) ++
              maybeElementTypeRune.map(e => {
                (interner.intern(RuneNameT(e)), TemplataEnvEntry(CoordTemplataT(getArrayElementType(templatas, e))))
              })),
            coutputs,
            parentRanges,
            callLocation,
            interner.intern(CodeNameS(keywords.Array)),
            Vector(
              RuneParentEnvLookupSR(parentRanges.head, RuneUsage(parentRanges.head, CodeRuneS(keywords.M)))) ++
            maybeElementTypeRune.map(e => {
              RuneParentEnvLookupSR(parentRanges.head, RuneUsage(parentRanges.head, e))
            }),
            Vector(CodeRuneS(keywords.M)) ++ maybeElementTypeRune,
            Vector(sizeTE.result.coord) ++
              maybeCallableTE.map(c => c.result.coord),
            Vector(),
            true,
            true) match {
            case Err(e) => throw CompileErrorExceptionT(CouldntFindFunctionToCallT(parentRanges, e))
            case Ok(x) => x
          }

        val elementType =
          prototype.prototype.returnType.kind match {
            case RuntimeSizedArrayTT(IdT(_, _, RuntimeSizedArrayNameT(_, RawArrayNameT(mutability, elementType)))) => {
              if (mutability != MutabilityTemplataT(MutableT)) {
                throw CompileErrorExceptionT(RangedInternalErrorT(parentRanges, "Array function returned wrong mutability!"))
              }
              elementType
            }
            case _ => {
              throw CompileErrorExceptionT(RangedInternalErrorT(parentRanges, "Array function returned wrong type!"))
            }
          }
        maybeElementTypeRune.foreach(elementTypeRuneA => {
          val expectedElementType = getArrayElementType(templatas, elementTypeRuneA)
          if (elementType != expectedElementType) {
            throw CompileErrorExceptionT(
              UnexpectedArrayElementType(parentRanges, expectedElementType, prototype.prototype.returnType))
          }
        })
        vassert(coutputs.getInstantiationBounds(prototype.prototype.id).nonEmpty)
        val callTE =
          FunctionCallTE(prototype.prototype, Vector(sizeTE) ++ maybeCallableTE)
        callTE
        //        throw CompileErrorExceptionT(RangedInternalErrorT(range, "Can't construct a mutable runtime array from a callable!"))
      }
    }
  }

  def evaluateStaticSizedArrayFromValues(
      coutputs: CompilerOutputs,
      callingEnv: IInDenizenEnvironmentT,
      parentRanges: List[RangeS],
    callLocation: LocationInDenizen,
      rulesWithImplicitlyCoercingLookupsS: Vector[IRulexSR],
      maybeElementTypeRuneA: Option[IRuneS],
      sizeRuneA: IRuneS,
      mutabilityRuneA: IRuneS,
      variabilityRuneA: IRuneS,
      exprs2: Vector[ReferenceExpressionTE],
      verifyConclusions: Boolean):
   StaticArrayFromValuesTE = {

    val runeTypingEnv =
      new IRuneTypeSolverEnv {
        override def lookup(
          range: RangeS,
          name: IImpreciseNameS):
        Result[IRuneTypeSolverLookupResult, IRuneTypingLookupFailedError] = {
           Ok(
             TemplataLookupResult(
               vassertSome(callingEnv.lookupNearestWithImpreciseName(name, Set(TemplataLookupContext))).tyype))
        }
      }

    val runeAToTypeWithImplicitlyCoercingLookupsS =
      runeTypeSolver.solve(
        opts.globalOptions.sanityCheck,
        opts.globalOptions.useOptimizedSolver,
        runeTypingEnv,
        parentRanges,
        false,
        rulesWithImplicitlyCoercingLookupsS,
        List(),
        true,
        Map[IRuneS, ITemplataType](
          sizeRuneA -> IntegerTemplataType(),
          mutabilityRuneA -> MutabilityTemplataType(),
          variabilityRuneA -> VariabilityTemplataType()) ++
            (maybeElementTypeRuneA match {
              case Some(rune) => Map(rune -> CoordTemplataType())
              case None => Map()
            })) match {
        case Ok(r) => r
        case Err(e) => throw CompileErrorExceptionT(HigherTypingInferError(parentRanges, e))
      }
    val memberTypes = exprs2.map(_.result.coord).toSet
    if (memberTypes.size > 1) {
      throw CompileErrorExceptionT(ArrayElementsHaveDifferentTypes(parentRanges, memberTypes))
    }
    val memberType = memberTypes.head

    val runeAToType =
      mutable.HashMap[IRuneS, ITemplataType]((runeAToTypeWithImplicitlyCoercingLookupsS.toSeq): _*)
    // We've now calculated all the types of all the runes, but the LookupSR rules are still a bit
    // loose. We intentionally ignored the types of the things they're looking up, so we could know
    // what types we *expect* them to be, so we could coerce.
    // That coercion is good, but lets make it more explicit.
    val ruleBuilder = ArrayBuffer[IRulexSR]()
    explicifyLookups(
      runeTypingEnv,
      runeAToType, ruleBuilder, rulesWithImplicitlyCoercingLookupsS) match {
      case Err(RuneTypingTooManyMatchingTypes(range, name)) => throw CompileErrorExceptionT(TooManyTypesWithNameT(range :: parentRanges, name))
      case Err(RuneTypingCouldntFindType(range, name)) => throw CompileErrorExceptionT(CouldntFindTypeT(range :: parentRanges, name))
      case Ok(()) =>
    }
    val rulesA = ruleBuilder.toVector

    val CompleteCompilerSolve(_, templatas, _, Vector()) =
      inferCompiler.solveExpectComplete(
        InferEnv(callingEnv, parentRanges, callLocation, callingEnv),
        coutputs, rulesA, runeAToType.toMap, parentRanges, callLocation, Vector(), Vector(), true, true, Vector())
    maybeElementTypeRuneA.foreach(elementTypeRuneA => {
      val expectedElementType = getArrayElementType(templatas, elementTypeRuneA)
      if (memberType != expectedElementType) {
        throw CompileErrorExceptionT(UnexpectedArrayElementType(parentRanges, expectedElementType, memberType))
      }
    })

//    val size = getArraySize(templatas, sizeRuneA)
    val mutability = ITemplataT.expectMutability(vassertSome(templatas.get(mutabilityRuneA)))
    val variability = ITemplataT.expectVariability(vassertSome(templatas.get(variabilityRuneA)))

//    if (size != exprs2.size) {
//      throw CompileErrorExceptionT(InitializedWrongNumberOfElements(range, size, exprs2.size))
//    }

    val staticSizedArrayType = resolveStaticSizedArray(mutability, variability, IntegerTemplataT(exprs2.size), memberType)
    val ownership =
      staticSizedArrayType.mutability match {
        case MutabilityTemplataT(MutableT) => OwnT
        case MutabilityTemplataT(ImmutableT) => ShareT
        case PlaceholderTemplataT(_, MutabilityTemplataType()) => OwnT
      }

    val ssaCoord = CoordT(ownership, GlobalRegionT(), staticSizedArrayType)

    val finalExpr =
      StaticArrayFromValuesTE(
        exprs2, ssaCoord, staticSizedArrayType)
    (finalExpr)
  }

  def evaluateDestroyStaticSizedArrayIntoCallable(
    coutputs: CompilerOutputs,
    fate: FunctionEnvironmentBoxT,
    range: List[RangeS],
    callLocation: LocationInDenizen,
    arrTE: ReferenceExpressionTE,
    callableTE: ReferenceExpressionTE):
  DestroyStaticSizedArrayIntoFunctionTE = {
    val arrayTT =
      arrTE.result.coord match {
        case CoordT(_, _, s @ contentsStaticSizedArrayTT(_, _, _, _)) => s
        case other => {
          throw CompileErrorExceptionT(RangedInternalErrorT(range, "Destroying a non-array with a callable! Destroying: " + other))
        }
      }

    val prototype =
      overloadResolver.getArrayConsumerPrototype(
        coutputs, fate, range, callLocation, callableTE, arrayTT.elementType, true)

    ast.DestroyStaticSizedArrayIntoFunctionTE(
      arrTE,
      arrayTT,
      callableTE,
      prototype)
  }

  def evaluateDestroyRuntimeSizedArrayIntoCallable(
    coutputs: CompilerOutputs,
    fate: FunctionEnvironmentBoxT,
    range: List[RangeS],
    callLocation: LocationInDenizen,
    arrTE: ReferenceExpressionTE,
    callableTE: ReferenceExpressionTE):
  DestroyImmRuntimeSizedArrayTE = {
    val arrayTT =
      arrTE.result.coord match {
        case CoordT(_, _, s @ contentsRuntimeSizedArrayTT(_, _)) => s
        case other => {
          throw CompileErrorExceptionT(RangedInternalErrorT(range, "Destroying a non-array with a callable! Destroying: " + other))
        }
      }

    arrayTT.mutability match {
      case PlaceholderTemplataT(_, MutabilityTemplataType()) => {
        throw CompileErrorExceptionT(RangedInternalErrorT(range, "Can't destroy an array whose mutability we don't know!"))
      }
      case MutabilityTemplataT(ImmutableT) =>
      case MutabilityTemplataT(MutableT) => {
        throw CompileErrorExceptionT(RangedInternalErrorT(range, "Can't destroy a mutable array with a callable!"))
      }
    }

    val prototype =
      overloadResolver.getArrayConsumerPrototype(
        coutputs, fate, range, callLocation, callableTE, arrayTT.elementType, true)

//    val freePrototype =
//      destructorCompiler.getFreeFunction(
//        coutputs, fate, range, arrTE.result.reference)
//        .function.prototype
//    vassert(coutputs.getInstantiationBounds(freePrototype.fullName).nonEmpty)

    ast.DestroyImmRuntimeSizedArrayTE(
      arrTE,
      arrayTT,
      callableTE,
      prototype)
  }

  def compileStaticSizedArray(globalEnv: GlobalEnvironment, coutputs: CompilerOutputs): Unit = {
    val builtinPackage = PackageCoordinate.BUILTIN(interner, keywords)
    val templateId =
      IdT(builtinPackage, Vector.empty, interner.intern(StaticSizedArrayTemplateNameT()))

    // We declare the function into the environment that we use to compile the
    // struct, so that those who use the struct can reach into its environment
    // and see the function and use it.
    // See CSFMSEO and SAFHE.
    val arrayOuterEnv =
      CitizenEnvironmentT(
        globalEnv,
        PackageEnvironmentT(globalEnv, templateId, globalEnv.nameToTopLevelEnvironment.values.toVector),
        templateId,
        templateId,
        TemplatasStore(templateId, Map(), Map()))
    coutputs.declareType(templateId)
    coutputs.declareTypeOuterEnv(templateId, arrayOuterEnv)

    val TemplateTemplataType(types, _) = StaticSizedArrayTemplateTemplataT().tyype
    val Vector(IntegerTemplataType(), MutabilityTemplataType(), VariabilityTemplataType(), CoordTemplataType()) = types
    val sizePlaceholder =
      templataCompiler.createPlaceholderInner(
        coutputs, arrayOuterEnv, templateId, 0, CodeRuneS(interner.intern(StrI("N"))), IntegerTemplataType(), false, true)
    val mutabilityPlaceholder =
      templataCompiler.createPlaceholderInner(
        coutputs, arrayOuterEnv, templateId, 1, CodeRuneS(interner.intern(StrI("M"))), MutabilityTemplataType(), false, true)
    val variabilityPlaceholder =
      templataCompiler.createPlaceholderInner(
        coutputs, arrayOuterEnv, templateId, 2, CodeRuneS(interner.intern(StrI("V"))), VariabilityTemplataType(), false, true)
    val elementPlaceholder =
      templataCompiler.createPlaceholderInner(
        coutputs, arrayOuterEnv, templateId, 3, CodeRuneS(interner.intern(StrI("E"))), CoordTemplataType(), false, true)
    val placeholders =
      Vector(sizePlaceholder, mutabilityPlaceholder, variabilityPlaceholder, elementPlaceholder)

    val id = templateId.copy(localName = templateId.localName.makeCitizenName(interner, placeholders))
    vassert(TemplataCompiler.getTemplate(id) == templateId)

    val arrayInnerEnv =
      arrayOuterEnv.copy(
        id = id,
        templatas = arrayOuterEnv.templatas.copy(templatasStoreName = id))
    coutputs.declareTypeInnerEnv(templateId, arrayInnerEnv)
  }

  def resolveStaticSizedArray(
    mutability: ITemplataT[MutabilityTemplataType],
    variability: ITemplataT[VariabilityTemplataType],
    size: ITemplataT[IntegerTemplataType],
    type2: CoordT):
  (StaticSizedArrayTT) = {
    interner.intern(StaticSizedArrayTT(
      IdT(
        PackageCoordinate.BUILTIN(interner, keywords),
        Vector(),
        interner.intern(StaticSizedArrayNameT(
          interner.intern(StaticSizedArrayTemplateNameT()),
          size,
          variability,
          interner.intern(RawArrayNameT(
            mutability, type2)))))))
  }

  def compileRuntimeSizedArray(globalEnv: GlobalEnvironment, coutputs: CompilerOutputs): Unit = {
    val builtinPackage = PackageCoordinate.BUILTIN(interner, keywords)
    val templateId =
      IdT(builtinPackage, Vector.empty, interner.intern(RuntimeSizedArrayTemplateNameT()))

    // We declare the function into the environment that we use to compile the
    // struct, so that those who use the struct can reach into its environment
    // and see the function and use it.
    // See CSFMSEO and SAFHE.
    val arrayOuterEnv =
      CitizenEnvironmentT(
        globalEnv,
        PackageEnvironmentT(globalEnv, templateId, globalEnv.nameToTopLevelEnvironment.values.toVector),
        templateId,
        templateId,
        TemplatasStore(templateId, Map(), Map()))
    coutputs.declareType(templateId)
    coutputs.declareTypeOuterEnv(templateId, arrayOuterEnv)



    val TemplateTemplataType(types, _) = RuntimeSizedArrayTemplateTemplataT().tyype
    val Vector(MutabilityTemplataType(), CoordTemplataType()) = types
    val mutabilityPlaceholder =
      templataCompiler.createPlaceholderInner(
        coutputs, arrayOuterEnv, templateId, 0, CodeRuneS(interner.intern(StrI("M"))), MutabilityTemplataType(), false, true)
    val elementPlaceholder =
      templataCompiler.createPlaceholderInner(
        coutputs, arrayOuterEnv, templateId, 1, CodeRuneS(interner.intern(StrI("E"))), CoordTemplataType(), false, true)
    val placeholders =
      Vector(mutabilityPlaceholder, elementPlaceholder)

    val id = templateId.copy(localName = templateId.localName.makeCitizenName(interner, placeholders))

    val arrayInnerEnv =
      arrayOuterEnv.copy(
        id = id,
        templatas = arrayOuterEnv.templatas.copy(templatasStoreName = id))
    coutputs.declareTypeInnerEnv(templateId, arrayInnerEnv)
  }

  def resolveRuntimeSizedArray(type2: CoordT, mutability: ITemplataT[MutabilityTemplataType]):
  (RuntimeSizedArrayTT) = {
    interner.intern(RuntimeSizedArrayTT(
      IdT(
        PackageCoordinate.BUILTIN(interner, keywords),
        Vector(),
        interner.intern(RuntimeSizedArrayNameT(
          interner.intern(RuntimeSizedArrayTemplateNameT()),
          interner.intern(RawArrayNameT(
            mutability, type2)))))))
  }

  private def getArraySize(templatas: Map[IRuneS, ITemplataT[ITemplataType]], sizeRuneA: IRuneS): Int = {
    val IntegerTemplataT(m) = vassertSome(templatas.get(sizeRuneA))
    m.toInt
  }
  private def getArrayElementType(templatas: Map[IRuneS, ITemplataT[ITemplataType]], typeRuneA: IRuneS): CoordT = {
    val CoordTemplataT(m) = vassertSome(templatas.get(typeRuneA))
    m
  }

  def lookupInStaticSizedArray(
      range: RangeS,
      containerExpr2: ReferenceExpressionTE,
      indexExpr2: ReferenceExpressionTE,
      at: StaticSizedArrayTT) = {
    val contentsStaticSizedArrayTT(size, mutability, variabilityTemplata, memberType) = at
    val variability =
      variabilityTemplata match {
        case PlaceholderTemplataT(_, _) => FinalT
        case VariabilityTemplataT(variability) => variability
      }
    StaticSizedArrayLookupTE(range, containerExpr2, at, indexExpr2, variability)
  }

  def lookupInUnknownSizedArray(
    parentRanges: List[RangeS],
    range: RangeS,
    containerExpr2: ReferenceExpressionTE,
    indexExpr2: ReferenceExpressionTE,
    rsa: RuntimeSizedArrayTT
  ): RuntimeSizedArrayLookupTE = {
    val contentsRuntimeSizedArrayTT(mutability, memberType) = rsa
    if (indexExpr2.result.coord != CoordT(ShareT, GlobalRegionT(), IntT(32))) {
      throw CompileErrorExceptionT(IndexedArrayWithNonInteger(range :: parentRanges, indexExpr2.result.coord))
    }
    val variability =
      mutability match {
        case PlaceholderTemplataT(_, MutabilityTemplataType()) => FinalT
        case MutabilityTemplataT(ImmutableT) => FinalT
        case MutabilityTemplataT(MutableT) => VaryingT
      }
    RuntimeSizedArrayLookupTE(range, containerExpr2, rsa, indexExpr2, variability)
  }

}
