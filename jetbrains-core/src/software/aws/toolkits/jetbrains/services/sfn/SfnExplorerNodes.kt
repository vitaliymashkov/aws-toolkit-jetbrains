// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.sfn

import com.intellij.openapi.project.Project
import com.intellij.psi.NavigatablePsiElement
import icons.AwsIcons
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration
import software.amazon.awssdk.services.sfn.SfnClient
import software.aws.toolkits.jetbrains.core.explorer.AwsExplorerService
import software.aws.toolkits.jetbrains.core.explorer.nodes.AwsExplorerNode
import software.aws.toolkits.jetbrains.core.explorer.nodes.AwsExplorerResourceNode
import software.aws.toolkits.jetbrains.core.explorer.nodes.CacheBackedAwsExplorerServiceRootNode
import software.aws.toolkits.jetbrains.services.lambda.resources.LambdaResources
import software.aws.toolkits.jetbrains.services.lambda.*

class SfnServiceNode(project: Project) :
    CacheBackedAwsExplorerServiceRootNode<FunctionConfiguration>(project, AwsExplorerService.SFN, LambdaResources.LIST_FUNCTIONS) {
    override fun toNode(child: FunctionConfiguration): AwsExplorerNode<*> = SfnNode(nodeProject, child.toDataClass(credentialProvider.id, region))
}

open class SfnNode(
    project: Project,
    function: LambdaFunction,
    immutable: Boolean = false
) : AwsExplorerResourceNode<LambdaFunction>(
    project,
    SfnClient.SERVICE_NAME,
    function,
    AwsIcons.Resources.LAMBDA_FUNCTION,
    immutable
) {
    override fun resourceType() = "stepfunction"

    override fun resourceArn() = value.arn

    override fun toString(): String = functionName()

    override fun displayName() = functionName()

    fun functionName(): String = value.name

    fun handlerPsi(): Array<NavigatablePsiElement> =
        Lambda.findPsiElementsForHandler(nodeProject, value.runtime, value.handler)
}
