// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.lambda.dotnet

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.rd.defineNestedLifetime
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.jetbrains.rd.framework.IProtocol
import com.jetbrains.rd.framework.IdKind
import com.jetbrains.rd.framework.Identities
import com.jetbrains.rd.framework.Protocol
import com.jetbrains.rd.framework.Serializers
import com.jetbrains.rd.framework.SocketWire
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.isAlive
import com.jetbrains.rd.util.lifetime.onTermination
import com.jetbrains.rd.util.put
import com.jetbrains.rd.util.reactive.adviseUntil
import com.jetbrains.rdclient.protocol.RdDispatcher
import com.jetbrains.rider.RiderEnvironment
import com.jetbrains.rider.debugger.DebuggerWorkerPlatform
import com.jetbrains.rider.debugger.DotNetDebugProcess
import com.jetbrains.rider.debugger.DotNetDebugRunner
import com.jetbrains.rider.debugger.RiderDebuggerWorkerModelManager
import com.jetbrains.rider.debugger.actions.utils.OptionsUtil
import com.jetbrains.rider.model.debuggerWorker.DotNetCoreExeStartInfo
import com.jetbrains.rider.model.debuggerWorker.DotNetCoreInfo
import com.jetbrains.rider.model.debuggerWorker.DotNetDebuggerSessionModel
import com.jetbrains.rider.model.debuggerWorkerConnectionHelperModel
import com.jetbrains.rider.projectView.solution
import com.jetbrains.rider.run.IDebuggerOutputListener
import com.jetbrains.rider.run.bindToSettings
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import software.aws.toolkits.core.utils.error
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.core.utils.trace
import software.aws.toolkits.jetbrains.services.lambda.execution.local.SamDebugSupport
import software.aws.toolkits.jetbrains.services.lambda.execution.local.SamRunningState
import software.aws.toolkits.resources.message
import java.io.File
import java.io.OutputStream
import java.util.Timer
import kotlin.concurrent.schedule

/**
 * Rider uses it's own DebuggerWorker process that run under .NET with extra parameters to configure:
 *    - path to runtime
 *    - path to DLL
 *    - debugger mode (client/server)
 *    - frontend port
 *    - backend port
 *
 * Debugger is launched via SAM CLI command: '$ sam local invoke' and pass debugger parameters --debugger-path and --debugger-args.
 * Under SAM CLI this command is translated into:
 *    - volume that is mounted in Docker to launch debugger (--debugger-path).
 *      This volume is set to path with debugger process used in Rider - DebuggerWorker - inside Rider SDK.
 *    - additional debugger arguments that are applied when launching debugger inside Docker (--debugger-args):
 *      '$ dotnet <debug_args_list> MockBootstraps.dll --debugger-spin-wait'
 *
 * We use <debugger_args_list> to inject a simple custom dotnet core application 'JetBrains.Rider.Debugger.Launcher'
 * that run Rider's debugger. This app starts Rider's debugger under Mono runtime that is bundled with Rider SDK (runtime.sh).
 * The app read arguments and start a Rider's DebuggerWorker process with ports and compiled lambda DLL - MockBootstraps.dll
 *
 * 'JetBrains.Rider.Debugger.Launcher' app is hosted on github repo - https://github.com/JetBrains/JetBrains.Rider.Debugger.Launcher.
 * The app is packed to NuGet package and is accessible in Rider SDK in '<SDKPath>/lib/ReSharperHost/' directory.
 */
class DotNetSamDebugSupport : SamDebugSupport {
    companion object {
        private val logger = getLogger<DotNetSamDebugSupport>()

        const val DEBUGGER_LAUNCHER_NAME = "JetBrains.Rider.Debugger.Launcher"
        private const val DEBUGGER_MODE = "server"

        private const val REMOTE_DEBUGGER_DIR = "/tmp/lambci_debug_files"
        private const val REMOTE_NETCORE_CLI_PATH = "/var/lang/bin/dotnet"
        private const val REMOTE_LAMBDA_COMPILED_PATH = "/var/runtime/MockBootstraps.dll"
    }

