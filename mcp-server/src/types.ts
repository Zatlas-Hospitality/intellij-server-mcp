/**
 * Configuration for the IntelliJ MCP server
 */
export interface ServerConfig {
  intellijPort: number;
  intellijHost: string;
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
  debugMessage?: string;
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
 * Health check response
 */
export interface HealthResponse {
  status: string;
  version: string;
  plugin: string;
}

/**
 * IntelliJ connection status
 */
export interface ConnectionStatus {
  connected: boolean;
  host: string;
  port: number;
  error?: string;
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

// Debug types

/**
 * Debug session information
 */
export interface DebugSessionInfo {
  sessionId: string;
  sessionName: string;
  isSuspended: boolean;
  currentFile?: string;
  currentLine?: number;
  projectName: string;
}

/**
 * Result of listing debug sessions
 */
export interface DebugSessionsResult {
  success: boolean;
  sessions: DebugSessionInfo[];
  error?: string;
}

/**
 * Stack frame information
 */
export interface StackFrameInfo {
  index: number;
  functionName?: string;
  file?: string;
  line?: number;
  isTopFrame: boolean;
}

/**
 * Result of getting debug stack
 */
export interface DebugStackResult {
  success: boolean;
  sessionName?: string;
  isSuspended: boolean;
  frames: StackFrameInfo[];
  error?: string;
}

/**
 * Variable information
 */
export interface VariableInfo {
  name: string;
  value?: string;
  type?: string;
  hasChildren: boolean;
}

/**
 * Result of getting debug variables
 */
export interface DebugVariablesResult {
  success: boolean;
  sessionName?: string;
  frameIndex: number;
  variables: VariableInfo[];
  error?: string;
}

/**
 * Result of evaluating an expression
 */
export interface DebugEvaluateResult {
  success: boolean;
  expression: string;
  result?: string;
  type?: string;
  hasChildren: boolean;
  error?: string;
}

/**
 * Result of a debug step action
 */
export interface DebugStepResult {
  success: boolean;
  action: string;
  message?: string;
  error?: string;
}

/**
 * Breakpoint information
 */
export interface BreakpointInfo {
  id: string;
  file: string;
  line: number;
  enabled: boolean;
  condition?: string;
  logExpression?: string;
}

/**
 * Result of listing breakpoints
 */
export interface BreakpointListResult {
  success: boolean;
  breakpoints: BreakpointInfo[];
  error?: string;
}

/**
 * Result of setting a breakpoint
 */
export interface BreakpointSetResult {
  success: boolean;
  breakpointId?: string;
  file?: string;
  line?: number;
  message?: string;
  error?: string;
}

/**
 * Result of removing a breakpoint
 */
export interface BreakpointRemoveResult {
  success: boolean;
  message?: string;
  error?: string;
}
