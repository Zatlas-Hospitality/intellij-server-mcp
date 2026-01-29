#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
  ListResourcesRequestSchema,
  ReadResourceRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";

import { IntelliJClient } from "./intellij-client.js";
import {
  ServerConfig,
  CompileResult,
  TestResult,
  DiagnosticsResult,
  RunStartResult,
  RunOutputResult,
  RunListResult,
  DebugSessionsResult,
  DebugStackResult,
  DebugVariablesResult,
  DebugEvaluateResult,
  DebugStepResult,
  BreakpointListResult,
  BreakpointSetResult,
  BreakpointRemoveResult,
} from "./types.js";

/**
 * MCP Server for IntelliJ IDEA integration
 *
 * This server connects to the MCP Bridge plugin running in IntelliJ
 * to provide compilation and test execution capabilities.
 */
class IntelliJMCPServer {
  private server: Server;
  private client: IntelliJClient;
  private config: ServerConfig;
  private lastCompileResult: CompileResult | null = null;
  private lastTestResult: TestResult | null = null;

  constructor() {
    this.config = this.loadConfig();
    this.client = new IntelliJClient(this.config);

    this.server = new Server(
      {
        name: "mcp-intellij",
        version: "1.0.0",
      },
      {
        capabilities: {
          tools: {},
          resources: {},
        },
      }
    );

    this.setupHandlers();
  }

  private loadConfig(): ServerConfig {
    return {
      intellijPort: parseInt(process.env.INTELLIJ_PORT || "10082"),
      intellijHost: process.env.INTELLIJ_HOST || "localhost",
    };
  }

