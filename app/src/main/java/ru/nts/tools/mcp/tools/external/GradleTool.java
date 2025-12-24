// Aristo 23.12.2025
package ru.nts.tools.mcp.tools.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.ProcessExecutor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Инструмент для выполнения задач автоматизации сборки через Gradle.
 */
public class GradleTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_gradle_task";
    }

    @Override
    public String getDescription() {
        return """
            Gradle build automation - compile, test, and package projects.

            COMMON TASKS:
            • build      - Compile + test + assemble
            • test       - Run all tests
            • clean      - Remove build artifacts
            • check      - Run all verification tasks
            • assemble   - Build without tests

            SMART FEATURES:
            • Auto-parses compilation errors with file:line format
            • Extracts test results summary (passed/failed/skipped)
            • Shows progress percentage for long builds
            • Truncates large outputs (keeps last 50 lines)

            ASYNC EXECUTION:
            If task exceeds timeout, returns taskId for monitoring.
            Use nts_task(action='log', taskId=X) to poll progress.

            EXAMPLE: task='test', timeout=120, arguments='--info'
            """;
    }

    @Override
    public String getCategory() {
        return "external";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("task").put("type", "string").put("description",
                "Gradle task name: 'build', 'test', 'clean', 'check', 'assemble', etc. " +
                "Can also use custom tasks defined in build.gradle. Required.");

        props.putObject("arguments").put("type", "string").put("description",
                "Additional Gradle flags. Examples: " +
                "'--info' (verbose), '--stacktrace' (errors), '-x test' (skip tests), " +
                "'--tests MyTest' (specific test).");

        props.putObject("timeout").put("type", "integer").put("description",
                "Max execution time in SECONDS. Required. " +
                "Recommendations: clean=30, build=120, test=300. " +
                "If exceeded, task continues async - use nts_task to monitor.");

        schema.putArray("required").add("task").add("timeout");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String task = params.get("task").asText();
        String extraArgs = params.path("arguments").asText("");
        long timeout = params.get("timeout").asLong();

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        File wrapperFile = PathSanitizer.getRoot().resolve(isWindows ? "gradlew.bat" : "gradlew").toFile();
        
        if (!wrapperFile.exists()) {
            throw new IllegalStateException(
                "Not a Gradle project. No " + wrapperFile.getName() + " found in project root. " +
                "This tool requires Gradle wrapper (gradlew/gradlew.bat) to be present.");
        }

        List<String> command = new ArrayList<>();
        command.add(wrapperFile.getAbsolutePath());
        command.add(task);

        if (!extraArgs.isEmpty()) {
            for (String arg : extraArgs.split("\\s+")) {
                if (!arg.isEmpty()) command.add(arg);
            }
        }

        ProcessExecutor.ExecutionResult result = ProcessExecutor.execute(command, timeout);

        ObjectNode response = mapper.createObjectNode();
        var content = response.putArray("content").addObject();
        content.put("type", "text");

        StringBuilder sb = new StringBuilder();
        
        String progress = extractProgress(result.output());
        String progressMark = progress != null ? " [" + progress + "]" : "";

        if (result.isRunning()) {
            sb.append("### Gradle task [").append(result.taskId()).append("] [IN_PROGRESS]").append(progressMark).append("\n");
            sb.append("The task is still running in the background. Use nts_task_log with taskId to poll for more output later.\n");
        } else {
            sb.append("### Gradle task [").append(result.taskId()).append("] FINISHED (code: ").append(result.exitCode()).append(")\n");
        }

        if (!result.isRunning()) {
            String smartSummary = parseSmartSummary(result.output());
            if (!smartSummary.isEmpty()) {
                sb.append("\n=== SMART SUMMARY ===\n").append(smartSummary).append("\n");
            }
        }

        String output = result.output();
        if (output.length() > 5000) {
            sb.append("\nOutput (truncated):\n...\n");
            String[] lines = output.split("\n");
            int start = Math.max(0, lines.length - 50);
            for (int i = start; i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
            }
        } else {
            sb.append("\nOutput:\n").append(output);
        }

        content.put("text", sb.toString());
        return response;
    }

    private String extractProgress(String output) {
        // Format: > :app:compileJava [45%]
        Pattern progressPattern = Pattern.compile("> .*\\[(\\d+%)\\]");
        String[] lines = output.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            Matcher m = progressPattern.matcher(lines[i]);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private String parseSmartSummary(String output) {
        StringBuilder summary = new StringBuilder();

        Pattern testSummary = Pattern.compile("(\\d+) tests completed, (\\d+) failed, (\\d+) skipped");
        Matcher ms = testSummary.matcher(output);
        if (ms.find()) {
            summary.append(String.format("[TESTS] Total: %s, Failed: %s, Skipped: %s\n", ms.group(1), ms.group(2), ms.group(3)));
        }

        Pattern javaError = Pattern.compile("([^\\s]+\\.java):(\\d+): error: (.*)");
        Pattern detailedTestFailure = Pattern.compile("([^\\s]+ > [^\\s]+ FAILED)");

        String[] lines = output.split("\\n");
        int compErrors = 0;
        int testFails = 0;

        for (String line : lines) {
            Matcher mj = javaError.matcher(line);
            if (mj.find() && compErrors < 10) {
                summary.append(String.format("[ERROR] %s:%s - %s\n", mj.group(1), mj.group(2), mj.group(3)));
                compErrors++;
                continue;
            }

            Matcher mt = detailedTestFailure.matcher(line);
            if (mt.find() && testFails < 10) {
                summary.append(String.format("[FAIL] %s\n", mt.group(1)));
                testFails++;
            }
        }

        if (output.contains("BUILD FAILED")) {
            summary.append("[STATUS] BUILD FAILED\n");
        } else if (output.contains("BUILD SUCCESSFUL")) {
            summary.append("[STATUS] BUILD SUCCESSFUL\n");
        }

        return summary.toString().trim();
    }
}
