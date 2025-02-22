package dev.vale.typing

import dev.vale.postparsing.{CodeNameS, TopLevelStructDeclarationNameS}
import dev.vale.solver.{FailedSolve, RuleError}
import dev.vale.typing.OverloadResolver.{InferFailure, SpecificParamDoesntSend}
import dev.vale.typing.ast.{AsSubtypeTE, DestroyTE, DiscardTE, FunctionCallTE, FunctionDefinitionT, LocalLookupTE, PrototypeT, ReferenceMemberTypeT, SignatureT, SoftLoadTE, UnletTE, UpcastTE, referenceExprResultKind, referenceExprResultStructName}
import dev.vale.typing.env.ReferenceLocalVariableT
import dev.vale.typing.infer.OwnershipDidntMatch
import dev.vale.typing.names.{IdT, FunctionNameT, FunctionTemplateNameT, InterfaceNameT, InterfaceTemplateNameT, KindPlaceholderNameT, KindPlaceholderTemplateNameT, StructNameT, StructTemplateNameT}
import dev.vale.typing.templata.{CoordTemplataT, IntegerTemplataT, KindTemplataT, MutabilityTemplataT, functionName, simpleName}
import dev.vale.typing.types.{BoolT, BorrowT, CoordT, ImmutableT, IntT, InterfaceTT, OwnT, KindPlaceholderT, RuntimeSizedArrayTT, ShareT, StructTT, VoidT}
import dev.vale.{Collector, Err, Ok, PackageCoordinate, StrI, Tests, vassert, vassertOne, vfail, vimpl, vwat}
//import dev.vale.typingpass.infer.NotEnoughToSolveError
import org.scalatest.{FunSuite, Matchers}

import scala.io.Source

class InProgressTests extends FunSuite with Matchers {

}