  private setupHandlers(): void {
    // List available tools
    this.server.setRequestHandler(ListToolsRequestSchema, async () => {
      return {
        tools: [
          {
            name: "intellij_status",
            description:
              "Check connection status to IntelliJ IDEA. Use this first to verify the MCP Bridge plugin is running.",
            inputSchema: {
              type: "object",
              properties: {},
              required: [],
            },
          },
          {
            name: "intellij_compile",
            description: `Trigger compilation in IntelliJ IDEA using its built-in compiler.

Benefits:
- Leverages IntelliJ's incremental compilation (only recompiles changed files)
- Uses the same compiler configuration as your IDE (no ECJ/Lombok issues)
- Preserves Spring context cache for fast subsequent test runs

Usage:
- incremental=true (default): Only compile changed files
- incremental=false: Full project rebuild`,
            inputSchema: {
              type: "object",
              properties: {
                incremental: {
                  type: "boolean",
                  description:
                    "If true (default), only compile changed files. If false, rebuild the entire project.",
                  default: true,
                },
              },
              required: [],
            },
          },
          {
            name: "intellij_test",
            description: `Run tests in IntelliJ IDEA using its built-in test runner.

Benefits:
- Uses IntelliJ's JUnit runner (same as your IDE)
- Maintains Spring context cache (fast after first run)
- Consistent with your development environment

Pattern formats (use fully qualified class names for reliability):
- "com.example.MyTest" - Run all tests in class (RECOMMENDED)
- "com.example.*" - Run all tests in package
- "com.example.MyTest#testMethod" - Run specific test method
- "com.example.MyTest$NestedClass#test method" - Nested class with Kotlin display name

IMPORTANT: Simple class names (e.g., "MyTest") may not resolve correctly. Always use fully qualified class names.
For Kotlin tests with backtick method names, use: "FullClass$Nested Class Name#method name"`,
            inputSchema: {
              type: "object",
              properties: {
                pattern: {
                  type: "string",
                  description:
                    'Test pattern to run (e.g., "MyTest", "MyTest#testMethod", "com.example.*")',
                },
                timeout: {
                  type: "number",
                  description: "Timeout in seconds (default: 300)",
                  default: 300,
                },
                debug: {
                  type: "boolean",
                  description: "Run tests in debug mode with debugger attached (default: false)",
                  default: false,
                },
              },
              required: ["pattern"],
            },
          },
          {
            name: "intellij_errors",
            description:
              "Get current compilation errors and warnings from IntelliJ. Useful for checking the state of the project after editing files.",
            inputSchema: {
              type: "object",
              properties: {},
              required: [],
            },
          },
          {
            name: "intellij_compile_status",
            description:
              "Get the result of the last compilation. Returns detailed error information if compilation failed.",
            inputSchema: {
              type: "object",
              properties: {},
              required: [],
            },
          },
          {
            name: "intellij_test_results",
            description:
              "Get the detailed results of the last test run. Includes pass/fail counts and failure messages.",
            inputSchema: {
              type: "object",
              properties: {},
              required: [],
            },
          },
          {
            name: "intellij_run_start",
            description: `Start a run configuration in IntelliJ IDEA without waiting for completion.

Returns a runId that can be used to:
- Get output with intellij_run_output
- Stop the process with intellij_run_stop

Use intellij_run_list to see all active runs.`,
            inputSchema: {
              type: "object",
              properties: {
                configName: {
                  type: "string",
                  description: "Name of the run configuration to start",
                },
                projectPath: {
                  type: "string",
                  description: "Optional project path or name (if multiple projects are open)",
                },
                debug: {
                  type: "boolean",
                  description: "Start in debug mode with debugger attached (default: false)",
                  default: false,
                },
              },
              required: ["configName"],
            },
          },
          {
            name: "intellij_run_output",
            description: `Get the current output from a running process.

Returns the accumulated console output, running status, and exit code (if terminated).`,
            inputSchema: {
              type: "object",
              properties: {
                runId: {
                  type: "string",
                  description: "The run ID returned from intellij_run_start",
                },
                clear: {
                  type: "boolean",
                  description: "If true, clear the output buffer after reading (default: false)",
                  default: false,
                },
              },
              required: ["runId"],
            },
          },
          {
            name: "intellij_run_stop",
            description: "Stop a running process by its run ID.",
            inputSchema: {
              type: "object",
              properties: {
                runId: {
                  type: "string",
                  description: "The run ID to stop",
                },
              },
              required: ["runId"],
            },
          },
          {
            name: "intellij_run_list",
            description: "List all active and recent runs with their status.",
            inputSchema: {
              type: "object",
              properties: {},
              required: [],
            },
          },
          {
            name: "intellij_projects",
            description: "List all open projects in IntelliJ IDEA.",
            inputSchema: {
              type: "object",
              properties: {},
              required: [],
            },
          },
          {
            name: "intellij_plugin_reinstall",
            description: `Reinstall the MCP Bridge plugin in IntelliJ IDEA from a built zip file.

By default, uses the plugin at:
~/zatlas_projects/mcp-intellij-server/intellij-plugin/build/distributions/intellij-plugin-1.0.0.zip

After reinstall, IntelliJ must be restarted for changes to take effect.`,
            inputSchema: {
              type: "object",
              properties: {
                pluginPath: {
                  type: "string",
                  description: "Optional custom path to the plugin zip file",
                },
              },
              required: [],
            },
          },
          {
            name: "intellij_plugin_restart",
            description: "Restart IntelliJ IDEA to apply plugin changes.",
            inputSchema: {
              type: "object",
              properties: {},
              required: [],
            },
          },
          {
            name: "intellij_plugin_info",
            description: "Get information about the installed MCP Bridge plugin.",
            inputSchema: {
              type: "object",
              properties: {},
              required: [],
            },
          },
          // Debug tools
          {
            name: "intellij_debug_sessions",
            description: "List active debug sessions in IntelliJ IDEA.",
            inputSchema: {
              type: "object",
              properties: {},
              required: [],
            },
          },
          {
            name: "intellij_debug_pause",
            description: "Pause the running debug session.",
            inputSchema: {
              type: "object",
              properties: {},
              required: [],
            },
          },
          {
            name: "intellij_debug_resume",
            description: "Resume execution of a paused debug session.",
            inputSchema: {
              type: "object",
              properties: {},
              required: [],
            },
          },
          {
            name: "intellij_debug_step_over",
            description: "Step over the current line in the debugger.",
            inputSchema: {
              type: "object",
              properties: {},
              required: [],
            },
          },
          {
            name: "intellij_debug_step_into",
            description: "Step into the function call at the current line.",
            inputSchema: {
              type: "object",
              properties: {},
              required: [],
            },
          },
          {
            name: "intellij_debug_step_out",
            description: "Step out of the current function.",
            inputSchema: {
              type: "object",
              properties: {},
              required: [],
            },
          },
          {
            name: "intellij_debug_stack",
            description: "Get the current stack frames when the debugger is paused.",
            inputSchema: {
              type: "object",
              properties: {},
              required: [],
            },
          },
          {
            name: "intellij_debug_variables",
            description: "Get variables at the current or specified stack frame.",
            inputSchema: {
              type: "object",
              properties: {
                frameIndex: {
                  type: "number",
                  description: "Stack frame index (0 = top frame, default: 0)",
                  default: 0,
                },
              },
              required: [],
            },
          },
          {
            name: "intellij_debug_evaluate",
            description: "Evaluate an expression in the current debug context.",
            inputSchema: {
              type: "object",
              properties: {
                expression: {
                  type: "string",
                  description: "The expression to evaluate",
                },
              },
              required: ["expression"],
            },
          },
          {
            name: "intellij_breakpoint_list",
            description: "List all breakpoints in the project.",
            inputSchema: {
              type: "object",
              properties: {},
              required: [],
            },
          },
          {
            name: "intellij_breakpoint_set",
            description: "Set a breakpoint at the specified file and line.",
            inputSchema: {
              type: "object",
              properties: {
                file: {
                  type: "string",
                  description: "Absolute path to the file",
                },
                line: {
                  type: "number",
                  description: "Line number (1-based)",
                },
                condition: {
                  type: "string",
                  description: "Optional condition expression for the breakpoint",
                },
              },
              required: ["file", "line"],
            },
          },
          {
            name: "intellij_breakpoint_remove",
            description: "Remove a breakpoint at the specified file and line.",
            inputSchema: {
              type: "object",
              properties: {
                file: {
                  type: "string",
                  description: "Absolute path to the file",
                },
                line: {
                  type: "number",
                  description: "Line number (1-based)",
                },
              },
              required: ["file", "line"],
            },
          },
        ],
      };
    });

    // Handle tool calls
    this.server.setRequestHandler(CallToolRequestSchema, async (request) => {
      const { name, arguments: args } = request.params;

      try {
        switch (name) {
          case "intellij_status":
            return await this.handleStatus();

          case "intellij_compile":
            return await this.handleCompile(args?.incremental as boolean);

          case "intellij_test":
            return await this.handleTest(
              args?.pattern as string,
              args?.timeout as number,
              args?.debug as boolean
            );

          case "intellij_errors":
            return await this.handleErrors();

          case "intellij_compile_status":
            return await this.handleCompileStatus();

          case "intellij_test_results":
            return await this.handleTestResults();

          case "intellij_run_start":
            return await this.handleRunStart(
              args?.configName as string,
              args?.projectPath as string | undefined,
              args?.debug as boolean
            );

          case "intellij_run_output":
            return await this.handleRunOutput(
              args?.runId as string,
              args?.clear as boolean
            );

          case "intellij_run_stop":
            return await this.handleRunStop(args?.runId as string);

          case "intellij_run_list":
            return await this.handleRunList();

          case "intellij_projects":
            return await this.handleProjects();

          case "intellij_plugin_reinstall":
            return await this.handlePluginReinstall(args?.pluginPath as string | undefined);

          case "intellij_plugin_restart":
            return await this.handlePluginRestart();

          case "intellij_plugin_info":
            return await this.handlePluginInfo();

          // Debug tools
          case "intellij_debug_sessions":
            return await this.handleDebugSessions();

          case "intellij_debug_pause":
            return await this.handleDebugPause();

          case "intellij_debug_resume":
            return await this.handleDebugResume();

          case "intellij_debug_step_over":
            return await this.handleDebugStepOver();

          case "intellij_debug_step_into":
            return await this.handleDebugStepInto();

          case "intellij_debug_step_out":
            return await this.handleDebugStepOut();

          case "intellij_debug_stack":
            return await this.handleDebugStack();

          case "intellij_debug_variables":
            return await this.handleDebugVariables(args?.frameIndex as number);

          case "intellij_debug_evaluate":
            return await this.handleDebugEvaluate(args?.expression as string);

          case "intellij_breakpoint_list":
            return await this.handleBreakpointList();

          case "intellij_breakpoint_set":
            return await this.handleBreakpointSet(
              args?.file as string,
              args?.line as number,
              args?.condition as string | undefined
            );

          case "intellij_breakpoint_remove":
            return await this.handleBreakpointRemove(
              args?.file as string,
              args?.line as number
            );

          default:
            return {
              content: [{ type: "text", text: `Unknown tool: ${name}` }],
              isError: true,
            };
        }
      } catch (error) {
        const message =
          error instanceof Error ? error.message : "Unknown error";
        return {
          content: [{ type: "text", text: `Error: ${message}` }],
          isError: true,
        };
      }
    });

    // List resources
    this.server.setRequestHandler(ListResourcesRequestSchema, async () => {
      return {
        resources: [
          {
            uri: "intellij://compile/last",
            name: "Last Compilation Result",
            mimeType: "application/json",
          },
          {
            uri: "intellij://test/last",
            name: "Last Test Results",
            mimeType: "application/json",
          },
          {
            uri: "intellij://diagnostics",
            name: "Current Diagnostics",
            mimeType: "application/json",
          },
        ],
      };
    });

    // Read resources
    this.server.setRequestHandler(ReadResourceRequestSchema, async (request) => {
      const { uri } = request.params;

      try {
        switch (uri) {
          case "intellij://compile/last":
            return {
              contents: [
                {
                  uri,
                  mimeType: "application/json",
                  text: JSON.stringify(
                    this.lastCompileResult || { status: "no_compilation_yet" },
                    null,
                    2
                  ),
                },
              ],
            };

          case "intellij://test/last":
            return {
              contents: [
                {
                  uri,
                  mimeType: "application/json",
                  text: JSON.stringify(
                    this.lastTestResult || { status: "no_tests_run_yet" },
                    null,
                    2
                  ),
                },
              ],
            };

          case "intellij://diagnostics":
            const diagnostics = await this.client.getDiagnostics();
            return {
              contents: [
                {
                  uri,
                  mimeType: "application/json",
                  text: JSON.stringify(diagnostics, null, 2),
                },
              ],
            };

          default:
            throw new Error(`Unknown resource: ${uri}`);
        }
      } catch (error) {
        const message =
          error instanceof Error ? error.message : "Unknown error";
        throw new Error(`Failed to read resource: ${message}`);
      }
    });
  }

