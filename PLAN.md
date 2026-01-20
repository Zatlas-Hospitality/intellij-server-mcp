# MCP IntelliJ Server - Implementation Plan

## Overview

Build an MCP server that connects to IntelliJ IDEA to leverage its incremental compilation, test execution, and LSP features. This provides consistency with your development environment (no ECJ/Lombok issues) while maintaining fast feedback loops.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          Claude Code                                     │
└─────────────────────────────┬───────────────────────────────────────────┘
                              │ MCP Protocol (stdio)
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    MCP Server (Node.js)                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐│
│  │  index.ts          │  intellij-client.ts  │  test-parser.ts        ││
│  │  (MCP handlers)    │  (HTTP client)       │  (Result formatting)   ││
│  └─────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────┬───────────────────────────────────────────┘
                              │ HTTP (localhost:10082)
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                       IntelliJ IDEA                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐│
│  │              MCP Bridge Plugin (Kotlin)                             ││
│  │  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐           ││
│  │  │ HTTP Server   │  │ Compile       │  │ Test Runner   │           ││
│  │  │ (NanoHTTPD)   │  │ Service       │  │ Service       │           ││
│  │  └───────────────┘  └───────────────┘  └───────────────┘           ││
│  └─────────────────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────────────────┐│
│  │                    IntelliJ Platform APIs                           ││
│  │  CompilerManager  │  RunManager  │  ActionManager  │  ProjectManager││
│  └─────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────┘
```

## Project Structure

```
mcp-intellij-server/
├── intellij-plugin/                 # IntelliJ Plugin (Kotlin/Gradle)
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── src/main/
│   │   ├── kotlin/
│   │   │   └── com/zatlas/mcpbridge/
│   │   │       ├── MCPBridgePlugin.kt       # Plugin entry point
│   │   │       ├── HttpServer.kt            # NanoHTTPD server
│   │   │       ├── CompileService.kt        # Compilation endpoints
│   │   │       ├── TestRunnerService.kt     # Test execution
│   │   │       ├── DiagnosticsService.kt    # Errors/warnings
│   │   │       └── SettingsConfigurable.kt  # Plugin settings UI
│   │   └── resources/
│   │       └── META-INF/
│   │           └── plugin.xml
│   └── src/test/
├── mcp-server/                      # MCP Server (Node.js/TypeScript)
│   ├── package.json
│   ├── tsconfig.json
│   └── src/
│       ├── index.ts                 # MCP server entry
│       ├── intellij-client.ts       # HTTP client for plugin
│       ├── tools/
│       │   ├── compile.ts           # java_compile tool
│       │   ├── test.ts              # java_test tool
│       │   └── diagnostics.ts       # java_errors tool
│       └── types.ts
├── README.md
└── PLAN.md
```

---

## Phase 1: IntelliJ Plugin - HTTP Server & Compilation

### 1.1 Plugin Skeleton

**File: `intellij-plugin/build.gradle.kts`**

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.2"
}

group = "com.zatlas"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.code.gson:gson:2.10.1")
}

intellij {
    version.set("2024.1")
    type.set("IC")  // Community Edition
    plugins.set(listOf("java", "junit"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("243.*")
    }
}
```

**File: `intellij-plugin/src/main/resources/META-INF/plugin.xml`**

```xml
<idea-plugin>
    <id>com.zatlas.mcp-bridge</id>
    <name>MCP Bridge</name>
    <vendor>Zatlas</vendor>
    <description>Exposes IntelliJ compilation and test execution via HTTP for MCP integration</description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.java</depends>
    <depends>com.intellij.java</depends>
    <depends>JUnit</depends>

    <applicationListeners>
        <listener
            class="com.zatlas.mcpbridge.MCPBridgeStartupListener"
            topic="com.intellij.ide.AppLifecycleListener"/>
    </applicationListeners>

    <extensions defaultExtensionNs="com.intellij">
        <applicationConfigurable
            parentId="tools"
            instance="com.zatlas.mcpbridge.SettingsConfigurable"
            id="com.zatlas.mcpbridge.settings"
            displayName="MCP Bridge"/>
        <applicationService
            serviceImplementation="com.zatlas.mcpbridge.MCPBridgeSettings"/>
    </extensions>
</idea-plugin>
```

### 1.2 HTTP Server

