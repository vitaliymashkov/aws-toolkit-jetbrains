// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.lambda.dotnet

import com.intellij.openapi.project.Project
import com.intellij.openapi.rd.defineNestedLifetime
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.rd.framework.impl.RpcTimeouts
import com.jetbrains.rd.util.AtomicInteger
import com.jetbrains.rd.util.spinUntil
import com.jetbrains.rd.util.threading.SynchronousScheduler
import com.jetbrains.rider.model.HandlerExistRequest
import com.jetbrains.rider.model.MethodExistingRequest
import com.jetbrains.rider.model.backendPsiHelperModel
import com.jetbrains.rider.model.lambdaPsiModel
import com.jetbrains.rider.model.publishableProjectsModel
import com.jetbrains.rider.projectView.ProjectModelViewHost
import com.jetbrains.rider.projectView.solution
import com.jetbrains.rider.run.configurations.method.getProjectModeId
import software.aws.toolkits.jetbrains.services.lambda.LambdaHandlerResolver
import software.aws.toolkits.jetbrains.services.lambda.dotnet.element.RiderLambdaHandlerFakePsiElement

class DotNetLambdaHandlerResolver : LambdaHandlerResolver {

    companion object {
        val handlerExistRequestId = AtomicInteger(1)
        val handlerExistTimeoutMs = RpcTimeouts.default.errorAwaitTime
    }

    override fun version(): Int = 1

    override fun findPsiElements(
        project: Project,
        handler: String,
        searchScope: GlobalSearchScope
    ): Array<NavigatablePsiElement> {
        val fieldId = getFieldIdByHandlerName(project, handler)
        if (fieldId < 0) return emptyArray()

        return arrayOf(RiderLambdaHandlerFakePsiElement(project, handler, fieldId))
    }

    override fun determineHandler(element: PsiElement): String? = null

    override fun determineHandlers(element: PsiElement, file: VirtualFile): Set<String> {
        val handler = determineHandler(element) ?: return emptySet()
        return setOf(handler)
    }

    override fun isHandlerValid(project: Project, handler: String): Boolean {
        val handlerParts = handler.split("::")
        if (handlerParts.size != 3) return false

        val assemblyName = handlerParts[0]
        val type = handlerParts[1]
        val methodName = handlerParts[2]

        return doesMethodExist(project, assemblyName, type, methodName)
    }

    fun getFieldIdByHandlerName(project: Project, handler: String): Int {
        val handlerParts = handler.split("::")
        if (handlerParts.size != 3) return -1

        val assemblyName = handlerParts[0]
        val type = handlerParts[1]
        val methodName = handlerParts[2]

        val projectModelViewHost = ProjectModelViewHost.getInstance(project)
        val publishableProjects = project.solution.publishableProjectsModel.publishableProjects.values.toList()
        val projectToProcess = publishableProjects.find { it.projectName == assemblyName } ?: return -1

        val model = project.solution.backendPsiHelperModel
        val fileIdResponse = model.findPublicMethod.sync(
            request = MethodExistingRequest(
                className = type,
                methodName = methodName,
                targetFramework = "",
                projectId = projectModelViewHost.getProjectModeId(projectToProcess.projectFilePath)),
            timeouts = RpcTimeouts.default
        )

        return fileIdResponse?.fileId ?: -1
    }

    private fun doesMethodExist(project: Project, assemblyName: String, type: String, methodName: String): Boolean {
        val projectModelViewHost = ProjectModelViewHost.getInstance(project)
        val projects = project.solution.publishableProjectsModel.publishableProjects.values.toList()
        val projectToProcess = projects.find { it.projectName == assemblyName } ?: return false

        val model = project.solution.lambdaPsiModel
        val lifetime = project.defineNestedLifetime()

        val handlerExistRequest = HandlerExistRequest(
            requestId = handlerExistRequestId.getAndIncrement(),
            className = type,
            methodName = methodName,
            projectId = projectModelViewHost.getProjectModeId(projectToProcess.projectFilePath)
        )

        try {
            var isHandlerExistValue: Boolean? = null
            model.isHandlerExistResponse.adviseOn(lifetime, SynchronousScheduler) { handler ->
                if (handler.requestId == handlerExistRequest.requestId) {
                    isHandlerExistValue = handler.value
                }
            }

            model.isHandlerExistRequest.fire(handlerExistRequest)

            spinUntil(handlerExistTimeoutMs) { isHandlerExistValue != null }

            return isHandlerExistValue
                ?: throw IllegalStateException(
                    "Timeout after $handlerExistTimeoutMs ms waiting for checking if handler with the name '$type.$methodName' exists."
                )
        } finally {
            lifetime.terminate()
        }
    }
}
