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

Pattern formats:
- "MyTest" - Run all tests in class MyTest
- "MyTest#testMethod" - Run specific test method
- "com.example.*" - Run all tests in package
- "com.example.MyTest" - Run tests in fully qualified class`,
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
              args?.timeout as number
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
              args?.projectPath as string | undefined
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

  private async handleTest(pattern: string, timeout: number = 300) {
    if (!pattern) {
      return {
        content: [{ type: "text", text: "Error: pattern is required" }],
        isError: true,
      };
    }

    const result = await this.client.runTest(pattern, timeout);
    this.lastTestResult = result;

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

  private async handleRunStart(configName: string, projectPath?: string) {
    if (!configName) {
      return {
        content: [{ type: "text", text: "Error: configName is required" }],
        isError: true,
      };
    }

    const result = await this.client.startRun(configName, projectPath);

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

  async run(): Promise<void> {
    const transport = new StdioServerTransport();
    await this.server.connect(transport);
    console.error("MCP IntelliJ server running on stdio");
  }
}

// Main entry point
const server = new IntelliJMCPServer();
server.run().catch(console.error);