**File: `intellij-plugin/src/main/kotlin/com/zatlas/mcpbridge/HttpServer.kt`**

```kotlin
package com.zatlas.mcpbridge

import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import com.intellij.openapi.diagnostic.Logger

class HttpServer(
    port: Int,
    private val compileService: CompileService,
    private val testRunnerService: TestRunnerService,
    private val diagnosticsService: DiagnosticsService
) : NanoHTTPD(port) {

    private val log = Logger.getInstance(HttpServer::class.java)
    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        log.info("MCP Bridge: $method $uri")

        return try {
            when {
                // Health check
                uri == "/health" -> jsonResponse(mapOf("status" to "ok"))

                // Compilation endpoints
                uri == "/compile" && method == Method.POST -> handleCompile(session)
                uri == "/compile/status" -> handleCompileStatus()

                // Test endpoints
                uri == "/test" && method == Method.POST -> handleRunTest(session)
                uri == "/test/results" -> handleTestResults()

                // Diagnostics
                uri == "/diagnostics" -> handleDiagnostics()
                uri == "/diagnostics/errors" -> handleErrors()

                // Project info
                uri == "/project" -> handleProjectInfo()

                else -> errorResponse(404, "Not found: $uri")
            }
        } catch (e: Exception) {
            log.error("Error handling request", e)
            errorResponse(500, e.message ?: "Internal error")
        }
    }

    private fun handleCompile(session: IHTTPSession): Response {
        val body = parseBody(session)
        val request = gson.fromJson(body, CompileRequest::class.java) ?: CompileRequest()
        val result = compileService.compile(request)
        return jsonResponse(result)
    }

    private fun handleCompileStatus(): Response {
        return jsonResponse(compileService.getStatus())
    }

    private fun handleRunTest(session: IHTTPSession): Response {
        val body = parseBody(session)
        val request = gson.fromJson(body, TestRequest::class.java)
        val result = testRunnerService.runTest(request)
        return jsonResponse(result)
    }

    private fun handleTestResults(): Response {
        return jsonResponse(testRunnerService.getLastResults())
    }

    private fun handleDiagnostics(): Response {
        return jsonResponse(diagnosticsService.getAllDiagnostics())
    }

    private fun handleErrors(): Response {
        return jsonResponse(diagnosticsService.getErrors())
    }

    private fun handleProjectInfo(): Response {
        return jsonResponse(compileService.getProjectInfo())
    }

    private fun parseBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: "{}"
    }

    private fun jsonResponse(data: Any): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            gson.toJson(data)
        )
    }

    private fun errorResponse(code: Int, message: String): Response {
        val status = when (code) {
            404 -> Response.Status.NOT_FOUND
            400 -> Response.Status.BAD_REQUEST
            else -> Response.Status.INTERNAL_ERROR
        }
        return newFixedLengthResponse(
            status,
            "application/json",
            gson.toJson(mapOf("error" to message))
        )
    }
}

// Request/Response DTOs
data class CompileRequest(
    val incremental: Boolean = true,
    val files: List<String>? = null,  // null = all changed files
    val includeTests: Boolean = true
)

data class CompileResult(
    val success: Boolean,
    val errors: List<CompileError>,
    val warnings: List<CompileError>,
    val compiledFiles: Int,
    val durationMs: Long
)

data class CompileError(
    val file: String,
    val line: Int,
    val column: Int,
    val message: String,
    val severity: String  // "ERROR" | "WARNING"
)

data class TestRequest(
    val pattern: String,           // Class name, method, or pattern
    val tags: List<String>? = null,
    val excludeTags: List<String>? = null,
    val timeout: Int = 60          // seconds
)

data class TestResult(
    val success: Boolean,
    val testsRun: Int,
    val testsPassed: Int,
    val testsFailed: Int,
    val testsSkipped: Int,
    val failures: List<TestFailure>,
    val durationMs: Long,
    val output: String
)

data class TestFailure(
    val className: String,
    val methodName: String,
    val message: String,
    val stackTrace: String
)
```

### 1.3 Compile Service

**File: `intellij-plugin/src/main/kotlin/com/zatlas/mcpbridge/CompileService.kt`**

