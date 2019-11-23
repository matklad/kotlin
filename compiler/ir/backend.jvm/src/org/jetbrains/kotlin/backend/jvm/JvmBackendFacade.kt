/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.ir.createParameterDeclarations
import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.backend.jvm.codegen.ClassCodegen
import org.jetbrains.kotlin.backend.jvm.lower.MultifileFacadeFileEntry
import org.jetbrains.kotlin.backend.jvm.serialization.JvmMangler
import org.jetbrains.kotlin.codegen.CompilationErrorHandler
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.load.kotlin.JvmPackagePartSource
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi2ir.Psi2IrTranslator
import org.jetbrains.kotlin.psi2ir.PsiSourceManager
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

object JvmBackendFacade {
    fun doGenerateFiles(
        files: Collection<KtFile>,
        state: GenerationState,
        errorHandler: CompilationErrorHandler,
        phaseConfig: PhaseConfig
    ) {
        val facadeGenerator = FacadeClassGenerator()
        val psi2ir = Psi2IrTranslator(state.languageVersionSettings, facadeClassGenerator = facadeGenerator::generate, mangler = JvmMangler)
        val psi2irContext = psi2ir.createGeneratorContext(state.module, state.bindingContext, extensions = JvmGeneratorExtensions)
        val irProviders = generateTypicalIrProviderList(
            psi2irContext.moduleDescriptor, psi2irContext.irBuiltIns, psi2irContext.symbolTable,
            externalDeclarationOrigin = JvmGeneratorExtensions.externalDeclarationOrigin,
            facadeClassGenerator = facadeGenerator::generate
        )
        val irModuleFragment = psi2ir.generateModuleFragment(psi2irContext, files, irProviders = irProviders)
        doGenerateFilesInternal(
            state, errorHandler, irModuleFragment, psi2irContext.symbolTable, psi2irContext.sourceManager, phaseConfig,
            facadeGenerator.classNameOverride
        )
    }

    internal fun doGenerateFilesInternal(
        state: GenerationState,
        errorHandler: CompilationErrorHandler,
        irModuleFragment: IrModuleFragment,
        symbolTable: SymbolTable,
        sourceManager: PsiSourceManager,
        phaseConfig: PhaseConfig,
        classNameOverride: MutableMap<IrClass, JvmClassName>
    ) {
        val context = JvmBackendContext(
            state, sourceManager, irModuleFragment.irBuiltins, irModuleFragment, symbolTable, phaseConfig, classNameOverride
        )
        state.irBasedMapAsmMethod = { descriptor ->
            context.methodSignatureMapper.mapAsmMethod(context.referenceFunction(descriptor).owner)
        }
        state.mapInlineClass = { descriptor ->
            context.typeMapper.mapType(context.referenceClass(descriptor).owner.defaultType)
        }

        for (irFile in irModuleFragment.files) {
            for (extension in IrGenerationExtension.getInstances(context.state.project)) {
                extension.generate(irFile, context, context.state.bindingContext)
            }
        }

        try {
            JvmLower(context).lower(irModuleFragment)
        } catch (e: Throwable) {
            errorHandler.reportException(e, null)
        }

        for (generateMultifileFacade in listOf(true, false)) {
            for (irFile in irModuleFragment.files) {
                // Generate multifile facades first, to compute and store JVM signatures of const properties which are later used
                // when serializing metadata in the multifile parts.
                // TODO: consider dividing codegen itself into separate phases (bytecode generation, metadata serialization) to avoid this
                val isMultifileFacade = irFile.fileEntry is MultifileFacadeFileEntry
                if (isMultifileFacade != generateMultifileFacade) continue

                try {
                    for (loweredClass in irFile.declarations) {
                        if (loweredClass !is IrClass) {
                            throw AssertionError("File-level declaration should be IrClass after JvmLower, got: " + loweredClass.render())
                        }

                        ClassCodegen.generate(loweredClass, context)
                    }
                    state.afterIndependentPart()
                } catch (e: Throwable) {
                    errorHandler.reportException(e, null) // TODO ktFile.virtualFile.url
                }
            }
        }
    }

    internal class FacadeClassGenerator {
        val classNameOverride = mutableMapOf<IrClass, JvmClassName>()

        fun generate(source: DeserializedContainerSource): IrClass? {
            val jvmPackagePartSource = source.safeAs<JvmPackagePartSource>() ?: return null
            val facadeName = jvmPackagePartSource.facadeClassName ?: jvmPackagePartSource.className
            return buildClass {
                origin = IrDeclarationOrigin.FILE_CLASS
                name = facadeName.fqNameForTopLevelClassMaybeWithDollars.shortName()
            }.also {
                it.createParameterDeclarations()
                classNameOverride[it] = facadeName
            }
        }
    }
}
