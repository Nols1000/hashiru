package io.github.nols1000.release.common

import org.gradle.api.GradleException
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream

class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val succeeded get() = exitCode == 0
}

/**
 * Runs a command and captures its output. Pass secrets through [stdin] or [environment],
 * never as arguments, because arguments end up in error messages.
 */
fun ExecOperations.run(
    vararg command: String,
    stdin: String? = null,
    environment: Map<String, String> = emptyMap(),
    check: Boolean = true,
): CommandResult {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val exitCode = exec { spec ->
        spec.commandLine(*command)
        spec.environment(environment)
        spec.standardOutput = stdout
        spec.errorOutput = stderr
        spec.isIgnoreExitValue = true
        if (stdin != null) spec.standardInput = stdin.byteInputStream()
    }.exitValue
    val result = CommandResult(exitCode, stdout.toString().trim(), stderr.toString().trim())
    if (check && !result.succeeded) {
        throw GradleException("`${command.joinToString(" ")}` failed with exit code $exitCode:\n${result.stderr}")
    }
    return result
}