```kotlin
package com.zatlas.mcpbridge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompileStatusNotification
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerMessage
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CompileService {

    private var lastCompileResult: CompileResult? = null

    fun compile(request: CompileRequest): CompileResult {
        val project = getActiveProject() ?: return CompileResult(
            success = false,
            errors = listOf(CompileError("", 0, 0, "No project open", "ERROR")),
            warnings = emptyList(),
            compiledFiles = 0,
            durationMs = 0
        )

        val startTime = System.currentTimeMillis()
        val future = CompletableFuture<CompileResult>()

        ApplicationManager.getApplication().invokeLater {
            val compilerManager = CompilerManager.getInstance(project)

            val callback = CompileStatusNotification { aborted, errors, warnings, context ->
                val result = buildResult(context, aborted, errors, warnings, startTime)
                lastCompileResult = result
                future.complete(result)
            }

            if (request.incremental) {
                // Incremental make - only changed files
                compilerManager.make(callback)
            } else {
                // Full rebuild
                compilerManager.rebuild(callback)
            }
        }

        return try {
            future.get(5, TimeUnit.MINUTES)
        } catch (e: Exception) {
            CompileResult(
                success = false,
                errors = listOf(CompileError("", 0, 0, "Compile timeout: ${e.message}", "ERROR")),
                warnings = emptyList(),
                compiledFiles = 0,
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun buildResult(
        context: CompileContext,
        aborted: Boolean,
        errorCount: Int,
        warningCount: Int,
        startTime: Long
    ): CompileResult {
        val errors = mutableListOf<CompileError>()
        val warnings = mutableListOf<CompileError>()

        context.getMessages(CompilerMessageCategory.ERROR).forEach { msg ->
            errors.add(msg.toCompileError("ERROR"))
        }
        context.getMessages(CompilerMessageCategory.WARNING).forEach { msg ->
            warnings.add(msg.toCompileError("WARNING"))
        }

        return CompileResult(
            success = !aborted && errorCount == 0,
            errors = errors,
            warnings = warnings,
            compiledFiles = context.getMessages(CompilerMessageCategory.STATISTICS).size,
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    private fun CompilerMessage.toCompileError(severity: String): CompileError {
        val vf = virtualFile
        return CompileError(
            file = vf?.path ?: "",
            line = if (vf != null) getLine(this) else 0,
            column = if (vf != null) getColumn(this) else 0,
            message = message,
            severity = severity
        )
    }

    private fun getLine(msg: CompilerMessage): Int {
        // Extract line from message if available
        return try {
            val navigatable = msg.navigatable
            // Use reflection or specific API to get line number
            1
        } catch (e: Exception) {
            1
        }
    }

    private fun getColumn(msg: CompilerMessage): Int = 1

    fun getStatus(): Map<String, Any> {
        val project = getActiveProject()
        return mapOf(
            "projectOpen" to (project != null),
            "projectName" to (project?.name ?: ""),
            "lastCompile" to (lastCompileResult ?: "none")
        )
    }

    fun getProjectInfo(): Map<String, Any> {
        val project = getActiveProject() ?: return mapOf("error" to "No project open")
        return mapOf(
            "name" to project.name,
            "basePath" to (project.basePath ?: ""),
            "isOpen" to project.isOpen
        )
    }

    private fun getActiveProject(): Project? {
        return ProjectManager.getInstance().openProjects.firstOrNull()
    }
}
```

---

## Phase 2: Test Runner Service

### 2.1 Test Runner Implementation

**File: `intellij-plugin/src/main/kotlin/com/zatlas/mcpbridge/TestRunnerService.kt`**

