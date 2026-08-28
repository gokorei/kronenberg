package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.MutatorCategory

/**
 * Centralized registry maintaining active AST mutation rules.
 */
public class MutatorRegistry(
    mutators: List<AstMutator> = defaultMutators(),
) {
    private val registeredMutators = mutableListOf<AstMutator>().apply { addAll(mutators) }

    public fun register(mutator: AstMutator): MutatorRegistry {
        registeredMutators.add(mutator)
        return this
    }

    public fun mutators(includeExtreme: Boolean = false): List<AstMutator> =
        if (includeExtreme) {
            registeredMutators.toList()
        } else {
            registeredMutators.filter {
                it.category != MutatorCategory.EXTREME &&
                    it.category != MutatorCategory.LITERAL_MUTATION &&
                    it.category != MutatorCategory.CONDITION_REPLACEMENT
            }
        }

    public companion object {
        public fun defaultMutators(): List<AstMutator> =
            listOf(
                RelationalBoundaryMutator(),
                ArithmeticOperatorMutator(),
                CompoundAssignmentMutator(),
                UnaryOperatorMutator(),
                BooleanInversionMutator(),
                ReturnValueMutator(),
                VoidMethodCallMutator(),
                LiteralMutationMutator(),
                CollectionOperatorMutator(),
                ConditionReplacementMutator(),
                NullSafetyMutator(),
                ElvisLeftHandMutator(),
                NonNullAssertionMutator(),
                RangeOperatorMutator(),
                BitwiseOperatorMutator(),
                SafeCallMutator(),
                SmartCastMutator(),
                StringTemplateMutator(),
                CoroutineFlowMutator(),
            )

        public fun default(): MutatorRegistry = MutatorRegistry(defaultMutators())
    }
}
