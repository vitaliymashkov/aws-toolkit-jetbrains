// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package protocol.model.setting

import java.io.File
import com.jetbrains.rd.generator.nova.csharp.CSharp50Generator
import com.jetbrains.rd.generator.nova.doc
import com.jetbrains.rd.generator.nova.kotlin.Kotlin11Generator
import com.jetbrains.rd.generator.nova.setting
import com.jetbrains.rd.generator.nova.source
import com.jetbrains.rd.generator.nova.util.syspropertyOrInvalid
import com.jetbrains.rd.generator.nova.Ext
import com.jetbrains.rd.generator.nova.ExternalGenerator
import com.jetbrains.rd.generator.nova.FlowTransform
import com.jetbrains.rd.generator.nova.GeneratorBase
import com.jetbrains.rd.generator.nova.PredefinedType.bool
import com.jetbrains.rider.model.nova.ide.IdeRoot
import com.jetbrains.rider.model.nova.ide.SolutionModel

object AwsSettingKotlinGenerator : ExternalGenerator(
    Kotlin11Generator(FlowTransform.AsIs, "com.jetbrains.rider.model", File(syspropertyOrInvalid("ktGeneratedOutput"))),
    IdeRoot)

object AwsSettingCSharpGenerator : ExternalGenerator(
    CSharp50Generator(FlowTransform.Reversed, "JetBrains.Rider.Model", File(syspropertyOrInvalid("csAwsSettingGeneratedOutput"))),
    IdeRoot)

@Suppress("unused")
object AwsSettingModel : Ext(SolutionModel.Solution) {

    init {
        setting(GeneratorBase.AcceptsGenerator) { generator ->
            generator == AwsSettingKotlinGenerator.generator ||
                generator == AwsSettingCSharpGenerator.generator
        }

        source("showLambdaGutterMarks", bool)
            .doc("Flag indicating whether Lambda gutter marks should be shown in editor")
    }
}
