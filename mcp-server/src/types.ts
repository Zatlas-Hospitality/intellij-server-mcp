/**
 * Configuration for the IntelliJ MCP server
 */
export interface ServerConfig {
  intellijPort: number;
  intellijHost: string;
  intellijProject?: string;
}

/**
 * Result of a compilation operation
 */
export interface CompileResult {
  success: boolean;
  errors: CompileError[];
  warnings: CompileError[];
  timeMs: number;
  aborted: boolean;
}

/**
 * A compilation error or warning
 */
export interface CompileError {
  message: string;
  file?: string;
  line?: number;
  column?: number;
  severity: string;
}

/**
 * Compilation diagnostics
 */
export interface DiagnosticsResult {
  errors: CompileError[];
  warnings: CompileError[];
  projectName?: string;
}

/**
 * Result of a test execution
 */
export interface TestResult {
  success: boolean;
  passed: number;
  failed: number;
  skipped: number;
  timeMs: number;
  tests: TestCaseResult[];
  error?: string;
}

/**
 * Individual test case result
 */
export interface TestCaseResult {
  name: string;
  className: string;
  methodName: string;
  status: "PASSED" | "FAILED" | "SKIPPED" | "ERROR";
  timeMs: number;
  message?: string;
  stackTrace?: string;
}

/**
 * Project info
 */
export interface ProjectInfo {
  name: string;
  basePath?: string;
}

/**
 * Health check response
 */
export interface HealthResponse {
  status: string;
  version: string;
  plugin: string;
  openProjects?: ProjectInfo[];
  targetProject?: ProjectInfo;
  projectFound?: boolean;
}

/**
 * IntelliJ connection status
 */
export interface ConnectionStatus {
  connected: boolean;
  host: string;
  port: number;
  error?: string;
  targetProject?: ProjectInfo;
  projectFound?: boolean;
  openProjects?: ProjectInfo[];
}

/**
 * Result of starting a run configuration
 */
export interface RunStartResult {
  success: boolean;
  runId?: string;
  error?: string;
  configName?: string;
  projectName?: string;
}

/**
 * Result of getting run output
 */
export interface RunOutputResult {
  success: boolean;
  runId: string;
  output: string;
  isRunning: boolean;
  exitCode?: number;
  error?: string;
}

/**
 * Result of stopping a run
 */
export interface RunStopResult {
  success: boolean;
  runId: string;
  message?: string;
  error?: string;
}

/**
 * Active run information
 */
export interface ActiveRun {
  runId: string;
  configName: string;
  projectName: string;
  startTime: number;
  isRunning: boolean;
  exitCode?: number;
}

/**
 * Result of listing runs
 */
export interface RunListResult {
  runs: ActiveRun[];
}

/**
 * Open project information
 */
export interface OpenProjectInfo {
  name: string;
  basePath?: string;
}

/**
 * Result of listing projects
 */
export interface ProjectListResult {
  projects: OpenProjectInfo[];
}

/**
 * Result of plugin reinstall operation
 */
export interface PluginReinstallResult {
  success: boolean;
  message: string;
  requiresRestart?: boolean;
}

/**
 * Plugin information
 */
export interface PluginInfo {
  pluginId: string;
  installed: boolean;
  version?: string;
  name?: string;
  enabled: boolean;
}
