package com.gokorei.kronenberg.ast

import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * A declaration of the analysed file whose type is readable from its PSI.
 */
internal data class ResultLocalDeclaration(
    val name: String,
    val typeText: String,
    val offset: Int,
    val scope: KtNamedFunction?,
)

/**
 * A file-level or class-level function whose return type is stated by the analysed file.
 *
 * The return type is either an explicit type reference or the type a direct `kotlin.Result` factory
 * expression body produces. Only non-variadic declarations are recorded: call arity is what selects
 * among overloads, and a variadic parameter makes that selection unreliable, so such a declaration
 * proves nothing.
 */
internal data class ResultLocalFunction(
    val name: String,
    val returnTypeText: String,
    val parameterCount: Int,
)

/**
 * A class or object declared in the analysed file, reduced to the facts the receiver analysis needs.
 */
internal data class ResultLocalClass(
    val memberNames: Set<String>,
    val memberTypes: Map<String, String>,
    val superTypeNames: List<String>,
)

/**
 * Flat, non-recursive index over the analysed file backing [PsiResultReceiverAnalyzer].
 *
 * The index answers three questions, each of which only ever reads facts literally written in the
 * parsed file:
 *
 * 1. What type does a name or an initializer have ([declarations])?
 * 2. What does a function declared in this file return, and does the call arity select it
 *    ([functionReturnType])?
 * 3. Does a type declared in this file shadow a callee, directly or through a locally declared
 *    supertype ([declaresMember], [memberTypeOf])?
 *
 * Questions 1 and 2 are what make a `Result` receiver provable. Whatever the file does not state
 * stays unproven, which the analyzer reports as [ResultReceiverVerdict.UNKNOWN] and the mutator
 * rejects.
 */
internal class ResultDeclarationIndex private constructor(
    val declarations: List<ResultLocalDeclaration>,
    private val functions: List<ResultLocalFunction>,
    private val classes: Map<String, ResultLocalClass>,
) {
    /**
     * Returns the declared return type of the file-level or class-level function named [name] that
     * accepts [argumentCount] arguments, or `null` when no such declaration exists.
     */
    fun functionReturnType(
        name: String,
        argumentCount: Int,
    ): String? = functions.lastOrNull { it.name == name && it.parameterCount == argumentCount }?.returnTypeText

    /**
     * Returns the explicitly declared type of [memberName] inside the locally declared [className],
     * or `null` when either the member or the class is unknown.
     */
    fun memberTypeOf(
        className: String,
        memberName: String,
    ): String? = classes[className]?.memberTypes?.get(memberName)

    /**
     * Reports whether [className], or any supertype also declared in the analysed file, declares
     * [memberName]. The [visited] set breaks cycles, so even a cyclic type hierarchy written in the
     * analysed file terminates.
     */
    fun declaresMember(
        className: String,
        memberName: String,
        visited: MutableSet<String> = mutableSetOf(),
    ): Boolean {
        val declaration = classes[className]?.takeIf { visited.add(className) }
        return when {
            declaration == null -> false
            memberName in declaration.memberNames -> true
            else -> declaration.superTypeNames.any { declaresMember(it, memberName, visited) }
        }
    }

    companion object {
        fun build(file: KtFile): ResultDeclarationIndex =
            ResultDeclarationIndex(
                declarations = collectDeclarations(file),
                functions = collectFunctions(file),
                classes = collectClasses(file),
            )

        /**
         * Records properties, parameters and local variables with a readable type reference, either
         * written out explicitly or derivable from a non-recursive initializer hint.
         */
        private fun collectDeclarations(file: KtFile): List<ResultLocalDeclaration> {
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

        /**
         * Records top-level and class-level functions whose return type the file states, either as an
         * explicit type reference or as a direct `kotlin.Result` factory expression body such as
         * `= runCatching { }`. Local functions are skipped because the index does not track scope.
         */
        private fun collectFunctions(file: KtFile): List<ResultLocalFunction> =
            file
                .collectDescendantsOfType<KtNamedFunction>()
                .filter { it.parent is KtFile || it.parent is KtClassBody }
                .filter { function -> function.valueParameters.none { it.isVarArg } }
                .mapNotNull { function ->
                    val name = function.name ?: return@mapNotNull null
                    val returnTypeText = returnTypeTextOf(function) ?: return@mapNotNull null
                    ResultLocalFunction(
                        name = name,
                        returnTypeText = returnTypeText,
                        parameterCount = function.valueParameters.size,
                    )
                }

        /**
         * Returns the declared return type of [function], falling back to the type a direct factory
         * expression body produces. A body the file does not describe returns `null` and proves
         * nothing.
         */
        private fun returnTypeTextOf(function: KtNamedFunction): String? =
            function.typeReference?.text
                ?: function.bodyExpression?.let { ResultTypeHeuristics.initializerType(it) }

        private fun collectClasses(file: KtFile): Map<String, ResultLocalClass> {
            val classes = mutableMapOf<String, ResultLocalClass>()
            file.collectDescendantsOfType<KtClassOrObject>().forEach { classOrObject ->
                val name = classOrObject.name ?: return@forEach
                classes[name] =
                    ResultLocalClass(
                        memberNames = memberNamesOf(classOrObject.declarations),
                        memberTypes = memberTypesOf(classOrObject.declarations),
                        superTypeNames = superTypeNamesOf(classOrObject),
                    )
            }
            return classes
        }

        /**
         * Collects the names of every named member, regardless of whether its type is written out.
         * A member with no explicit type still shadows a callee name.
         */
        private fun memberNamesOf(declarations: List<KtDeclaration>): Set<String> = declarations.mapNotNull { memberNameOf(it) }.toSet()

        /**
         * Collects the explicitly declared types of the named members that carry a type reference.
         */
        private fun memberTypesOf(declarations: List<KtDeclaration>): Map<String, String> =
            declarations
                .mapNotNull { member ->
                    val name = memberNameOf(member) ?: return@mapNotNull null
                    val callable = member as? KtCallableDeclaration ?: return@mapNotNull null
                    val typeText = callable.typeReference?.text ?: return@mapNotNull null
                    name to typeText
                }.toMap()

        private fun memberNameOf(declaration: KtDeclaration): String? =
            when (declaration) {
                is KtCallableDeclaration -> declaration.name
                else -> null
            }

        private fun superTypeNamesOf(classOrObject: KtClassOrObject): List<String> =
            classOrObject.superTypeListEntries.mapNotNull { entry ->
                entry.typeReference?.let { ResultTypeHeuristics.baseTypeName(it.text) }
            }
    }
}
