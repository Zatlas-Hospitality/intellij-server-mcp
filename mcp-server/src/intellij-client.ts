import {
  CompileResult,
  TestResult,
  DiagnosticsResult,
  HealthResponse,
  ConnectionStatus,
  ServerConfig,
  RunStartResult,
  RunOutputResult,
  RunStopResult,
  RunListResult,
  ProjectListResult,
  PluginReinstallResult,
  PluginInfo,
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
 * HTTP client for communicating with the IntelliJ MCP Bridge plugin
 */
export class IntelliJClient {
  private baseUrl: string;
  private config: ServerConfig;

  constructor(config: ServerConfig) {
    this.config = config;
    this.baseUrl = `http://${config.intellijHost}:${config.intellijPort}`;
  }

  /**
   * Check connection to IntelliJ
   */
  async checkConnection(): Promise<ConnectionStatus> {
    try {
      const response = await this.get<HealthResponse>("/health");
      return {
        connected: response.status === "ok",
        host: this.config.intellijHost,
        port: this.config.intellijPort,
      };
    } catch (error) {
      return {
        connected: false,
        host: this.config.intellijHost,
        port: this.config.intellijPort,
        error: error instanceof Error ? error.message : "Unknown error",
      };
    }
  }

  /**
   * Trigger compilation in IntelliJ
   */
  async compile(incremental: boolean = true): Promise<CompileResult> {
    return this.post<CompileResult>("/compile", { incremental });
  }

  /**
   * Get the last compilation result
   */
  async getCompileStatus(): Promise<CompileResult | { status: string }> {
    return this.get<CompileResult | { status: string }>("/compile/status");
  }

  /**
   * Run tests matching the given pattern
   */
  async runTest(pattern: string, timeout: number = 300, debug: boolean = false): Promise<TestResult> {
    return this.post<TestResult>("/test", { pattern, timeout, debug });
  }

  /**
   * Get the last test results
   */
  async getTestResults(): Promise<TestResult | { status: string }> {
    return this.get<TestResult | { status: string }>("/test/results");
  }

  /**
   * Get current diagnostics (errors/warnings)
   */
  async getDiagnostics(): Promise<DiagnosticsResult> {
    return this.get<DiagnosticsResult>("/diagnostics");
  }

  /**
   * Health check
   */
  async health(): Promise<HealthResponse> {
    return this.get<HealthResponse>("/health");
  }

  /**
   * Start a run configuration
   */
  async startRun(configName: string, projectPath?: string, debug: boolean = false): Promise<RunStartResult> {
    return this.post<RunStartResult>("/run/start", { configName, projectPath, debug });
  }

  /**
   * Get output from a running process
   */
  async getRunOutput(runId: string, clear: boolean = false): Promise<RunOutputResult> {
    const queryParam = clear ? "?clear=true" : "";
    return this.get<RunOutputResult>(`/run/${runId}/output${queryParam}`);
  }

  /**
   * Stop a running process
   */
  async stopRun(runId: string): Promise<RunStopResult> {
    return this.post<RunStopResult>(`/run/${runId}/stop`, {});
  }

  /**
   * List all active runs
   */
  async listRuns(): Promise<RunListResult> {
    return this.get<RunListResult>("/run/list");
  }

  /**
   * List all open projects
   */
  async listProjects(): Promise<ProjectListResult> {
    return this.get<ProjectListResult>("/run/projects");
  }

  /**
   * Reinstall the MCP Bridge plugin from a zip file
   */
  async reinstallPlugin(pluginPath?: string): Promise<PluginReinstallResult> {
    return this.post<PluginReinstallResult>("/plugin/reinstall", { pluginPath });
  }

  /**
   * Restart IntelliJ IDE
   */
  async restartIde(): Promise<PluginReinstallResult> {
    return this.post<PluginReinstallResult>("/plugin/restart", {});
  }

  /**
   * Get plugin information
   */
  async getPluginInfo(): Promise<PluginInfo> {
    return this.get<PluginInfo>("/plugin/info");
  }

  // Debug methods

  /**
   * List active debug sessions
   */
  async listDebugSessions(): Promise<DebugSessionsResult> {
    return this.get<DebugSessionsResult>("/debug/sessions");
  }

  /**
   * Get current stack frames
   */
  async getDebugStack(): Promise<DebugStackResult> {
    return this.get<DebugStackResult>("/debug/stack");
  }

  /**
   * Get variables at current or specified frame
   */
  async getDebugVariables(frameIndex: number = 0): Promise<DebugVariablesResult> {
    return this.get<DebugVariablesResult>(`/debug/variables?frameIndex=${frameIndex}`);
  }

  /**
   * Evaluate an expression in the current context
   */
  async debugEvaluate(expression: string): Promise<DebugEvaluateResult> {
    return this.post<DebugEvaluateResult>("/debug/evaluate", { expression });
  }

  /**
   * Pause the running debug session
   */
  async debugPause(): Promise<DebugStepResult> {
    return this.post<DebugStepResult>("/debug/pause", {});
  }

  /**
   * Resume execution
   */
  async debugResume(): Promise<DebugStepResult> {
    return this.post<DebugStepResult>("/debug/resume", {});
  }

  /**
   * Step over current line
   */
  async debugStepOver(): Promise<DebugStepResult> {
    return this.post<DebugStepResult>("/debug/step/over", {});
  }

  /**
   * Step into function call
   */
  async debugStepInto(): Promise<DebugStepResult> {
    return this.post<DebugStepResult>("/debug/step/into", {});
  }

  /**
   * Step out of current function
   */
  async debugStepOut(): Promise<DebugStepResult> {
    return this.post<DebugStepResult>("/debug/step/out", {});
  }

  /**
   * List all breakpoints
   */
  async listBreakpoints(): Promise<BreakpointListResult> {
    return this.get<BreakpointListResult>("/breakpoint/list");
  }

  /**
   * Set a breakpoint at file:line
   */
  async setBreakpoint(file: string, line: number, condition?: string): Promise<BreakpointSetResult> {
    return this.post<BreakpointSetResult>("/breakpoint/set", { file, line, condition });
  }

  /**
   * Remove a breakpoint at file:line
   */
  async removeBreakpoint(file: string, line: number): Promise<BreakpointRemoveResult> {
    return this.post<BreakpointRemoveResult>("/breakpoint/remove", { file, line });
  }

  private async get<T>(path: string): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const response = await fetch(url, {
      method: "GET",
      headers: {
        Accept: "application/json",
      },
    });

    if (!response.ok) {
      const text = await response.text();
      throw new Error(`HTTP ${response.status}: ${text}`);
    }

    return response.json() as Promise<T>;
  }

  private async post<T>(path: string, body: object): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const response = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      const text = await response.text();
      throw new Error(`HTTP ${response.status}: ${text}`);
    }

    return response.json() as Promise<T>;
  }
}
