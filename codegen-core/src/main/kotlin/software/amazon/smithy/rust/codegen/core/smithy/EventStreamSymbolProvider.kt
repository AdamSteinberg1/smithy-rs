/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.rust.codegen.core.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustType
import software.amazon.smithy.rust.codegen.core.rustlang.render
import software.amazon.smithy.rust.codegen.core.rustlang.stripOuter
import software.amazon.smithy.rust.codegen.core.smithy.traits.SyntheticInputTrait
import software.amazon.smithy.rust.codegen.core.smithy.traits.SyntheticOutputTrait
import software.amazon.smithy.rust.codegen.core.smithy.transformers.eventStreamErrors
import software.amazon.smithy.rust.codegen.core.util.getTrait
import software.amazon.smithy.rust.codegen.core.util.isEventStream
import software.amazon.smithy.rust.codegen.core.util.isInputEventStream
import software.amazon.smithy.rust.codegen.core.util.isOutputEventStream

fun UnionShape.eventStreamErrorSymbol(symbolProvider: RustSymbolProvider): RuntimeType {
    val unionSymbol = symbolProvider.toSymbol(this)
    return RustModule.Error.toType().resolve("${unionSymbol.name}Error")
}

/**
 * Wrapping symbol provider to wrap modeled types with the aws-smithy-http Event Stream send/receive types.
 */
class EventStreamSymbolProvider(
    private val runtimeConfig: RuntimeConfig,
    base: RustSymbolProvider,
    private val model: Model,
    private val target: CodegenTarget,
) : WrappingSymbolProvider(base) {
    override fun toSymbol(shape: Shape): Symbol {
        val initial = super.toSymbol(shape)

        // We only want to wrap with Event Stream types when dealing with member shapes
        if (shape is MemberShape && shape.isEventStream(model)) {
            // Determine if the member has a container that is a synthetic input or output
            val operationShape = model.expectShape(shape.container).let { maybeInputOutput ->
                val operationId = maybeInputOutput.getTrait<SyntheticInputTrait>()?.operation
                    ?: maybeInputOutput.getTrait<SyntheticOutputTrait>()?.operation
                operationId?.let { model.expectShape(it, OperationShape::class.java) }
            }
            // If we find an operation shape, then we can wrap the type
            if (operationShape != null) {
                val unionShape = model.expectShape(shape.target).asUnionShape().get()
                val error = if (target == CodegenTarget.SERVER && unionShape.eventStreamErrors().isEmpty()) {
                    RuntimeType.smithyHttp(runtimeConfig).resolve("event_stream::MessageStreamError").toSymbol()
                } else {
                    symbolForEventStreamError(unionShape)
                }
                val errorFmt = error.rustType().render(fullyQualified = true)
                val innerFmt = initial.rustType().stripOuter<RustType.Option>().render(fullyQualified = true)
                val isSender = (shape.isInputEventStream(model) && target == CodegenTarget.CLIENT) ||
                    (shape.isOutputEventStream(model) && target == CodegenTarget.SERVER)
                val outer = when (isSender) {
                    true -> "EventStreamSender<$innerFmt, $errorFmt>"
                    else -> "Receiver<$innerFmt, $errorFmt>"
                }
                val rustType = RustType.Opaque(outer, "aws_smithy_http::event_stream")
                return initial.toBuilder()
                    .name(rustType.name)
                    .rustType(rustType)
                    .addReference(initial)
                    .addDependency(CargoDependency.smithyHttp(runtimeConfig).withFeature("event-stream"))
                    .addReference(error)
                    .build()
            }
        }

        return initial
    }
}