```kotlin
package com.zatlas.mcpbridge

import com.intellij.execution.*
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class TestRunnerService {

    private var lastResults: TestResult? = null
    private val outputBuffer = StringBuilder()

    fun runTest(request: TestRequest): TestResult {
        val project = getActiveProject() ?: return errorResult("No project open")

        val startTime = System.currentTimeMillis()
        val future = CompletableFuture<TestResult>()

        ApplicationManager.getApplication().invokeLater {
            try {
                val config = createJUnitConfiguration(project, request)
                executeTest(project, config, request, startTime, future)
            } catch (e: Exception) {
                future.complete(errorResult("Failed to create test config: ${e.message}"))
            }
        }

        return try {
            future.get(request.timeout.toLong(), TimeUnit.SECONDS)
        } catch (e: Exception) {
            errorResult("Test timeout after ${request.timeout}s: ${e.message}")
        }
    }

    private fun createJUnitConfiguration(project: Project, request: TestRequest): RunConfiguration {
        val runManager = RunManager.getInstance(project)
        val configurationType = JUnitConfigurationType.getInstance()
        val factory = configurationType.configurationFactories[0]

        val settings = runManager.createConfiguration("MCP Test Run", factory)
        val config = settings.configuration as JUnitConfiguration

        val data = config.persistentData

        // Parse pattern to determine test scope
        when {
            request.pattern.contains("#") -> {
                // Method pattern: com.example.MyTest#testMethod
                val parts = request.pattern.split("#")
                data.TEST_OBJECT = JUnitConfiguration.TEST_METHOD
                data.MAIN_CLASS_NAME = parts[0]
                data.METHOD_NAME = parts[1]
            }
            request.pattern.endsWith("*") -> {
                // Package pattern: com.example.*
                data.TEST_OBJECT = JUnitConfiguration.TEST_PACKAGE
                data.PACKAGE_NAME = request.pattern.removeSuffix("*").removeSuffix(".")
            }
            else -> {
                // Class pattern: com.example.MyTest or MyTest
                data.TEST_OBJECT = JUnitConfiguration.TEST_CLASS
                data.MAIN_CLASS_NAME = request.pattern
            }
        }

        // Add tag filters if specified
        request.tags?.let { tags ->
            // JUnit 5 tag expression
            config.persistentData.setTestSearchScope(JUnitConfiguration.TEST_SEARCH_SCOPE)
        }

        return config
    }

    private fun executeTest(
        project: Project,
        config: RunConfiguration,
        request: TestRequest,
        startTime: Long,
        future: CompletableFuture<TestResult>
    ) {
        outputBuffer.clear()
        val failures = mutableListOf<TestFailure>()
        var testsRun = 0
        var testsPassed = 0
        var testsFailed = 0
        var testsSkipped = 0

        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(executor, config).build()

        ProgramRunnerUtil.executeConfiguration(environment, true)

        // Listen for test events
        val testEventsListener = object : SMTRunnerEventsAdapter() {
            override fun onTestFinished(test: SMTestProxy) {
                testsRun++
                when {
                    test.isPassed -> testsPassed++
                    test.isDefect -> {
                        testsFailed++
                        failures.add(TestFailure(
                            className = test.parent?.name ?: "",
                            methodName = test.name,
                            message = test.errorMessage ?: "",
                            stackTrace = test.stacktrace ?: ""
                        ))
                    }
                    test.isIgnored -> testsSkipped++
                }
            }

            override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
                val result = TestResult(
                    success = testsFailed == 0,
                    testsRun = testsRun,
                    testsPassed = testsPassed,
                    testsFailed = testsFailed,
                    testsSkipped = testsSkipped,
                    failures = failures,
                    durationMs = System.currentTimeMillis() - startTime,
                    output = outputBuffer.toString()
                )
                lastResults = result
                future.complete(result)
            }
        }

        // Attach process listener for output capture
        environment.state?.execute(executor, environment.runner)?.let { handler ->
            handler.processHandler?.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    outputBuffer.append(event.text)
                }
            })
        }
    }

    fun getLastResults(): TestResult {
        return lastResults ?: errorResult("No tests have been run yet")
    }

    private fun errorResult(message: String): TestResult {
        return TestResult(
            success = false,
            testsRun = 0,
            testsPassed = 0,
            testsFailed = 0,
            testsSkipped = 0,
            failures = listOf(TestFailure("", "", message, "")),
            durationMs = 0,
            output = message
        )
    }

    private fun getActiveProject(): Project? {
        return ProjectManager.getInstance().openProjects.firstOrNull()
    }
}
```

---

## Phase 3: MCP Server (Node.js)

### 3.1 MCP Server Implementation

**File: `mcp-server/src/index.ts`**

```typescript
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { IntelliJClient } from "./intellij-client.js";

const INTELLIJ_PORT = parseInt(process.env.INTELLIJ_PORT || "10082");
const client = new IntelliJClient(`http://localhost:${INTELLIJ_PORT}`);

const server = new Server(
  { name: "mcp-intellij", version: "1.0.0" },
  { capabilities: { tools: {} } }
);

