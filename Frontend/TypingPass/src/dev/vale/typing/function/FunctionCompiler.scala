package dev.vale.typing.function

import dev.vale.{Interner, Keywords, Profiler, RangeS, postparsing, vassert, vassertOne, vfail, vimpl, vwat}
import dev.vale.postparsing._
import dev.vale.postparsing.patterns.AtomSP
import dev.vale.highertyping.CouldntSolveRulesA
import dev.vale.typing.types._
import dev.vale.typing.templata._
import dev.vale.parsing._
import dev.vale.postparsing.RuneTypeSolver
import dev.vale.postparsing.patterns._
import dev.vale.postparsing.rules._
import dev.vale.typing.OverloadResolver.IFindFunctionFailureReason
import dev.vale.typing._
import dev.vale.typing.ast._
import dev.vale.typing.env._
import FunctionCompiler.IEvaluateFunctionResult
import dev.vale.highertyping.FunctionA
import dev.vale.typing.{CompilerOutputs, ConvertHelper, IFunctionGenerator, InferCompiler, TemplataCompiler, TypingPassOptions}
import dev.vale.typing.ast.{FunctionBannerT, FunctionHeaderT, LocationInFunctionEnvironmentT, ParameterT, PrototypeT, ReferenceExpressionTE}
import dev.vale.typing.citizen.StructCompiler
import dev.vale.typing.env.{AddressibleClosureVariableT, AddressibleLocalVariableT, FunctionEnvironmentT, IInDenizenEnvironmentT, NodeEnvironmentT, NodeEnvironmentBox, ReferenceClosureVariableT, ReferenceLocalVariableT, TemplataLookupContext}
import dev.vale.typing.names.{LambdaCitizenNameT, LambdaCitizenTemplateNameT, NameTranslator}
import dev.vale.typing.templata._
import dev.vale.typing.types._
import dev.vale.typing.names.LambdaCitizenNameT

import scala.collection.immutable.{List, Set}



trait IFunctionCompilerDelegate {
  def evaluateBlockStatements(
    coutputs: CompilerOutputs,
    startingNenv: NodeEnvironmentT,
    nenv: NodeEnvironmentBox,
    life: LocationInFunctionEnvironmentT,
    ranges: List[RangeS],
    callLocation: LocationInDenizen,
    exprs: BlockSE):
  (ReferenceExpressionTE, Set[CoordT])

  def translatePatternList(
    coutputs: CompilerOutputs,
    nenv: NodeEnvironmentBox,
    life: LocationInFunctionEnvironmentT,
    ranges: List[RangeS],
    patterns1: Vector[AtomSP],
    patternInputExprs2: Vector[ReferenceExpressionTE]):
  ReferenceExpressionTE

//  def evaluateParent(
//    env: IEnvironment, coutputs: CompilerOutputs, callRange: List[RangeS], sparkHeader: FunctionHeaderT):
//  Unit

  def generateFunction(
    functionCompilerCore: FunctionCompilerCore,
    generator: IFunctionGenerator,
    env: FunctionEnvironmentT,
    coutputs: CompilerOutputs,
    life: LocationInFunctionEnvironmentT,
    callRange: List[RangeS],
    // We might be able to move these all into the function environment... maybe....
    originFunction: Option[FunctionA],
    paramCoords: Vector[ParameterT],
    maybeRetCoord: Option[CoordT]):
  FunctionHeaderT
}

object FunctionCompiler {
  trait IEvaluateFunctionResult

  case class EvaluateFunctionSuccess(
    prototype: PrototypeTemplataT,
    inferences: Map[IRuneS, ITemplataT[ITemplataType]]
  ) extends IEvaluateFunctionResult

  case class EvaluateFunctionFailure(
    reason: IFindFunctionFailureReason
  ) extends IEvaluateFunctionResult
}