  private async handleStatus() {
    const status = await this.client.checkConnection();

    if (status.connected) {
      return {
        content: [
          {
            type: "text",
            text: `✓ Connected to IntelliJ IDEA at ${status.host}:${status.port}`,
          },
        ],
      };
    } else {
      return {
        content: [
          {
            type: "text",
            text: `✗ Not connected to IntelliJ IDEA at ${status.host}:${status.port}

Error: ${status.error}

Make sure:
1. IntelliJ IDEA is running
2. The MCP Bridge plugin is installed and enabled
3. The server is started (check Settings → Tools → MCP Bridge)
4. The port matches (currently trying port ${status.port})`,
          },
        ],
        isError: true,
      };
    }
  }

  private async handleCompile(incremental: boolean = true) {
    const result = await this.client.compile(incremental);
    this.lastCompileResult = result;

    if (result.success) {
      return {
        content: [
          {
            type: "text",
            text: `✓ Compilation successful (${result.timeMs}ms)

Warnings: ${result.warnings.length}
${result.warnings.length > 0 ? result.warnings.map((w) => `  - ${w.message}`).join("\n") : ""}`,
          },
        ],
      };
    } else {
      const errorList = result.errors
        .map((e) => {
          let location = "";
          if (e.file) {
            location = e.file;
            if (e.line) location += `:${e.line}`;
            if (e.column) location += `:${e.column}`;
            location = ` (${location})`;
          }
          return `  - ${e.message}${location}`;
        })
        .join("\n");

      return {
        content: [
          {
            type: "text",
            text: `✗ Compilation failed (${result.timeMs}ms)

Errors (${result.errors.length}):
${errorList}

Warnings: ${result.warnings.length}`,
          },
        ],
        isError: true,
      };
    }
  }

