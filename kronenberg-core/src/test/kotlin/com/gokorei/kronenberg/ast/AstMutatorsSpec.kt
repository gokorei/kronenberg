package com.gokorei.kronenberg.ast

import com.gokorei.kronenberg.model.AstEdit
import com.gokorei.kronenberg.model.MutatorCategory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AstMutatorsSpec {
    private fun parse(code: String): KtFile = K2SnippetFrontend.parsePsi(code)

    private fun context(code: String): MutationContext = MutationContext(code, parse(code))

    private fun findMutations(
        code: String,
        mutator: AstMutator,
    ) = buildList {
        val ctx = context(code)
        ctx.file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitElement(element: PsiElement) {
                    super.visitElement(element)
                    if (mutator.canMutate(element)) {
                        addAll(mutator.mutate(element, ctx))
                    }
                }
            },
        )
    }

    @Nested
    inner class RelationalBoundaryMutatorTests {
        private val mutator = RelationalBoundaryMutator()

        @Test
        fun `mutates less than to less than or equal`() {
            mutator.category shouldBe MutatorCategory.RELATIONAL_BOUNDARY
            val edits = findMutations("fun check(x: Int): Boolean = x < 10", mutator)
            edits.firstOrNull()?.let {
                it.replacement shouldBe "<="
                it.originalText shouldContain "<"
            }
        }

        @Test
        fun `mutates less than or equal to less than`() {
            val edits = findMutations("fun check(x: Int): Boolean = x <= 10", mutator)
            edits.firstOrNull()?.replacement shouldBe "<"
        }

        @Test
        fun `mutates greater than to greater than or equal`() {
            val edits = findMutations("fun check(x: Int): Boolean = x > 10", mutator)
            edits.firstOrNull()?.replacement shouldBe ">="
        }

        @Test
        fun `mutates greater than or equal to greater than`() {
            val edits = findMutations("fun check(x: Int): Boolean = x >= 10", mutator)
            edits.firstOrNull()?.replacement shouldBe ">"
        }

        @Test
        fun `does not mutate equality or inequality`() {
            val eqEdits = findMutations("fun check(x: Int): Boolean = x == 10", mutator)
            eqEdits shouldBe emptyList()
            val neEdits = findMutations("fun check(x: Int): Boolean = x != 10", mutator)
            neEdits shouldBe emptyList()
        }
    }

    @Nested
    inner class EqualityMutatorTests {
        private val mutator = EqualityMutator()

        @Test
        fun `mutates equality to inequality`() {
            mutator.category shouldBe MutatorCategory.EQUALITY
            val edits = findMutations("fun check(x: Int): Boolean = x == 10", mutator)
            edits.firstOrNull()?.replacement shouldBe "!="
        }

        @Test
        fun `mutates inequality to equality`() {
            mutator.category shouldBe MutatorCategory.EQUALITY
            val edits = findMutations("fun check(x: Int): Boolean = x != 10", mutator)
            edits.firstOrNull()?.replacement shouldBe "=="
        }

        @Test
        fun `mutates referential equality and inequality`() {
            mutator.category shouldBe MutatorCategory.EQUALITY
            val editsEq = findMutations("fun check(a: Any, b: Any): Boolean = a === b", mutator)
            editsEq.firstOrNull()?.replacement shouldBe "!=="

            val editsNe = findMutations("fun check(a: Any, b: Any): Boolean = a !== b", mutator)
            editsNe.firstOrNull()?.replacement shouldBe "==="
        }
    }

    @Nested
    inner class ArithmeticOperatorMutatorTests {
        private val mutator = ArithmeticOperatorMutator()

        @Test
        fun `mutates plus to minus`() {
            mutator.category shouldBe MutatorCategory.ARITHMETIC_OPERATOR
            val edits = findMutations("fun add(a: Int, b: Int): Int = a + b", mutator)
            edits.firstOrNull()?.replacement shouldBe "-"
        }

        @Test
        fun `mutates multiplication to division`() {
            val edits = findMutations("fun multiply(a: Int, b: Int): Int = a * b", mutator)
            edits.firstOrNull()?.replacement shouldBe "/"
        }
    }

    @Nested
    inner class CompoundAssignmentMutatorTests {
        private val mutator = CompoundAssignmentMutator()

        @Test
        fun `mutates plus-assign to minus-assign`() {
            mutator.category shouldBe MutatorCategory.COMPOUND_ASSIGNMENT
            val edits =
                findMutations("fun accumulate(vararg nums: Int): Int { var sum = 0; for (n in nums) sum += n; return sum }", mutator)
            edits.firstOrNull()?.replacement shouldBe "-="
        }

        @Test
        fun `mutates multiply-assign to divide-assign`() {
            val edits = findMutations("fun scale(factor: Int) { var x = 10; x *= factor }", mutator)
            edits.firstOrNull()?.replacement shouldBe "/="
        }
    }

    @Nested
    inner class UnaryOperatorMutatorTests {
        private val mutator = UnaryOperatorMutator()

        @Test
        fun `mutates prefix plus to prefix minus and vice versa`() {
            mutator.category shouldBe MutatorCategory.UNARY_OPERATOR
            val edits = findMutations("fun negate(x: Int): Int = -x", mutator)
            edits.firstOrNull()?.replacement shouldBe "+x"
        }

        @Test
        fun `mutates prefix increment to decrement`() {
            val edits = findMutations("fun inc(x: Int): Int { var a = x; return ++a }", mutator)
            edits.firstOrNull()?.replacement shouldBe "--a"
        }

        @Test
        fun `mutates postfix increment to decrement`() {
            val edits = findMutations("fun inc(x: Int): Int { var a = x; return a++ }", mutator)
            edits.firstOrNull()?.replacement shouldBe "a--"
        }
    }

    @Nested
    inner class BooleanInversionMutatorTests {
        private val mutator = BooleanInversionMutator()

        @Test
        fun `mutates boolean AND to OR`() {
            mutator.category shouldBe MutatorCategory.BOOLEAN_INVERSION
            val edits = findMutations("fun isEligible(a: Boolean, b: Boolean): Boolean = a && b", mutator)
            edits.firstOrNull()?.replacement shouldBe "||"
        }

        @Test
        fun `removes boolean prefix NOT`() {
            val edits = findMutations("fun invert(flag: Boolean): Boolean = !flag", mutator)
            edits.firstOrNull()?.replacement shouldBe "flag"
        }
    }

    @Nested
    inner class ReturnValueMutatorTests {
        private val mutator = ReturnValueMutator()

        @Test
        fun `mutates boolean return true to false`() {
            mutator.category shouldBe MutatorCategory.RETURN_VALUE
            val edits = findMutations("fun check(): Boolean { return true }", mutator)
            edits.firstOrNull()?.replacement shouldBe "false"
        }

        @Test
        fun `mutates string return expression to empty string and altered string`() {
            val edits = findMutations("fun greeting(): String { return \"hello\" }", mutator)
            edits.any { it.replacement == "\"\"" } shouldBe true
            edits.any { it.replacement == "\"mutated\"" } shouldBe true
        }

        @Test
        fun `mutates collection return to empty list`() {
            val edits = findMutations("fun getItems(): List<String> { return listOf(\"a\", \"b\") }", mutator)
            edits.any { it.replacement == "emptyList()" } shouldBe true
        }

        @Test
        fun `mutates nullable object return to null`() {
            val edits = findMutations("fun findUser(): String? { return \"user\" }", mutator)
            edits.any { it.replacement == "null" } shouldBe true
        }
    }

    @Nested
    inner class VoidMethodCallMutatorTests {
        private val mutator = VoidMethodCallMutator()

        @Test
        fun `replaces side-effect statement with Unit`() {
            val code =
                """
                fun runJob() {
                    performSideEffect()
                    println("Done")
                }
                """.trimIndent()
            mutator.category shouldBe MutatorCategory.VOID_METHOD_CALL
            val edits = findMutations(code, mutator)
            edits.firstOrNull()?.replacement shouldBe "Unit"
        }
    }

    @Nested
    inner class LiteralMutationMutatorTests {
        private val mutator = LiteralMutationMutator()

        @Test
        fun `mutates integer literals`() {
            val edits = findMutations("fun getVal(): Int = 42", mutator)
            edits.map { it.replacement } shouldBe listOf("43", "41")
        }

        @Test
        fun `mutates double literals`() {
            val edits = findMutations("fun getRate(): Double = 1.5", mutator)
            edits.any { it.replacement == "2.5" } shouldBe true
        }
    }

    @Nested
    inner class CollectionOperatorMutatorTests {
        private val mutator = CollectionOperatorMutator()

        @Test
        fun `inverts filter to filterNot`() {
            mutator.category shouldBe MutatorCategory.COLLECTION_OPERATOR
            val edits = findMutations("fun evens(list: List<Int>) = list.filter { it % 2 == 0 }", mutator)
            edits.firstOrNull()?.replacement shouldBe "filterNot"
        }

        @Test
        fun `inverts any to all`() {
            val edits = findMutations("fun hasPositive(list: List<Int>) = list.any { it > 0 }", mutator)
            edits.firstOrNull()?.replacement shouldBe "all"
        }

        @Test
        fun `inverts map to mapNotNull`() {
            val edits = findMutations("fun transform(list: List<Int>) = list.map { it * 2 }", mutator)
            edits.firstOrNull()?.replacement shouldBe "mapNotNull"
        }

        @Test
        fun `inverts sorted to sortedDescending`() {
            val edits = findMutations("fun order(list: List<Int>) = list.sorted()", mutator)
            edits.firstOrNull()?.replacement shouldBe "sortedDescending"
        }

        @Test
        fun `inverts minOrNull to maxOrNull`() {
            val edits = findMutations("fun lowest(list: List<Int>) = list.minOrNull()", mutator)
            edits.firstOrNull()?.replacement shouldBe "maxOrNull"
        }
    }

    @Nested
    inner class NullSafetyMutatorTests {
        private val elvisRightMutator = NullSafetyMutator()
        private val elvisLeftMutator = ElvisLeftHandMutator()
        private val nonNullAssertionMutator = NonNullAssertionMutator()

        @Test
        fun `mutates elvis expression to right hand default`() {
            elvisRightMutator.category shouldBe MutatorCategory.NULL_SAFETY
            val edits = findMutations("fun getOrDefault(s: String?): String = s ?: \"default\"", elvisRightMutator)
            edits.firstOrNull()?.replacement shouldBe "\"default\""
        }

        @Test
        fun `mutates elvis expression to left hand fallback check`() {
            val edits = findMutations("fun getOrDefault(s: String?): String = s ?: \"default\"", elvisLeftMutator)
            edits.firstOrNull()?.replacement shouldBe "s"
        }

        @Test
        fun `mutates non-null assertion by removing exclamation marks`() {
            val edits = findMutations("fun extract(s: String?): Int = s!!.length", nonNullAssertionMutator)
            edits.firstOrNull()?.replacement shouldBe "s"
        }
    }

    @Nested
    inner class RangeOperatorMutatorTests {
        private val mutator = RangeOperatorMutator()

        @Test
        fun `mutates until to closed range`() {
            mutator.category shouldBe MutatorCategory.RANGE_OPERATOR
            val edits = findMutations("fun count(n: Int) = (0 until n).toList()", mutator)
            edits.firstOrNull()?.replacement shouldBe ".."
        }

        @Test
        fun `mutates rangeUntil operator to closed range`() {
            val edits = findMutations("fun count(n: Int) = (0..<n).toList()", mutator)
            edits.firstOrNull()?.replacement shouldBe ".."
        }

        @Test
        fun `mutates closed range operator to rangeUntil operator without syntax error`() {
            val code = "fun count(n: Int) = (0..n).toList()"
            val edits = findMutations(code, mutator)
            edits.firstOrNull()?.replacement shouldBe "..<"

            val generator = AstMutantGenerator()
            val mutants = generator.generateMutants(code)
            mutants.any { it.replacementText == "..<" && it.mutatedSource.contains("0..<n") } shouldBe true
        }
    }

    @Nested
    inner class BitwiseOperatorMutatorTests {
        private val mutator = BitwiseOperatorMutator()

        @Test
        fun `mutates infix and to or`() {
            mutator.category shouldBe MutatorCategory.BITWISE_OPERATOR
            val edits = findMutations("fun mask(a: Int, b: Int): Int = a and b", mutator)
            edits.firstOrNull()?.replacement shouldBe "or"
        }

        @Test
        fun `mutates infix xor to and`() {
            val edits = findMutations("fun mask(a: Int, b: Int): Int = a xor b", mutator)
            edits.firstOrNull()?.replacement shouldBe "and"
        }
    }

    @Nested
    inner class SafeCallMutatorTests {
        private val mutator = SafeCallMutator()

        @Test
        fun `mutates safe call to non-null assertion call`() {
            mutator.category shouldBe MutatorCategory.NULL_SAFETY
            val edits = findMutations("fun getLen(s: String?): Int? = s?.length", mutator)
            edits.firstOrNull()?.replacement shouldBe "s!!.length"
        }
    }

    @Nested
    inner class SmartCastMutatorTests {
        private val mutator = SmartCastMutator()

        @Test
        fun `mutates is to not-is and as to as-safe`() {
            mutator.category shouldBe MutatorCategory.EXTREME
            val isEdits = findMutations("fun checkType(x: Any): Boolean = x is String", mutator)
            isEdits.firstOrNull()?.replacement shouldBe "!is"

            val asEdits = findMutations("fun cast(x: Any): String = x as String", mutator)
            asEdits.firstOrNull()?.replacement shouldBe "as?"
        }
    }

    @Nested
    inner class StringTemplateMutatorTests {
        private val mutator = StringTemplateMutator()

        @Test
        fun `mutates interpolated expressions inside string template`() {
            mutator.category shouldBe MutatorCategory.LITERAL_MUTATION
            val edits = findMutations("fun greet(name: String) = \"Hello, \$name!\"", mutator)
            edits.firstOrNull()?.replacement shouldBe ""
        }
    }

    @Nested
    inner class CoroutineFlowMutatorTests {
        private val mutator = CoroutineFlowMutator()

        @Test
        fun `mutates delay call argument to zero`() {
            mutator.category shouldBe MutatorCategory.COROUTINE
            val edits = findMutations("suspend fun wait() { delay(1000L) }", mutator)
            edits.firstOrNull()?.replacement shouldBe "0L"
        }
    }

    @Nested
    inner class TypedAstMutatorTests {
        private val customMutator =
            object : TypedAstMutator<org.jetbrains.kotlin.psi.KtCallExpression>(org.jetbrains.kotlin.psi.KtCallExpression::class) {
                override val name: String = "TakeIfCustomMutator"
                override val category: MutatorCategory = MutatorCategory.COLLECTION_OPERATOR
                override val description: String = "Inverts takeIf <-> takeUnless"

                override fun canMutateTyped(element: org.jetbrains.kotlin.psi.KtCallExpression): Boolean =
                    element.calleeExpression?.text in setOf("takeIf", "takeUnless")

                override fun mutateTyped(
                    element: org.jetbrains.kotlin.psi.KtCallExpression,
                    context: MutationContext,
                ): List<AstEdit> {
                    val callee = element.calleeExpression ?: return emptyList()
                    val rep = if (callee.text == "takeIf") "takeUnless" else "takeIf"
                    return listOf(context.edit(callee, rep, "Inverted ${callee.text} -> $rep"))
                }
            }

        @Test
        fun `custom TypedAstMutator seamlessly inverts target calls with context edit`() {
            val edits = findMutations("fun check(x: Int) = x.takeIf { it > 0 }", customMutator)
            edits.size shouldBe 1
            edits.first().replacement shouldBe "takeUnless"
            edits.first().description shouldBe "Inverted takeIf -> takeUnless"
        }
    }
}