    private val debuggerAssemblyFile =
        RiderEnvironment.getBundledFile(DebuggerWorkerPlatform.AnyCpu.assemblyName)

    private val debuggerBinDirectory = debuggerAssemblyFile.parentFile

    /**
     * Check whether the JatBrains.Rider.Worker.Launcher app (that is required to run Debugger) is downloaded into Rider SDK.
     */
    override fun isSupported(): Boolean {
        val debugLauncherFile = File(debuggerBinDirectory, "$DEBUGGER_LAUNCHER_NAME.dll")

        val debuggerLauncherExists = debugLauncherFile.exists()
        if (!debuggerLauncherExists) {
            logger.error { "$DEBUGGER_LAUNCHER_NAME runnable does not exists" }
        }
        return debuggerLauncherExists
    }

    override fun patchCommandLine(debugPort: Int, state: SamRunningState, commandLine: GeneralCommandLine) {
        val debugArgs = StringBuilder()
            .append("$REMOTE_DEBUGGER_DIR/$DEBUGGER_LAUNCHER_NAME.dll ")
            .append("$REMOTE_DEBUGGER_DIR/runtime.sh ")
            .append("$REMOTE_DEBUGGER_DIR/${debuggerAssemblyFile.name} ")
            .append("$DEBUGGER_MODE ")
            .append("$debugPort ")
            .append("$debugPort")
            .toString()

        commandLine.withParameters("--debugger-path")
                .withParameters(debuggerBinDirectory.path)
                .withParameters("--debug-args")
                .withParameters(debugArgs)

        super.patchCommandLine(debugPort, state, commandLine)
    }

    override fun createDebugProcess(
        environment: ExecutionEnvironment,
        state: SamRunningState,
        debugPort: Int
    ): XDebugProcessStarter? {
        throw UnsupportedOperationException("Use 'createDebugProcessAsync' instead")
    }