  private async handleTest(pattern: string, timeout: number = 300, debug: boolean = false) {
    if (!pattern) {
      return {
        content: [{ type: "text", text: "Error: pattern is required" }],
        isError: true,
      };
    }

    const result = await this.client.runTest(pattern, timeout, debug);
    this.lastTestResult = result;

    // Debug mode - return immediately with debug instructions
    if (result.debugMessage) {
      return {
        content: [{ type: "text", text: `🐛 ${result.debugMessage}

Use these tools to interact with the debugger:
- intellij_debug_sessions: Check if paused at breakpoint
- intellij_debug_variables: View variables
- intellij_debug_evaluate: Evaluate expressions
- intellij_debug_step_over/into/out: Step through code
- intellij_debug_resume: Continue execution` }],
      };
    }

    if (result.success) {
      let summary = `✓ Tests passed (${result.timeMs}ms)

Summary: ${result.passed} passed`;
      if (result.skipped > 0) {
        summary += `, ${result.skipped} skipped`;
      }

      return {
        content: [{ type: "text", text: summary }],
      };
    } else {
      let failureDetails = "";
      const failures = result.tests.filter(
        (t) => t.status === "FAILED" || t.status === "ERROR"
      );

      if (failures.length > 0) {
        failureDetails = "\n\nFailures:\n" + failures.map((f) => {
          let detail = `  ${f.className}#${f.methodName}`;
          if (f.message) detail += `\n    ${f.message}`;
          if (f.stackTrace) {
            // Show first few lines of stack trace
            const lines = f.stackTrace.split("\n").slice(0, 5);
            detail += `\n    ${lines.join("\n    ")}`;
          }
          return detail;
        }).join("\n\n");
      }

      return {
        content: [
          {
            type: "text",
            text: `✗ Tests failed (${result.timeMs}ms)

Summary: ${result.passed} passed, ${result.failed} failed, ${result.skipped} skipped${failureDetails}${result.error ? `\n\nError: ${result.error}` : ""}`,
          },
        ],
        isError: true,
      };
    }
  }

