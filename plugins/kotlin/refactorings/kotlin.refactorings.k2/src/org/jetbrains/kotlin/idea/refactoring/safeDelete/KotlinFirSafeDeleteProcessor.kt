// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.refactoring.safeDelete

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.safeDelete.JavaSafeDeleteDelegate
import com.intellij.refactoring.safeDelete.NonCodeUsageSearchInfo
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteProcessorDelegateBase
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteReferenceSimpleDeleteUsageInfo
import com.intellij.refactoring.util.RefactoringDescriptionLocation
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import com.intellij.util.containers.map2Array
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.analyzeInModalWindow
import org.jetbrains.kotlin.analysis.api.symbols.KtCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtClassOrObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtSymbolWithModality
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.idea.refactoring.*
import org.jetbrains.kotlin.idea.search.search
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.parameterIndex

class KotlinFirSafeDeleteProcessor : SafeDeleteProcessorDelegateBase() {
    override fun handlesElement(element: PsiElement?) = element.canDeleteElement()

    override fun findUsages(
        element: PsiElement,
        allElementsToDelete: Array<out PsiElement>,
        result: MutableList<UsageInfo>
    ): NonCodeUsageSearchInfo {
        val isInside: (t: PsiElement) -> Boolean =
            { allElementsToDelete.any { elementToDelete -> SafeDeleteProcessor.isInside(it, elementToDelete) } }
        if (element is KtDeclaration) {
            ReferencesSearch.search(element).forEach(Processor {
                val e = it.element
                if (!isInside(e)) {
                    if (e.getNonStrictParentOfType<KtValueArgumentName>() != null) {
                        //named argument would be deleted with argument
                        return@Processor true
                    }
                    val importDirective = e.getNonStrictParentOfType<KtImportDirective>()
                    result.add(SafeDeleteReferenceSimpleDeleteUsageInfo(importDirective ?: e, element, importDirective != null))
                }
                return@Processor true
            })
        }

        val additionalElementsToDelete = arrayListOf<PsiElement>()
        if (element is KtNamedFunction) {
            findFunctionUsages(element, allElementsToDelete, isInside, additionalElementsToDelete, result)
        }
        
        if (element is KtTypeParameter) {
            val owner = element.getNonStrictParentOfType<KtTypeParameterListOwner>()
            if (owner != null) {
                val parameterList = owner.typeParameters
                val parameterIndex = parameterList.indexOf(element)
                for (reference in ReferencesSearch.search(owner)) {
                    JavaSafeDeleteDelegate.EP.forLanguage(reference.element.language).createJavaTypeParameterUsageInfo(
                        reference,
                        result,
                        element,
                        parameterList.size,
                        parameterIndex
                    )
                }
            }
        }
        
        if (element is KtParameter) {
            val function = element.getNonStrictParentOfType<KtFunction>()
            if (function != null) {
                val parameterIndexAsJavaCall = element.parameterIndex() + if (function.receiverTypeReference != null) 1 else 0
                findCallArgumentsToDelete(result, element, parameterIndexAsJavaCall, function)
                if (function is KtPrimaryConstructor) {
                    findCallArgumentsToDelete(result, element, parameterIndexAsJavaCall, function.getContainingClassOrObject())
                }
            }
        }
        
        return NonCodeUsageSearchInfo(isInside, element)
    }

    private fun findFunctionUsages(
        element: KtNamedFunction,
        allElementsToDelete: Array<out PsiElement>,
        isInside: (t: PsiElement) -> Boolean,
        additionalElementsToDelete: ArrayList<PsiElement>,
        result: MutableList<UsageInfo>
    ) {
        val overridden = arrayListOf<PsiElement>()
        val containingClass = element.containingClass()
        if (containingClass != null) {
            analyze(containingClass) {
                val elementClassSymbol = containingClass.getSymbol() as KtClassOrObjectSymbol

                fun isMultipleInheritance(function: KtSymbol): Boolean {
                    val superMethods = (function as? KtCallableSymbol)?.getDirectlyOverriddenSymbols() ?: return false
                    return superMethods.any {
                        val superClassSymbol = it.getContainingSymbol() as? KtClassOrObjectSymbol ?: return@any false
                        return@any superClassSymbol != elementClassSymbol && !superClassSymbol.isSubClassOf(elementClassSymbol)
                    }
                }

                search(element).forEach { m ->
                    val original = m.unwrapped
                    if (original is KtDeclaration && !allElementsToDelete.contains(original)) {
                        analyze(original) {
                            if (isMultipleInheritance(original.getSymbol())) {
                                return@forEach
                            }
                        }
                        overridden.add(original)
                    }
                }
            }
        }

        for (overriddenFunction in overridden) {
            if (ReferencesSearch.search(overriddenFunction).forEach(Processor {
                    val place = it.element
                    if (!isInside(place)) {
                        return@Processor false
                    }
                    return@Processor true
                })) {
                additionalElementsToDelete.add(overriddenFunction)
                result.add(SafeDeleteReferenceSimpleDeleteUsageInfo(overriddenFunction, element, true))
            } else {
                result.add(SafeDeleteOverrideUsageInfo(overriddenFunction, element))
            }
        }
    }

