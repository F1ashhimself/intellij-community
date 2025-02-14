// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.j2k.post.processing.processings

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.childrenOfType
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget.CONSTRUCTOR_PARAMETER
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget.FIELD
import org.jetbrains.kotlin.idea.core.setVisibility
import org.jetbrains.kotlin.idea.intentions.addUseSiteTarget
import org.jetbrains.kotlin.idea.j2k.post.processing.*
import org.jetbrains.kotlin.idea.util.CommentSaver
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.nj2k.NewJ2kConverterContext
import org.jetbrains.kotlin.nj2k.escaped
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

internal class MergePropertyWithConstructorParameterProcessing : ElementsBasedPostProcessing() {
    override fun runProcessing(elements: List<PsiElement>, converterContext: NewJ2kConverterContext) {
        for (klass in runReadAction { elements.descendantsOfType<KtClass>() }) {
            convertClass(klass)
        }
    }

    private fun convertClass(klass: KtClass) {
        val initializations = runReadAction { collectPropertyInitializations(klass) }.ifEmpty { return }
        runUndoTransparentActionInEdt(inWriteAction = true) {
            for (initialization in initializations) {
                val commentSaver = CommentSaver(initialization.assignment, saveLineBreaks = true)
                val restoreCommentsTarget: KtExpression
                when (initialization) {
                    is ConstructorParameterInitialization -> {
                        initialization.mergePropertyAndConstructorParameter()
                        restoreCommentsTarget = initialization.initializer
                    }

                    is LiteralInitialization -> {
                        val (property, initializer, _) = initialization
                        property.initializer = initializer
                        restoreCommentsTarget = property
                    }
                }
                initialization.assignment.delete()
                commentSaver.restore(restoreCommentsTarget, forceAdjustIndent = false)
            }

            klass.removeEmptyInitBlocks()
            klass.removeRedundantEnumSemicolon()
        }
    }

    private fun collectPropertyInitializations(klass: KtClass): List<Initialization<*>> {
        val usedParameters = mutableSetOf<KtParameter>()
        val usedProperties = mutableSetOf<KtProperty>()
        val initializations = mutableListOf<Initialization<*>>()

        fun KtExpression.asProperty() = unpackedReferenceToProperty()?.takeIf {
            it !in usedProperties && it.containingClass() == klass && it.initializer == null
        }

        fun KtReferenceExpression.asParameter() = resolve()?.safeAs<KtParameter>()?.takeIf {
            it !in usedParameters && it.containingClass() == klass && !it.hasValOrVar()
        }

        fun KtProperty.isSameTypeAs(parameter: KtParameter): Boolean {
            val propertyType = type() ?: return false
            val parameterType = parameter.type() ?: return false
            return KotlinTypeChecker.DEFAULT.equalTypes(propertyType, parameterType)
        }

        val initializer = klass.getAnonymousInitializers().singleOrNull() ?: return emptyList()
        val statements = initializer.body?.safeAs<KtBlockExpression>()?.statements ?: return emptyList()

        for (statement in statements) {
            val assignment = statement.asAssignment() ?: break
            val property = assignment.left?.asProperty() ?: break
            usedProperties += property

            when (val rightSide = assignment.right) {
                is KtReferenceExpression -> {
                    val parameter = rightSide.asParameter() ?: break
                    if (!property.isSameTypeAs(parameter)) break
                    usedParameters += parameter
                    initializations += ConstructorParameterInitialization(property, parameter, assignment)
                }

                is KtConstantExpression, is KtStringTemplateExpression -> {
                    initializations += LiteralInitialization(property, rightSide, assignment)
                }

                else -> {}
            }
        }

        return initializations
    }

    private fun ConstructorParameterInitialization.mergePropertyAndConstructorParameter() {
        val (property, parameter, _) = this

        parameter.addBefore(property.valOrVarKeyword, parameter.nameIdentifier!!)
        parameter.addAfter(KtPsiFactory(property).createWhiteSpace(), parameter.valOrVarKeyword!!)
        parameter.rename(property.name!!)
        parameter.setVisibility(property.visibilityModifierTypeOrDefault())
        val commentSaver = CommentSaver(property, saveLineBreaks = true)

        parameter.annotationEntries.forEach {
            if (it.useSiteTarget == null) it.addUseSiteTarget(CONSTRUCTOR_PARAMETER, property.project)
        }
        property.annotationEntries.forEach {
            parameter.addAnnotationEntry(it).also { entry ->
                if (entry.useSiteTarget == null) entry.addUseSiteTarget(FIELD, property.project)
            }
        }
        property.typeReference?.annotationEntries?.forEach { parameter.typeReference?.addAnnotationEntry(it) }

        property.delete()
        commentSaver.restore(parameter, forceAdjustIndent = false)
    }

    private fun KtCallableDeclaration.rename(newName: String) {
        val factory = KtPsiFactory(this)
        val escapedName = newName.escaped()
        ReferencesSearch.search(this, LocalSearchScope(containingKtFile)).forEach {
            it.element.replace(factory.createExpression(escapedName))
        }
        setName(escapedName)
    }

    private fun KtClass.removeEmptyInitBlocks() {
        for (initBlock in getAnonymousInitializers()) {
            if ((initBlock.body as KtBlockExpression).statements.isEmpty()) {
                val commentSaver = CommentSaver(initBlock)
                initBlock.delete()
                primaryConstructor?.let { commentSaver.restore(it) }
            }
        }
    }

    private fun KtClass.removeRedundantEnumSemicolon() {
        if (!isEnum()) return
        val enumEntries = body?.childrenOfType<KtEnumEntry>().orEmpty()
        val otherMembers = body?.childrenOfType<KtDeclaration>()?.filterNot { it is KtEnumEntry }.orEmpty()
        if (otherMembers.isNotEmpty()) return
        if (enumEntries.isNotEmpty()) {
            enumEntries.lastOrNull()?.removeSemicolon()
        } else {
            body?.removeSemicolon()
        }
    }

    private fun KtElement.removeSemicolon() {
        getChildrenOfType<LeafPsiElement>().find { it.text == ";" }?.delete()
    }
}

private sealed class Initialization<I : KtElement> {
    abstract val property: KtProperty
    abstract val initializer: I
    abstract val assignment: KtBinaryExpression
}

private data class ConstructorParameterInitialization(
    override val property: KtProperty,
    override val initializer: KtParameter,
    override val assignment: KtBinaryExpression
) : Initialization<KtParameter>()

private data class LiteralInitialization(
    override val property: KtProperty,
    override val initializer: KtExpression,
    override val assignment: KtBinaryExpression
) : Initialization<KtExpression>()
