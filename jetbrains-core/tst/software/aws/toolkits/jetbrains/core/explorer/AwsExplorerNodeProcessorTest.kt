// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core.explorer

import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Ref
import com.intellij.testFramework.ProjectRule
import com.intellij.util.ui.tree.TreeUtil
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.atLeastOnce
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import software.aws.toolkits.jetbrains.core.explorer.nodes.AwsExplorerNode
import software.aws.toolkits.jetbrains.ui.tree.AsyncTreeModel
import software.aws.toolkits.jetbrains.ui.tree.StructureTreeModel
import software.aws.toolkits.jetbrains.utils.CompatibilityUtils.registerExtension
import software.aws.toolkits.jetbrains.utils.rules.TestDisposableRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JTree
import javax.swing.tree.TreeModel

class AwsExplorerNodeProcessorTest {
    @Rule
    @JvmField
    val projectRule = ProjectRule()

    @Rule
    @JvmField
    val testDisposableRule = TestDisposableRule()

    @Test
    fun testNodePostProcessorIsInvoked() {
        val mockExtension = mock<AwsExplorerNodeProcessor>()

        registerExtension(AwsExplorerNodeProcessor.EP_NAME, mockExtension, testDisposableRule.testDisposable)

        val countDownLatch = CountDownLatch(1)

        TreeUtil.expand(JTree(createTreeModel()), 1) {
            countDownLatch.countDown()
        }

        countDownLatch.await(1, TimeUnit.SECONDS)

        verify(mockExtension, atLeastOnce()).postProcessPresentation(any(), any())
    }

    @Test
    fun testNodesArePostProcessedInBackground() {
        val ranOnCorrectThread = Ref(true)
        val ran = Ref(false)

        registerExtension(
            AwsExplorerNodeProcessor.EP_NAME,
            object : AwsExplorerNodeProcessor {
                override fun postProcessPresentation(node: AwsExplorerNode<*>, presentation: PresentationData) {
                    ran.set(true)
                    ran.set(ranOnCorrectThread.get() && !ApplicationManager.getApplication().isDispatchThread)
                }
            },
            testDisposableRule.testDisposable
        )

        val countDownLatch = CountDownLatch(1)

        TreeUtil.expand(JTree(createTreeModel()), 1) {
            countDownLatch.countDown()
        }

        countDownLatch.await(1, TimeUnit.SECONDS)

        assertThat(ran.get()).isTrue()
        assertThat(ranOnCorrectThread.get()).isTrue()
    }

    private fun createTreeModel(): TreeModel {
        val project = projectRule.project
        val awsTreeModel = AwsExplorerTreeStructure(project)
        val structureTreeModel = StructureTreeModel(awsTreeModel, testDisposableRule.testDisposable)
        return AsyncTreeModel(structureTreeModel, true, testDisposableRule.testDisposable)
    }
}