// List available tools
server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: "intellij_compile",
      description: "Trigger incremental compilation in IntelliJ. Uses IntelliJ's compiler for perfect Lombok/annotation processor compatibility.",
      inputSchema: {
        type: "object",
        properties: {
          incremental: {
            type: "boolean",
            description: "If true (default), only compile changed files. If false, full rebuild.",
            default: true
          }
        }
      }
    },
    {
      name: "intellij_test",
      description: "Run JUnit tests in IntelliJ. Leverages IntelliJ's test runner with cached Spring context.",
      inputSchema: {
        type: "object",
        properties: {
          pattern: {
            type: "string",
            description: "Test pattern: 'MyTest' (class), 'MyTest#testMethod' (method), 'com.example.*' (package)"
          },
          timeout: {
            type: "number",
            description: "Timeout in seconds (default: 60)",
            default: 60
          }
        },
        required: ["pattern"]
      }
    },
    {
      name: "intellij_errors",
      description: "Get current compilation errors and warnings from IntelliJ.",
      inputSchema: { type: "object", properties: {} }
    },
    {
      name: "intellij_status",
      description: "Check IntelliJ connection status and project info.",
      inputSchema: { type: "object", properties: {} }
    }
  ]
}));

// Handle tool calls
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  try {
    switch (name) {
      case "intellij_compile": {
        const result = await client.compile(args?.incremental ?? true);
        return formatCompileResult(result);
      }

      case "intellij_test": {
        const pattern = args?.pattern as string;
        const timeout = (args?.timeout as number) ?? 60;
        const result = await client.runTest(pattern, timeout);
        return formatTestResult(result);
      }

      case "intellij_errors": {
        const result = await client.getErrors();
        return formatErrors(result);
      }

      case "intellij_status": {
        const result = await client.getStatus();
        return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
      }

      default:
        throw new Error(`Unknown tool: ${name}`);
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return {
      content: [{ type: "text", text: `Error: ${message}` }],
      isError: true
    };
  }
});

function formatCompileResult(result: any) {
  let text = result.success
    ? `✓ Compilation successful (${result.durationMs}ms)\n`
    : `✗ Compilation failed (${result.durationMs}ms)\n`;

  if (result.errors.length > 0) {
    text += `\nErrors (${result.errors.length}):\n`;
    for (const err of result.errors) {
      text += `  ${err.file}:${err.line} - ${err.message}\n`;
    }
  }

  if (result.warnings.length > 0) {
    text += `\nWarnings (${result.warnings.length}):\n`;
    for (const warn of result.warnings.slice(0, 5)) {
      text += `  ${warn.file}:${warn.line} - ${warn.message}\n`;
    }
    if (result.warnings.length > 5) {
      text += `  ... and ${result.warnings.length - 5} more\n`;
    }
  }

  return { content: [{ type: "text", text }] };
}

function formatTestResult(result: any) {
  let text = result.success
    ? `✓ Tests passed (${result.durationMs}ms)\n`
    : `✗ Tests failed (${result.durationMs}ms)\n`;

  text += `\nResults: ${result.testsPassed}/${result.testsRun} passed`;
  if (result.testsSkipped > 0) text += `, ${result.testsSkipped} skipped`;
  text += "\n";

  if (result.failures.length > 0) {
    text += `\nFailures:\n`;
    for (const fail of result.failures) {
      text += `\n  ${fail.className}#${fail.methodName}\n`;
      text += `    ${fail.message}\n`;
      if (fail.stackTrace) {
        const lines = fail.stackTrace.split("\n").slice(0, 5);
        text += lines.map(l => `    ${l}`).join("\n") + "\n";
      }
    }
  }

  return { content: [{ type: "text", text }] };
}

function formatErrors(result: any) {
  if (!result.errors || result.errors.length === 0) {
    return { content: [{ type: "text", text: "No compilation errors" }] };
  }

  let text = `Compilation errors (${result.errors.length}):\n\n`;
  for (const err of result.errors) {
    text += `${err.file}:${err.line}:${err.column}\n  ${err.message}\n\n`;
  }

  return { content: [{ type: "text", text }] };
}

// Start server
const transport = new StdioServerTransport();
await server.connect(transport);
```

### 3.2 IntelliJ HTTP Client

**File: `mcp-server/src/intellij-client.ts`**

```typescript
import http from "http";