  private async handleErrors() {
    const diagnostics = await this.client.getDiagnostics();

    if (diagnostics.errors.length === 0 && diagnostics.warnings.length === 0) {
      return {
        content: [
          {
            type: "text",
            text: `✓ No compilation errors or warnings${diagnostics.projectName ? ` in project: ${diagnostics.projectName}` : ""}`,
          },
        ],
      };
    }

    let text = "";
    if (diagnostics.projectName) {
      text += `Project: ${diagnostics.projectName}\n\n`;
    }

    if (diagnostics.errors.length > 0) {
      text += `Errors (${diagnostics.errors.length}):\n`;
      text += diagnostics.errors
        .map((e) => {
          let location = "";
          if (e.file) {
            location = e.file;
            if (e.line) location += `:${e.line}`;
            location = ` (${location})`;
          }
          return `  - ${e.message}${location}`;
        })
        .join("\n");
      text += "\n\n";
    }

    if (diagnostics.warnings.length > 0) {
      text += `Warnings (${diagnostics.warnings.length}):\n`;
      text += diagnostics.warnings
        .map((w) => `  - ${w.message}`)
        .join("\n");
    }

    return {
      content: [{ type: "text", text: text.trim() }],
      isError: diagnostics.errors.length > 0,
    };
  }

  private async handleCompileStatus() {
    const result = await this.client.getCompileStatus();

    if ("status" in result && result.status === "no_compilation_yet") {
      return {
        content: [
          { type: "text", text: "No compilation has been run yet. Use intellij_compile to trigger a build." },
        ],
      };
    }

    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(result, null, 2),
        },
      ],
    };
  }

  private async handleTestResults() {
    const result = await this.client.getTestResults();

    if ("status" in result && result.status === "no_tests_run_yet") {
      return {
        content: [
          { type: "text", text: "No tests have been run yet. Use intellij_test to run tests." },
        ],
      };
    }

    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(result, null, 2),
        },
      ],
    };
  }

  private async handleRunStart(configName: string, projectPath?: string, debug: boolean = false) {
    if (!configName) {
      return {
        content: [{ type: "text", text: "Error: configName is required" }],
        isError: true,
      };
    }

    const result = await this.client.startRun(configName, projectPath, debug);

    if (result.success) {
      return {
        content: [
          {
            type: "text",
            text: `✓ Started run configuration "${result.configName}" in project "${result.projectName}"

Run ID: ${result.runId}

Use intellij_run_output with this runId to get the output.
Use intellij_run_stop to terminate the process.`,
          },
        ],
      };
    } else {
      return {
        content: [
          {
            type: "text",
            text: `✗ Failed to start run configuration: ${result.error}`,
          },
        ],
        isError: true,
      };
    }
  }

  private async handleRunOutput(runId: string, clear: boolean = false) {
    if (!runId) {
      return {
        content: [{ type: "text", text: "Error: runId is required" }],
        isError: true,
      };
    }

    const result = await this.client.getRunOutput(runId, clear);

    if (result.success) {
      const status = result.isRunning ? "🟢 Running" : `🔴 Terminated (exit code: ${result.exitCode})`;
      const output = result.output || "(no output yet)";

      return {
        content: [
          {
            type: "text",
            text: `Status: ${status}

Output:
${output}`,
          },
        ],
      };
    } else {
      return {
        content: [
          {
            type: "text",
            text: `✗ Failed to get output: ${result.error}`,
          },
        ],
        isError: true,
      };
    }
  }

  private async handleRunStop(runId: string) {
    if (!runId) {
      return {
        content: [{ type: "text", text: "Error: runId is required" }],
        isError: true,
      };
    }

    const result = await this.client.stopRun(runId);

    if (result.success) {
      return {
        content: [
          {
            type: "text",
            text: `✓ ${result.message || "Stop signal sent to run " + runId}`,
          },
        ],
      };
    } else {
      return {
        content: [
          {
            type: "text",
            text: `✗ Failed to stop run: ${result.error}`,
          },
        ],
        isError: true,
      };
    }
  }

  private async handleRunList() {
    const result = await this.client.listRuns();

    if (result.runs.length === 0) {
      return {
        content: [
          {
            type: "text",
            text: "No active runs. Use intellij_run_start to start a run configuration.",
          },
        ],
      };
    }

    const runList = result.runs
      .map((run) => {
        const status = run.isRunning ? "🟢 Running" : `🔴 Stopped (exit: ${run.exitCode})`;
        const duration = Math.round((Date.now() - run.startTime) / 1000);
        return `  ${run.runId}: ${run.configName} [${run.projectName}] - ${status} (${duration}s)`;
      })
      .join("\n");

    return {
      content: [
        {
          type: "text",
          text: `Active runs:\n${runList}`,
        },
      ],
    };
  }

  private async handleProjects() {
    const result = await this.client.listProjects();

    if (result.projects.length === 0) {
      return {
        content: [
          {
            type: "text",
            text: "No projects open in IntelliJ IDEA.",
          },
        ],
      };
    }

    const projectList = result.projects
      .map((p) => `  - ${p.name}${p.basePath ? ` (${p.basePath})` : ""}`)
      .join("\n");

    return {
      content: [
        {
          type: "text",
          text: `Open projects:\n${projectList}`,
        },
      ],
    };
  }

  private async handlePluginReinstall(pluginPath?: string) {
    const result = await this.client.reinstallPlugin(pluginPath);

    if (result.success) {
      return {
        content: [
          {
            type: "text",
            text: `✓ ${result.message}${result.requiresRestart ? "\n\nUse intellij_plugin_restart to restart the IDE." : ""}`,
          },
        ],
      };
    } else {
      return {
        content: [
          {
            type: "text",
            text: `✗ ${result.message}`,
          },
        ],
        isError: true,
      };
    }
  }

  private async handlePluginRestart() {
    const result = await this.client.restartIde();

    if (result.success) {
      return {
        content: [
          {
            type: "text",
            text: `✓ ${result.message}

Note: Connection will be lost during restart. Wait a few seconds and use intellij_status to verify reconnection.`,
          },
        ],
      };
    } else {
      return {
        content: [
          {
            type: "text",
            text: `✗ ${result.message}`,
          },
        ],
        isError: true,
      };
    }
  }

  private async handlePluginInfo() {
    const info = await this.client.getPluginInfo();

    return {
      content: [
        {
          type: "text",
          text: `Plugin Information:
  ID: ${info.pluginId}
  Name: ${info.name || "N/A"}
  Version: ${info.version || "N/A"}
  Installed: ${info.installed ? "Yes" : "No"}
  Enabled: ${info.enabled ? "Yes" : "No"}`,
        },
      ],
    };
  }

  // Debug handlers

  private async handleDebugSessions() {
    const result = await this.client.listDebugSessions();

    if (!result.success) {
      return {
        content: [{ type: "text", text: `✗ ${result.error}` }],
        isError: true,
      };
    }

    if (result.sessions.length === 0) {
      return {
        content: [
          {
            type: "text",
            text: "No active debug sessions. Start a test or run configuration with debug=true.",
          },
        ],
      };
    }

    const sessionList = result.sessions
      .map((s) => {
        const status = s.isSuspended ? "⏸ Paused" : "▶ Running";
        const location = s.currentFile && s.currentLine
          ? ` at ${s.currentFile}:${s.currentLine}`
          : "";
        return `  ${s.sessionName}: ${status}${location}`;
      })
      .join("\n");

    return {
      content: [{ type: "text", text: `Debug sessions:\n${sessionList}` }],
    };
  }

  private async handleDebugPause() {
    const result = await this.client.debugPause();
    return this.formatStepResult(result);
  }

  private async handleDebugResume() {
    const result = await this.client.debugResume();
    return this.formatStepResult(result);
  }

  private async handleDebugStepOver() {
    const result = await this.client.debugStepOver();
    return this.formatStepResult(result);
  }

  private async handleDebugStepInto() {
    const result = await this.client.debugStepInto();
    return this.formatStepResult(result);
  }

  private async handleDebugStepOut() {
    const result = await this.client.debugStepOut();
    return this.formatStepResult(result);
  }

  private formatStepResult(result: DebugStepResult) {
    if (result.success) {
      return {
        content: [{ type: "text", text: `✓ ${result.message || result.action}` }],
      };
    } else {
      return {
        content: [{ type: "text", text: `✗ ${result.error}` }],
        isError: true,
      };
    }
  }

  private async handleDebugStack() {
    const result = await this.client.getDebugStack();

    if (!result.success) {
      return {
        content: [{ type: "text", text: `✗ ${result.error}` }],
        isError: true,
      };
    }

    if (result.frames.length === 0) {
      return {
        content: [
          {
            type: "text",
            text: `Session: ${result.sessionName || "unknown"}\nNo stack frames available.`,
          },
        ],
      };
    }

    const frameList = result.frames
      .map((f) => {
        const marker = f.isTopFrame ? "→ " : "  ";
        const location = f.file && f.line ? `${f.file}:${f.line}` : "unknown";
        return `${marker}#${f.index} ${f.functionName || "unknown"} (${location})`;
      })
      .join("\n");

    return {
      content: [
        {
          type: "text",
          text: `Session: ${result.sessionName || "unknown"}\nStack frames:\n${frameList}`,
        },
      ],
    };
  }

  private async handleDebugVariables(frameIndex: number = 0) {
    const result = await this.client.getDebugVariables(frameIndex);

    if (!result.success) {
      return {
        content: [{ type: "text", text: `✗ ${result.error}` }],
        isError: true,
      };
    }

    if (result.variables.length === 0) {
      return {
        content: [
          {
            type: "text",
            text: `Session: ${result.sessionName || "unknown"}\nNo variables in frame ${result.frameIndex}.`,
          },
        ],
      };
    }

    const varList = result.variables
      .map((v) => {
        const type = v.type ? `: ${v.type}` : "";
        const children = v.hasChildren ? " {...}" : "";
        return `  ${v.name}${type} = ${v.value || "null"}${children}`;
      })
      .join("\n");

    return {
      content: [
        {
          type: "text",
          text: `Session: ${result.sessionName || "unknown"}\nVariables (frame ${result.frameIndex}):\n${varList}`,
        },
      ],
    };
  }

  private async handleDebugEvaluate(expression: string) {
    if (!expression) {
      return {
        content: [{ type: "text", text: "Error: expression is required" }],
        isError: true,
      };
    }

    const result = await this.client.debugEvaluate(expression);

    if (result.success) {
      const type = result.type ? ` (${result.type})` : "";
      const children = result.hasChildren ? " {...}" : "";
      return {
        content: [
          {
            type: "text",
            text: `${result.expression} = ${result.result || "null"}${type}${children}`,
          },
        ],
      };
    } else {
      return {
        content: [{ type: "text", text: `✗ ${result.error}` }],
        isError: true,
      };
    }
  }

  private async handleBreakpointList() {
    const result = await this.client.listBreakpoints();

    if (!result.success) {
      return {
        content: [{ type: "text", text: `✗ ${result.error}` }],
        isError: true,
      };
    }

    if (result.breakpoints.length === 0) {
      return {
        content: [
          {
            type: "text",
            text: "No breakpoints set. Use intellij_breakpoint_set to add breakpoints.",
          },
        ],
      };
    }

    const bpList = result.breakpoints
      .map((bp) => {
        const status = bp.enabled ? "●" : "○";
        const condition = bp.condition ? ` [if: ${bp.condition}]` : "";
        return `  ${status} ${bp.file}:${bp.line}${condition}`;
      })
      .join("\n");

    return {
      content: [
        {
          type: "text",
          text: `Breakpoints:\n${bpList}`,
        },
      ],
    };
  }

  private async handleBreakpointSet(file: string, line: number, condition?: string) {
    if (!file || !line) {
      return {
        content: [{ type: "text", text: "Error: file and line are required" }],
        isError: true,
      };
    }

    const result = await this.client.setBreakpoint(file, line, condition);

    if (result.success) {
      const conditionText = condition ? ` with condition: ${condition}` : "";
      return {
        content: [
          {
            type: "text",
            text: `✓ Breakpoint set at ${result.file}:${result.line}${conditionText}`,
          },
        ],
      };
    } else {
      return {
        content: [{ type: "text", text: `✗ ${result.error}` }],
        isError: true,
      };
    }
  }

  private async handleBreakpointRemove(file: string, line: number) {
    if (!file || !line) {
      return {
        content: [{ type: "text", text: "Error: file and line are required" }],
        isError: true,
      };
    }

    const result = await this.client.removeBreakpoint(file, line);

    if (result.success) {
      return {
        content: [{ type: "text", text: `✓ ${result.message}` }],
      };
    } else {
      return {
        content: [{ type: "text", text: `✗ ${result.error}` }],
        isError: true,
      };
    }
  }

  async run(): Promise<void> {
    const transport = new StdioServerTransport();
    await this.server.connect(transport);
    console.error("MCP IntelliJ server running on stdio");
  }
}

// Main entry point
const server = new IntelliJMCPServer();
server.run().catch(console.error);
