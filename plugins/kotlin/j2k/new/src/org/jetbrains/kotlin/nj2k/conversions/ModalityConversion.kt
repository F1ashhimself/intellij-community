// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.nj2k.conversions

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.nj2k.NewJ2kConverterContext
import org.jetbrains.kotlin.nj2k.RecursiveApplicableConversionBase
import org.jetbrains.kotlin.nj2k.psi
import org.jetbrains.kotlin.nj2k.tree.*


class ModalityConversion(context: NewJ2kConverterContext) : RecursiveApplicableConversionBase(context) {
    override fun applyToElement(element: JKTreeElement): JKTreeElement {
        when (element) {
            is JKClass -> processClass(element)
            is JKMethod -> processMethod(element)
            is JKField -> processField(element)
        }
        return recurse(element)
    }

    private fun processClass(klass: JKClass) {
        klass.modality = when {
            klass.classKind == JKClass.ClassKind.ENUM -> Modality.FINAL
            klass.classKind == JKClass.ClassKind.INTERFACE -> Modality.OPEN
            klass.modality == Modality.OPEN
                    && context.converter.settings.openByDefault -> Modality.OPEN
            klass.modality == Modality.OPEN
                    && !context.converter.converterServices.oldServices.referenceSearcher.hasInheritors(klass.psi as PsiClass) ->
                Modality.FINAL

            else -> klass.modality
        }
    }

    private fun processMethod(method: JKMethod) {
        val psi = method.psi<PsiMethod>() ?: return
        val containingClass = method.parentOfType<JKClass>() ?: return
        when {
            method.visibility == Visibility.PRIVATE -> {
                method.modality = Modality.FINAL
            }
            method.modality != Modality.ABSTRACT
                    && psi.findSuperMethods().isNotEmpty() -> {
                method.modality = Modality.FINAL
                if (!method.hasOtherModifier(OtherModifier.OVERRIDE)) {
                    method.otherModifierElements += JKOtherModifierElement(OtherModifier.OVERRIDE)
                }
            }
            method.modality == Modality.OPEN
                    && context.converter.settings.openByDefault
                    && containingClass.modality == Modality.OPEN
                    && method.visibility != Visibility.PRIVATE -> {
                method.modality = Modality.OPEN
            }

            method.modality == Modality.OPEN
                    && containingClass.classKind != JKClass.ClassKind.INTERFACE
                    && !context.converter.converterServices.oldServices.referenceSearcher.hasOverrides(psi) -> {
                method.modality = Modality.FINAL
            }
            else -> method.modality = method.modality
        }
    }

    private fun processField(field: JKField) {
        val containingClass = field.parentOfType<JKClass>() ?: return
        if (containingClass.classKind == JKClass.ClassKind.INTERFACE) {
            field.modality = Modality.FINAL
        }
    }
}