export class IntelliJClient {
  constructor(private baseUrl: string) {}

  async compile(incremental: boolean = true): Promise<any> {
    return this.post("/compile", { incremental });
  }

  async runTest(pattern: string, timeout: number = 60): Promise<any> {
    return this.post("/test", { pattern, timeout });
  }

  async getErrors(): Promise<any> {
    return this.get("/diagnostics/errors");
  }

  async getStatus(): Promise<any> {
    return this.get("/compile/status");
  }

  async healthCheck(): Promise<boolean> {
    try {
      await this.get("/health");
      return true;
    } catch {
      return false;
    }
  }

  private async get(path: string): Promise<any> {
    return this.request("GET", path);
  }

  private async post(path: string, body: any): Promise<any> {
    return this.request("POST", path, body);
  }

  private request(method: string, path: string, body?: any): Promise<any> {
    return new Promise((resolve, reject) => {
      const url = new URL(path, this.baseUrl);

      const options = {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname,
        method,
        headers: {
          "Content-Type": "application/json",
        },
        timeout: 300000, // 5 minutes
      };

      const req = http.request(options, (res) => {
        let data = "";
        res.on("data", (chunk) => (data += chunk));
        res.on("end", () => {
          try {
            resolve(JSON.parse(data));
          } catch {
            resolve(data);
          }
        });
      });

      req.on("error", reject);
      req.on("timeout", () => reject(new Error("Request timeout")));

      if (body) {
        req.write(JSON.stringify(body));
      }
      req.end();
    });
  }
}
```

---

## Phase 4: Integration & Testing

### 4.1 Claude Code Configuration

**File: `.mcp.json` (in your project)**

```json
{
  "mcpServers": {
    "intellij": {
      "command": "node",
      "args": ["/path/to/mcp-intellij-server/mcp-server/dist/index.js"],
      "env": {
        "INTELLIJ_PORT": "10082"
      }
    }
  }
}
```

### 4.2 Plugin Installation

1. Build plugin: `cd intellij-plugin && ./gradlew buildPlugin`
2. Install: IntelliJ → Settings → Plugins → ⚙️ → Install from Disk
3. Select `intellij-plugin/build/distributions/mcp-bridge-1.0.0.zip`
4. Restart IntelliJ

### 4.3 Usage Flow

```
1. Open project in IntelliJ (plugin starts HTTP server on port 10082)
2. Start Claude Code in same project directory
3. Claude uses intellij_compile / intellij_test tools
4. Results returned via MCP protocol
```

---

## Implementation Timeline

| Phase | Description | Effort |
|-------|-------------|--------|
| 1.1 | Plugin skeleton + Gradle setup | 0.5 day |
| 1.2 | HTTP server (NanoHTTPD) | 0.5 day |
| 1.3 | Compile service | 1 day |
| 2.1 | Test runner service | 2-3 days |
| 3.1 | MCP server (Node.js) | 1 day |
| 3.2 | IntelliJ client | 0.5 day |
| 4.x | Integration & testing | 1-2 days |

**Total: ~7-9 days**

---

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| IntelliJ API changes between versions | Pin to specific IntelliJ version, test upgrades |
| Test execution complexity | Start with class-level tests, add method patterns later |
| Spring context not caching | Verify context reuse in same IntelliJ session |
| Plugin crashes IntelliJ | Run HTTP server in separate thread with error isolation |
| Port conflicts | Make port configurable via plugin settings |

---

## Future Enhancements

1. **LSP features** - Code navigation, find references via plugin
2. **Hot reload** - Integrate with IntelliJ's hot swap
3. **Multi-project** - Support multiple open projects
4. **Gradle tasks** - Expose Gradle task execution
5. **Debug support** - Remote debug attachment

---

## Comparison with mcp-java-fast-test

| Feature | mcp-java-fast-test | mcp-intellij-server |
|---------|-------------------|---------------------|
| Compiler | ECJ (Eclipse) | IntelliJ (javac) |
| Lombok | ❌ Compatibility issues | ✅ Works |
| Spring context cache | ✅ Nailgun warm JVM | ✅ IntelliJ keeps JVM warm |
| IDE dependency | None | IntelliJ must be running |
| Setup complexity | Medium | Higher |
| Consistency | Drift risk | Same as dev environment |
