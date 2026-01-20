package com.zatlas.mcpbridge.models

/**
 * Result of a compilation operation
 */
data class CompileResult(
    val success: Boolean,
    val errors: List<CompileError>,
    val warnings: List<CompileError>,
    val timeMs: Long,
    val aborted: Boolean = false
)

/**
 * A compilation error or warning
 */
data class CompileError(
    val message: String,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val severity: String = "ERROR"
)

/**
 * Compilation diagnostics including current errors/warnings
 */
data class DiagnosticsResult(
    val errors: List<CompileError>,
    val warnings: List<CompileError>,
    val projectName: String?
)

/**
 * Result of a test execution
 */
data class TestResult(
    val success: Boolean,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val timeMs: Long,
    val tests: List<TestCaseResult>,
    val error: String? = null
)

/**
 * Individual test case result
 */
data class TestCaseResult(
    val name: String,
    val className: String,
    val methodName: String,
    val status: TestStatus,
    val timeMs: Long,
    val message: String? = null,
    val stackTrace: String? = null
)

/**
 * Test status enum
 */
enum class TestStatus {
    PASSED,
    FAILED,
    SKIPPED,
    ERROR
}