    override fun createDebugProcessAsync(
        environment: ExecutionEnvironment,
        state: SamRunningState,
        debugPort: Int
    ): Promise<XDebugProcessStarter?> {
        val promise = AsyncPromise<XDebugProcessStarter?>()
        val project = environment.project

        // Define a debugger lifetime to be able to dispose the debugger process and all nested component on termination
        val debuggerLifetimeDefinition = project.defineNestedLifetime()
        val debuggerLifetime = debuggerLifetimeDefinition.lifetime

        val scheduler = RdDispatcher(debuggerLifetime)
        val startInfo = createNetCoreStartInfo(state)

        val protocol = Protocol(
            serializers = Serializers(),
            identity = Identities(IdKind.Client),
            scheduler = scheduler,
            wire = SocketWire.Client(debuggerLifetime, scheduler, port = debugPort, optId = "FrontendToDebugWorker"),
            lifetime = debuggerLifetime
        )

        val executionResult = state.execute(environment.executor, environment.runner)

        protocol.wire.connected.adviseUntil(debuggerLifetime) connected@{ isConnected ->
            if (!isConnected) {
                return@connected false
            }

            try {
                val workerModel = RiderDebuggerWorkerModelManager.createDebuggerModel(debuggerLifetime, protocol)

                workerModel.initialized.adviseUntil(debuggerLifetime) initialized@{ isInitialized ->
                    if (!isInitialized) {
                        return@initialized false
                    }

                    // Fire backend to connect to debugger.
                    environment.project.solution.debuggerWorkerConnectionHelperModel.ports.put(
                        debuggerLifetime,
                        environment.executionId,
                        debugPort
                    )

                    val sessionModel = DotNetDebuggerSessionModel(startInfo)
                    sessionModel.sessionProperties.bindToSettings(debuggerLifetime).apply {
                        enableHeuristicPathResolve.set(true)
                    }

                    workerModel.activeSession.set(sessionModel)
                    val console = TextConsoleBuilderFactory.getInstance().createBuilder(environment.project).console
                    val processHandler = object : ProcessHandler() {
                        override fun detachProcessImpl() {
                            destroyProcessImpl()
                        }

                        override fun detachIsDefault(): Boolean = false
                        override fun getProcessInput(): OutputStream? = null

                        override fun destroyProcessImpl() {
                            val process =
                                (executionResult.processHandler as? BaseProcessHandler<*>)?.process
                            if (process == null) {
                                logger.error { "Unable to get process handler for SAM CLI invoke" }
                                return
                            }
                            process.destroy()
                            notifyProcessTerminated(0)
                        }

                        fun notifyProcessDestroyed(exitCode: Int) {
                            notifyProcessTerminated(exitCode)
                        }
                    }

                    processHandler.addProcessListener(object : ProcessAdapter() {
                        override fun processTerminated(event: ProcessEvent) {
                            logger.trace { "Process exited. Terminating debugger lifetime" }
                            debuggerLifetimeDefinition.terminate()
                        }
                    })

                    workerModel.targetExited.advise(debuggerLifetime) {
                        logger.trace { "Target exited" }
                        // We should try to kill deployment there because it's already stopped,
                        // just notify debugger session about termination via its process handler.
                        processHandler.notifyProcessDestroyed(it.exitCode ?: 0)
                    }

                    promise.setResult(
                        createAndStartSession(
                            executionConsole = console,
                            env = environment,
                            sessionLifetime = debuggerLifetime,
                            processHandler = processHandler,
                            protocol = protocol,
                            sessionModel = sessionModel,
                            outputEventsListener = object : IDebuggerOutputListener {})
                    )

                    return@initialized true
                }
            } catch (t: Throwable) {
                debuggerLifetimeDefinition.terminate()
                promise.setError(t)
            }
            return@connected true
        }

        val checkDebuggerTask = Timer("Debugger Worker launch timer", true).schedule(debuggerAttachTimeoutMs) {
            if (debuggerLifetimeDefinition.isAlive && !protocol.wire.connected.value) {
                debuggerLifetimeDefinition.terminate()
                promise.setError(message("lambda.debug.process.start.timeout"))
            }
        }

        debuggerLifetime.onTermination {
            checkDebuggerTask.cancel()
        }

        return promise
    }

    private fun createNetCoreStartInfo(state: SamRunningState): DotNetCoreExeStartInfo =
        DotNetCoreExeStartInfo(
            dotNetCoreInfo = DotNetCoreInfo(REMOTE_NETCORE_CLI_PATH, null),
            exePath = REMOTE_LAMBDA_COMPILED_PATH,
            workingDirectory = "",
            arguments = state.settings.handler,
            environmentVariables = emptyList(),
            runtimeArguments = null,
            executeAsIs = false,
            useExternalConsole = false,
            needToBeInitializedImmediately = true
        )

    private fun createAndStartSession(
        executionConsole: ExecutionConsole,
        env: ExecutionEnvironment,
        sessionLifetime: Lifetime,
        processHandler: ProcessHandler,
        protocol: IProtocol,
        sessionModel: DotNetDebuggerSessionModel,
        outputEventsListener: IDebuggerOutputListener
    ): XDebugProcessStarter {

        val fireInitializedManually = env.getUserData(DotNetDebugRunner.FIRE_INITIALIZED_MANUALLY) ?: false

        return object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess =
                // TODO: Update to use 'sessionId' parameter in ctr when min SDK version is 193.
                DotNetDebugProcess(
                    sessionLifetime = sessionLifetime,
                    session = session,
                    debuggerWorkerProcessHandler = processHandler,
                    console = executionConsole,
                    protocol = protocol,
                    sessionProxy = sessionModel,
                    fireInitializedManually = fireInitializedManually,
                    customListener = outputEventsListener,
                    debugKind = OptionsUtil.toDebugKind(sessionModel.sessionProperties.debugKind.valueOrNull),
                    project = env.project)
        }
    }
}
