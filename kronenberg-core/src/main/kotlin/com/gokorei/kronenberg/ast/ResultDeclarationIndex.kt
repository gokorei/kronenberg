package com.gokorei.kronenberg.ast

import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * A declaration of the analysed file whose receiver type is readable from its PSI.
 */
internal data class ResultLocalDeclaration(
    val name: String,
    val typeText: String,
    val offset: Int,
    val scope: KtNamedFunction?,
)

/**
 * Builds the flat declaration index backing [PsiResultReceiverAnalyzer].
 *
 * Only explicitly written type references and non-recursive initializer hints are recorded, so
 * building the index can never recurse back into itself.
 */
internal object ResultDeclarationIndexBuilder {
    fun build(file: KtFile): List<ResultLocalDeclaration> {
        val declarations = mutableListOf<ResultLocalDeclaration>()
        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitProperty(property: KtProperty) {
                    val typeText =
                        property.typeReference?.text
                            ?: property.initializer?.let { ResultTypeHeuristics.initializerType(it) }
                    record(property, property.name, typeText, declarations)
                    super.visitProperty(property)
                }

                override fun visitParameter(parameter: KtParameter) {
                    record(parameter, parameter.name, parameter.typeReference?.text, declarations)
                    super.visitParameter(parameter)
                }
            },
        )
        return declarations
    }

    private fun record(
        element: KtElement,
        name: String?,
        typeText: String?,
        sink: MutableList<ResultLocalDeclaration>,
    ) {
        if (name == null || typeText == null) return
        sink.add(
            ResultLocalDeclaration(
                name = name,
                typeText = typeText,
                offset = element.textRange.startOffset,
                scope = element.getStrictParentOfType<KtNamedFunction>(),
            ),
        )
    }
}
