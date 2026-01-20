# MCP IntelliJ Server

An MCP (Model Context Protocol) server that connects to IntelliJ IDEA to leverage its incremental compilation and test execution capabilities. This provides consistency with your development environment (no ECJ/Lombok issues) while maintaining fast feedback loops.

## Architecture

```
Claude Code ──MCP(stdio)──► Node.js MCP Server ──HTTP──► IntelliJ Plugin
                                                              │
                                                    ┌─────────┴─────────┐
                                                    │   IntelliJ APIs   │
                                                    │ CompilerManager   │
                                                    │ RunManager        │
                                                    │ ActionManager     │
                                                    └───────────────────┘
```

## Components

### 1. IntelliJ Plugin (`intellij-plugin/`)

A Kotlin plugin that exposes HTTP endpoints for:
- **Compilation**: Leverages IntelliJ's CompilerManager for incremental builds
- **Test Execution**: Uses IntelliJ's JUnit runner with Spring context caching
- **Diagnostics**: Reports current errors and warnings

### 2. MCP Server (`mcp-server/`)

A TypeScript MCP server that Claude Code can use to interact with IntelliJ.

## Installation

### Building the IntelliJ Plugin

```bash
cd intellij-plugin
./gradlew buildPlugin
```

The plugin will be in `intellij-plugin/build/distributions/mcp-bridge-*.zip`.

### Installing the Plugin

1. Open IntelliJ IDEA
2. Go to **Settings → Plugins → ⚙️ → Install Plugin from Disk**
3. Select the ZIP file from `build/distributions/`
4. Restart IntelliJ IDEA

### Configuring the Plugin

1. Go to **Settings → Build, Execution, Deployment → MCP Bridge**
2. Configure the HTTP port (default: 10082)
3. Enable/disable auto-start on IDE startup

### Building the MCP Server

```bash
cd mcp-server
npm install
npm run build
```

### Configuring Claude Code

Add to your `.mcp.json` (or the global MCP settings):

```json
{
  "mcpServers": {
    "intellij": {
      "command": "node",
      "args": ["/path/to/mcp-intellij-server/mcp-server/dist/index.js"],
      "env": {
        "INTELLIJ_PORT": "10082",
        "INTELLIJ_HOST": "localhost"
      }
    }
  }
}
```

## Usage

### Available Tools

#### `intellij_status`
Check connection status to IntelliJ IDEA.

```
Use this first to verify the MCP Bridge plugin is running.
```

#### `intellij_compile`
Trigger compilation in IntelliJ IDEA.

```
Arguments:
- incremental (boolean, default: true): Only compile changed files
```

Example:
```
intellij_compile incremental=true
```

#### `intellij_test`
Run tests in IntelliJ IDEA.

```
Arguments:
- pattern (string, required): Test pattern to run
- timeout (number, default: 300): Timeout in seconds

Pattern formats:
- "MyTest" - Run all tests in class MyTest
- "MyTest#testMethod" - Run specific test method
- "com.example.*" - Run all tests in package
- "com.example.MyTest" - Run tests in fully qualified class
```

Example:
```
intellij_test pattern="UserServiceTest#testCreateUser"
```

#### `intellij_errors`
Get current compilation errors and warnings.

#### `intellij_compile_status`
Get the result of the last compilation.

#### `intellij_test_results`
Get the detailed results of the last test run.

#### `intellij_run_start`
Start a run configuration without waiting for completion.

```
Arguments:
- configName (string, required): Name of the run configuration to start
- projectPath (string, optional): Project path or name (if multiple projects open)

Returns a runId that can be used with intellij_run_output and intellij_run_stop.
```

Example:
```
intellij_run_start configName="Tunnel"
```

#### `intellij_run_output`
Get the current output from a running process.

```
Arguments:
- runId (string, required): The run ID from intellij_run_start
- clear (boolean, default: false): Clear output buffer after reading
```

Example:
```
intellij_run_output runId="run-1"
```

#### `intellij_run_stop`
Stop a running process by its run ID.

```
Arguments:
- runId (string, required): The run ID to stop
```

#### `intellij_run_list`
List all active and recent runs with their status.

#### `intellij_projects`
List all open projects in IntelliJ IDEA.

## HTTP API (Plugin)

The IntelliJ plugin exposes the following HTTP endpoints:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check |
| `/compile` | POST | Trigger compilation (`{"incremental": true}`) |
| `/compile/status` | GET | Last compile result |
| `/test` | POST | Run tests (`{"pattern": "...", "timeout": 300}`) |
| `/test/results` | GET | Last test results |
| `/diagnostics` | GET | Current errors/warnings |
| `/run/start` | POST | Start run config (`{"configName": "...", "projectPath": "..."}`) |
| `/run/list` | GET | List active runs |
| `/run/projects` | GET | List open projects |
| `/run/{runId}/output` | GET | Get run output (query: `?clear=true`) |
| `/run/{runId}/stop` | POST | Stop a running process |

## Benefits

1. **Consistency**: Uses the same compiler and test runner as your IDE
2. **Speed**: Incremental compilation only rebuilds changed files
3. **Spring Context Caching**: Test runs maintain Spring context across executions
4. **No Lombok Issues**: Uses IntelliJ's annotation processing, not ECJ
5. **Familiar Output**: Error messages match what you see in IntelliJ

## Troubleshooting

### Connection Failed

1. Verify IntelliJ IDEA is running
2. Check the MCP Bridge plugin is installed and enabled
3. Verify the server is started (Settings → Tools → MCP Bridge)
4. Ensure the port matches (default: 10082)

### Tests Not Found

1. Make sure the test class is compiled
2. Use fully qualified class names for ambiguous patterns
3. Check the module configuration in IntelliJ

### Slow First Test Run

This is expected. The first test run loads the Spring context (if applicable). Subsequent runs will be fast (~100ms) due to context caching.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `INTELLIJ_PORT` | 10082 | Port the IntelliJ plugin listens on |
| `INTELLIJ_HOST` | localhost | Host where IntelliJ is running |

## License

MIT