// When typingpassing a function, these things need to happen:
// - Spawn a local environment for the function
// - Add any closure args to the environment
// - Incorporate any template arguments into the environment
// There's a layer to take care of each of these things.
// This file is the outer layer, which spawns a local environment for the function.
class FunctionCompiler(
    opts: TypingPassOptions,
    interner: Interner,
    keywords: Keywords,
    nameTranslator: NameTranslator,
    templataCompiler: TemplataCompiler,
    inferCompiler: InferCompiler,
    convertHelper: ConvertHelper,
    structCompiler: StructCompiler,
    delegate: IFunctionCompilerDelegate) {
  val closureOrLightLayer =
    new FunctionCompilerClosureOrLightLayer(
      opts, interner, keywords, nameTranslator, templataCompiler, inferCompiler, convertHelper, structCompiler, delegate)

  // We would want only the prototype instead of the entire header if, for example,
  // we were calling the function. This is necessary for a recursive function like
  // func main():Int{main()}
  def evaluateGenericFunctionFromNonCall(
    coutputs: CompilerOutputs,
    parentRanges: List[RangeS],
    callLocation: LocationInDenizen,
    functionTemplata: FunctionTemplataT,
    verifyConclusions: Boolean):
  (FunctionHeaderT) = {
    Profiler.frame(() => {
      val FunctionTemplataT(env, function) = functionTemplata
      if (function.isLight) {
        closureOrLightLayer.evaluateGenericLightFunctionFromNonCall(
          env, coutputs, function.range :: parentRanges, callLocation, function, verifyConclusions)
      } else {
        vfail() // I think we need a call to evaluate a lambda?
      }
    })

  }

  def evaluateTemplatedLightFunctionFromCallForPrototype(
    coutputs: CompilerOutputs,
    callingEnv: IInDenizenEnvironmentT, // See CSSNCE
    callRange: List[RangeS],
    callLocation: LocationInDenizen,
    functionTemplata: FunctionTemplataT,
    alreadySpecifiedTemplateArgs: Vector[ITemplataT[ITemplataType]],
    argTypes: Vector[CoordT]):
  (IEvaluateFunctionResult) = {
    Profiler.frame(() => {
      val FunctionTemplataT(declaringEnv, function) = functionTemplata
      closureOrLightLayer.evaluateTemplatedLightBannerFromCall(
        declaringEnv,
        coutputs,
        callingEnv, // See CSSNCE
        callRange, callLocation, function, alreadySpecifiedTemplateArgs, argTypes)
    })
  }

  def evaluateTemplatedFunctionFromCallForPrototype(
    coutputs: CompilerOutputs,
    callingEnv: IInDenizenEnvironmentT, // See CSSNCE
    callRange: List[RangeS],
    callLocation: LocationInDenizen,
    functionTemplata: FunctionTemplataT,
    alreadySpecifiedTemplateArgs: Vector[ITemplataT[ITemplataType]],
    argTypes: Vector[CoordT]):
  (IEvaluateFunctionResult) = {
    Profiler.frame(() => {
      val FunctionTemplataT(declaringEnv, function) = functionTemplata
      if (function.isLight()) {
        closureOrLightLayer.evaluateTemplatedLightBannerFromCall(
          declaringEnv,
          coutputs,
          callingEnv, // See CSSNCE
          callRange, callLocation, function, alreadySpecifiedTemplateArgs, argTypes)
      } else {
        val lambdaCitizenName2 =
          functionTemplata.function.name match {
            case LambdaDeclarationNameS(codeLocation) => interner.intern(LambdaCitizenNameT(interner.intern(LambdaCitizenTemplateNameT(nameTranslator.translateCodeLocation(codeLocation)))))
            case _ => vwat()
          }

        val KindTemplataT(closureStructRef@StructTT(_)) =
          vassertOne(
            declaringEnv.lookupNearestWithName(
              lambdaCitizenName2,
              Set(TemplataLookupContext)))
        val banner =
          closureOrLightLayer.evaluateTemplatedClosureFunctionFromCallForBanner(
            declaringEnv, coutputs, callingEnv, callRange, callLocation, closureStructRef, function,
            alreadySpecifiedTemplateArgs, argTypes)
        (banner)
      }
    })

  }

  def evaluateTemplatedFunctionFromCallForPrototype(
    coutputs: CompilerOutputs,
    callRange: List[RangeS],
    callLocation: LocationInDenizen,
    callingEnv: IInDenizenEnvironmentT, // See CSSNCE
    functionTemplata: FunctionTemplataT,
    explicitTemplateArgs: Vector[ITemplataT[ITemplataType]],
    argTypes: Vector[CoordT],
    verifyConclusions: Boolean):
  IEvaluateFunctionResult = {
    Profiler.frame(() => {
      val FunctionTemplataT(env, function) = functionTemplata
      if (function.isLight()) {
        closureOrLightLayer.evaluateTemplatedLightFunctionFromCallForPrototype2(
          env, coutputs, callingEnv, callRange, callLocation, function, explicitTemplateArgs, argTypes, verifyConclusions)
      } else {
        val lambdaCitizenName2 =
          function.name match {
            case LambdaDeclarationNameS(codeLocation) => interner.intern(LambdaCitizenNameT(interner.intern(LambdaCitizenTemplateNameT(nameTranslator.translateCodeLocation(codeLocation)))))
            case _ => vwat()
          }
        val KindTemplataT(closureStructRef @ StructTT(_)) =
          vassertOne(
            env.lookupNearestWithName(
              lambdaCitizenName2,
              Set(TemplataLookupContext)))
        closureOrLightLayer.evaluateTemplatedClosureFunctionFromCallForPrototype(
          env, coutputs, callingEnv, callRange, callLocation, closureStructRef, function, explicitTemplateArgs, argTypes, verifyConclusions)
      }
    })

  }

  def evaluateGenericLightFunctionParentForPrototype(
    coutputs: CompilerOutputs,
    callRange: List[RangeS],
    callLocation: LocationInDenizen,
    callingEnv: IInDenizenEnvironmentT, // See CSSNCE
    functionTemplata: FunctionTemplataT,
    args: Vector[Option[CoordT]]):
  IEvaluateFunctionResult = {
    Profiler.frame(() => {
      val FunctionTemplataT(env, function) = functionTemplata
      closureOrLightLayer.evaluateGenericLightFunctionParentForPrototype2(
        env, coutputs, callingEnv, callRange, callLocation, function, args)
    })
  }

  def evaluateGenericLightFunctionFromCallForPrototype(
    coutputs: CompilerOutputs,
    callRange: List[RangeS],
    callLocation: LocationInDenizen,
    callingEnv: IInDenizenEnvironmentT, // See CSSNCE
    functionTemplata: FunctionTemplataT,
    explicitTemplateArgs: Vector[ITemplataT[ITemplataType]],
    args: Vector[CoordT]):
  IEvaluateFunctionResult = {
    Profiler.frame(() => {
      val FunctionTemplataT(env, function) = functionTemplata
      closureOrLightLayer.evaluateGenericLightFunctionFromCallForPrototype2(
        env, coutputs, callingEnv, callRange, callLocation, function, explicitTemplateArgs, args.map(Some(_)))
    })
  }

  def evaluateClosureStruct(
    coutputs: CompilerOutputs,
    containingNodeEnv: NodeEnvironmentT,
    callRange: List[RangeS],
    callLocation: LocationInDenizen,
    name: IFunctionDeclarationNameS,
    functionA: FunctionA,
    verifyConclusions: Boolean):
  (StructTT) = {
    val CodeBodyS(body) = functionA.body
    val closuredNames = body.closuredNames;

    // Note, this is where the unordered closuredNames set becomes ordered.
    val closuredVarNamesAndTypes =
      closuredNames
        .map(name => determineClosureVariableMember(containingNodeEnv, coutputs, name))
        .toVector;

    val (structTT, _, functionTemplata) =
      structCompiler.makeClosureUnderstruct(
        containingNodeEnv, coutputs, callRange, callLocation, name, functionA, closuredVarNamesAndTypes)

    (structTT)
  }

  private def determineClosureVariableMember(
    env: NodeEnvironmentT,
    coutputs: CompilerOutputs,
    name: IVarNameS) = {
    val (variability2, memberType) =
      env.getVariable(nameTranslator.translateVarNameStep(name)).get match {
        case ReferenceLocalVariableT(_, variability, reference) => {
          // See "Captured own is borrow" test for why we do this
          val tyype =
            reference.ownership match {
              case OwnT => ReferenceMemberTypeT(CoordT(BorrowT, GlobalRegionT(), reference.kind))
              case BorrowT | ShareT => ReferenceMemberTypeT(reference)
            }
          (variability, tyype)
        }
        case AddressibleLocalVariableT(_, variability, reference) => {
          (variability, AddressMemberTypeT(reference))
        }
        case ReferenceClosureVariableT(_, _, variability, reference) => {
          // See "Captured own is borrow" test for why we do this
          val tyype =
            reference.ownership match {
              case OwnT => ReferenceMemberTypeT(CoordT(BorrowT, GlobalRegionT(), reference.kind))
              case BorrowT | ShareT => ReferenceMemberTypeT(reference)
            }
          (variability, tyype)
        }
        case AddressibleClosureVariableT(_, _, variability, reference) => {
          (variability, AddressMemberTypeT(reference))
        }
      }
    NormalStructMemberT(nameTranslator.translateVarNameStep(name), variability2, memberType)
  }

}
