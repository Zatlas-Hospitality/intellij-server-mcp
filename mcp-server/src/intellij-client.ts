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
  async checkConnection(projectPath?: string): Promise<ConnectionStatus> {
    try {
      const queryParam = projectPath ? `?projectPath=${encodeURIComponent(projectPath)}` : "";
      const response = await this.get<HealthResponse>(`/health${queryParam}`);
      return {
        connected: response.status === "ok",
        host: this.config.intellijHost,
        port: this.config.intellijPort,
        targetProject: response.targetProject,
        projectFound: response.projectFound,
        openProjects: response.openProjects,
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
  async compile(incremental: boolean = true, projectPath?: string): Promise<CompileResult> {
    return this.post<CompileResult>("/compile", { incremental, projectPath });
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
  async runTest(pattern: string, timeout: number = 300, projectPath?: string): Promise<TestResult> {
    return this.post<TestResult>("/test", { pattern, timeout, projectPath });
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
  async startRun(configName: string, projectPath?: string): Promise<RunStartResult> {
    return this.post<RunStartResult>("/run/start", { configName, projectPath });
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
