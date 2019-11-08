// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core.explorer

import com.intellij.openapi.project.Project
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.sfn.SfnClient
import software.aws.toolkits.jetbrains.core.explorer.nodes.AwsExplorerServiceRootNode
import software.aws.toolkits.jetbrains.services.cloudformation.CloudFormationServiceNode
import software.aws.toolkits.jetbrains.services.lambda.LambdaServiceNode
import software.aws.toolkits.jetbrains.services.sfn.SfnServiceNode
import software.aws.toolkits.resources.message

enum class AwsExplorerService(val serviceId: String, val displayName: String) {
    CLOUDFORMATION(CloudFormationClient.SERVICE_NAME, message("explorer.node.cloudformation")) {
        override fun buildServiceRootNode(project: Project) = CloudFormationServiceNode(project)
    },
    LAMBDA(LambdaClient.SERVICE_NAME, message("explorer.node.lambda")) {
        override fun buildServiceRootNode(project: Project) = LambdaServiceNode(project)
    },
    SFN(SfnClient.SERVICE_NAME, message("explorer.node.sfn")) {
        override fun buildServiceRootNode(project: Project) = SfnServiceNode(project)
    },
    ;

    abstract fun buildServiceRootNode(project: Project): AwsExplorerServiceRootNode
}