    private fun findCallArgumentsToDelete(
        result: MutableList<UsageInfo>,
        element: KtParameter,
        parameterIndexAsJavaCall: Int,
        ktElement: KtElement
    ) {
        ReferencesSearch.search(ktElement).forEach(Processor {
            JavaSafeDeleteDelegate.EP.forLanguage(it.element.language)
                ?.createUsageInfoForParameter(it, result, element, parameterIndexAsJavaCall, element.isVarArg)
            return@Processor true
        })
    }

    override fun getElementsToSearch(
        element: PsiElement,
        module: Module?,
        allElementsToDelete: MutableCollection<PsiElement>
    ): MutableCollection<out PsiElement> {
        return arrayListOf(element)
    }

    override fun getAdditionalElementsToDelete(
        element: PsiElement,
        allElementsToDelete: MutableCollection<PsiElement>,
        askUser: Boolean
    ): MutableCollection<PsiElement>? {
        return null
    }

    override fun findConflicts(element: PsiElement, allElementsToDelete: Array<out PsiElement>): MutableCollection<String>? {
        if (element is KtNamedFunction || element is KtProperty) {
            val ktClass = element.getNonStrictParentOfType<KtClass>()
            if (ktClass == null || ktClass.body != element.parent) return null

            val modifierList = ktClass.modifierList
            if (modifierList != null && modifierList.hasModifier(KtTokens.ABSTRACT_KEYWORD)) return null

            return analyzeInModalWindow(element as KtDeclaration, RefactoringBundle.message("detecting.possible.conflicts")) {
                (element.getSymbol() as? KtCallableSymbol)?.getAllOverriddenSymbols()
                    ?.asSequence()
                    ?.filter { (it as? KtSymbolWithModality)?.modality == Modality.ABSTRACT }
                    ?.mapNotNull { it.psi }
                    ?.mapTo(ArrayList()) {
                        KotlinK2RefactoringsBundle.message(
                            "safe.delete.implements.conflict.message", 
                            ElementDescriptionUtil.getElementDescription(element, RefactoringDescriptionLocation.WITH_PARENT), 
                            ElementDescriptionUtil.getElementDescription(it, RefactoringDescriptionLocation.WITH_PARENT)
                        )
                    }
            }
        }
        return null
    }

    override fun preprocessUsages(project: Project?, usages: Array<out UsageInfo>?): Array<UsageInfo>? {
        return usages?.map2Array { it }
    }

    override fun prepareForDeletion(element: PsiElement?) {
        when (element) {
            is KtTypeParameter -> {
                deleteSeparatingComma(element)
                deleteBracesAroundEmptyList(element)
            }

            is KtParameter -> {
                deleteSeparatingComma(element)
            }
        }
    }


    override fun isToSearchInComments(element: PsiElement?): Boolean {
        val settings = KotlinFirRefactoringsSettings.instance
        when (element) {
            is KtClass -> return settings.RENAME_SEARCH_IN_COMMENTS_FOR_CLASS
            is KtFunction -> return settings.RENAME_SEARCH_IN_COMMENTS_FOR_FUNCTION
            is KtProperty -> return settings.RENAME_SEARCH_IN_COMMENTS_FOR_PROPERTY
            else -> return false
        }
    }

    override fun setToSearchInComments(element: PsiElement?, enabled: Boolean) {
        val settings = KotlinFirRefactoringsSettings.instance
        when (element) {
            is KtClass -> settings.RENAME_SEARCH_IN_COMMENTS_FOR_CLASS = enabled
            is KtFunction -> settings.RENAME_SEARCH_IN_COMMENTS_FOR_FUNCTION = enabled
            is KtProperty -> settings.RENAME_SEARCH_IN_COMMENTS_FOR_PROPERTY = enabled
        }
    }

    override fun isToSearchForTextOccurrences(element: PsiElement?): Boolean {
        val settings = KotlinFirRefactoringsSettings.instance
        when (element) {
            is KtClass -> return settings.RENAME_SEARCH_FOR_TEXT_FOR_CLASS
            is KtFunction -> return settings.RENAME_SEARCH_FOR_TEXT_FOR_FUNCTION
            is KtProperty -> return settings.RENAME_SEARCH_FOR_TEXT_FOR_VARIABLE
            else -> return false
        }
    }

    override fun setToSearchForTextOccurrences(element: PsiElement?, enabled: Boolean) {
        val settings = KotlinFirRefactoringsSettings.instance
        when (element) {
            is KtClass -> settings.RENAME_SEARCH_FOR_TEXT_FOR_CLASS = enabled
            is KtFunction -> settings.RENAME_SEARCH_FOR_TEXT_FOR_FUNCTION = enabled
            is KtProperty -> settings.RENAME_SEARCH_FOR_TEXT_FOR_VARIABLE = enabled
        }
    }
}
