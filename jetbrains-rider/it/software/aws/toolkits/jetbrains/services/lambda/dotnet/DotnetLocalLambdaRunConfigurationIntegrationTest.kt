// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.lambda.dotnet

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.jetbrains.rider.test.annotations.TestEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.testng.annotations.BeforeMethod
import org.testng.annotations.DataProvider
import org.testng.annotations.Test
import software.amazon.awssdk.services.lambda.model.Runtime
import software.aws.toolkits.jetbrains.core.region.MockRegionProvider
import software.aws.toolkits.jetbrains.services.lambda.execution.local.LambdaRunConfigurationTestBase
import software.aws.toolkits.jetbrains.services.lambda.execution.local.preWarmSamVersionCache
import software.aws.toolkits.jetbrains.services.lambda.sam.executeLambda
import software.aws.toolkits.jetbrains.settings.SamSettings

class DotnetLocalLambdaRunConfigurationIntegrationTest : LambdaRunConfigurationTestBase() {
    companion object {
        @JvmStatic
        @DataProvider(name = "runtimes")
        fun data(): Iterator<String> = listOf(
            Runtime.DOTNETCORE2_1.toString()
        ).listIterator()
    }

    private val handler = "EchoLambda::EchoLambda.Function::FunctionHandler"

    @BeforeMethod
    fun setUp() {
        SamSettings.getInstance().savedExecutablePath = System.getenv()["SAM_CLI_EXEC"]
        preWarmSamVersionCache(SamSettings.getInstance().executablePath, 5000)
    }

    override fun getSolutionDirectoryName(): String = "EchoLambda"

    @Test(dataProvider = "runtimes")
    @TestEnvironment(solution = "EchoLambda")
    fun samIsExecuted(runtime: String) {
        val runConfiguration = createHandlerBasedRunConfiguration(runtime = Runtime.fromValue(runtime), handler = handler)
        assertThat(runConfiguration).isNotNull

        val executeLambda = executeLambda(runConfiguration)
        assertThat(executeLambda.exitCode).isEqualTo(0)
    }

    @Test(dataProvider = "runtimes")
    @TestEnvironment(solution = "EchoLambda")
    fun envVarsArePassed(runtime: String) {
        val envVars = mutableMapOf("Foo" to "Bar", "Bat" to "Baz")

        val runConfiguration = createHandlerBasedRunConfiguration(runtime = Runtime.fromValue(runtime), handler = handler, environmentVariables = envVars)
        assertThat(runConfiguration).isNotNull

        val executeLambda = executeLambda(runConfiguration)

        assertThat(executeLambda.exitCode).isEqualTo(0)
        assertThat(jsonToMap(executeLambda.stdout))
            .containsEntry("Foo", "Bar")
            .containsEntry("Bat", "Baz")
    }

    @Test(dataProvider = "runtimes")
    @TestEnvironment(solution = "EchoLambda")
    fun regionIsPassed(runtime: String) {
        val runConfiguration = createHandlerBasedRunConfiguration(runtime = Runtime.fromValue(runtime), handler = handler)
        assertThat(runConfiguration).isNotNull

        val executeLambda = executeLambda(runConfiguration)

        assertThat(executeLambda.exitCode).isEqualTo(0)
        assertThat(jsonToMap(executeLambda.stdout))
            .containsEntry("AWS_REGION", MockRegionProvider.US_EAST_1.id)
    }

    @Test(dataProvider = "runtimes")
    @TestEnvironment(solution = "EchoLambda")
    fun credentialsArePassed(runtime: String) {
        val runConfiguration = createHandlerBasedRunConfiguration(runtime = Runtime.fromValue(runtime), handler = handler)
        assertThat(runConfiguration).isNotNull

        val executeLambda = executeLambda(runConfiguration)

        assertThat(executeLambda.exitCode).isEqualTo(0)
        assertThat(jsonToMap(executeLambda.stdout))
            .containsEntry("AWS_ACCESS_KEY_ID", mockCreds.accessKeyId())
            .containsEntry("AWS_SECRET_ACCESS_KEY", mockCreds.secretAccessKey())
    }

    private fun jsonToMap(data: String) = jacksonObjectMapper().readValue<Map<String, Any>>(data)
}
