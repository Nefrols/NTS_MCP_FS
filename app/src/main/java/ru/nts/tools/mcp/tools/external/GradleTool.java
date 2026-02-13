/*
 * Copyright 2025 Aristo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.nts.tools.mcp.tools.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.ProcessExecutor;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Инструмент для выполнения задач автоматизации сборки через Gradle.
 * Поддерживает wrapper, системный gradle fallback, инициализацию проектов.
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
            Gradle build automation — compile, test, package, and initialize projects.

            COMMON TASKS:
            - build      — Compile + test + assemble (requires gradlew)
            - test       — Run all tests (requires gradlew)
            - clean      — Remove build artifacts (requires gradlew)
            - check      — Run all verification tasks (requires gradlew)
            - assemble   — Build without tests (requires gradlew)
            - init       — Initialize new Gradle project (uses system gradle, gradlew NOT required)

            WRAPPER BEHAVIOR:
            - By default, uses gradlew/gradlew.bat from project root
            - If gradlew not found: suggests init, generateWrapper, or manual setup
            - generateWrapper=true: auto-generates wrapper from system gradle before running task
            - task='init': always uses system gradle (creates project from scratch)

            PROJECT INITIALIZATION:
            - task='init', initType='java-application' — New Java app with main class
            - task='init', initType='java-library' — New Java library
            - task='init', initDsl='kotlin' — Use Kotlin DSL (default)
            - Init automatically runs in non-interactive mode

            SMART FEATURES:
            - Auto-parses compilation errors with file:line format
            - Extracts test results summary (passed/failed/skipped)
            - Shows progress percentage for long builds
            - Truncates large outputs (keeps last 50 lines)

            ASYNC EXECUTION:
            If task exceeds timeout, returns taskId for monitoring.
            Use nts_task(action='log', taskId=X) to poll progress.

            EXAMPLES:
            - task='build', timeout=120
            - task='test', timeout=300, arguments='--info'
            - task='init', timeout=60, initType='java-application'
            - task='build', timeout=120, generateWrapper=true
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
                "Gradle task name: 'build', 'test', 'clean', 'check', 'assemble', 'init', etc. " +
                "Can also use custom tasks defined in build.gradle. Required.");

        props.putObject("arguments").put("type", "string").put("description",
                "Additional Gradle flags. Examples: " +
                "'--info' (verbose), '--stacktrace' (errors), '-x test' (skip tests), " +
                "'--tests MyTest' (specific test).");

        props.putObject("timeout").put("type", "integer").put("description",
                "Max execution time in SECONDS. Required. " +
                "Recommendations: clean=30, build=120, test=300. " +
                "If exceeded, task continues async - use nts_task to monitor.");

        props.putObject("generateWrapper").put("type", "boolean").put("description",
                "If true and gradlew is not found, automatically runs system 'gradle wrapper' " +
                "to generate the wrapper before executing the main task. " +
                "Requires system gradle to be installed and available in PATH. Default: false.");

        var initTypeNode = props.putObject("initType");
        initTypeNode.put("type", "string");
        initTypeNode.put("description",
                "Project type for 'gradle init'. Only used when task='init'. " +
                "Available types: java-application, java-library, kotlin-application, " +
                "kotlin-library, groovy-application, groovy-library, scala-library, " +
                "cpp-application, cpp-library, basic. Default: basic.");
        initTypeNode.putArray("enum")
                .add("java-application").add("java-library")
                .add("kotlin-application").add("kotlin-library")
                .add("groovy-application").add("groovy-library")
                .add("scala-library")
                .add("cpp-application").add("cpp-library")
                .add("basic");

        var initDslNode = props.putObject("initDsl");
        initDslNode.put("type", "string");
        initDslNode.put("description",
                "Build script DSL for 'gradle init'. Only used when task='init'. " +
                "Options: kotlin (recommended, default), groovy.");
        initDslNode.putArray("enum").add("kotlin").add("groovy");

        schema.putArray("required").add("task").add("timeout");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String task = params.get("task").asText();
        String extraArgs = params.path("arguments").asText("");
        long timeout = params.get("timeout").asLong();
        boolean generateWrapper = params.path("generateWrapper").asBoolean(false);
        String initType = params.path("initType").asText(null);
        String initDsl = params.path("initDsl").asText(null);

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        File wrapperFile = PathSanitizer.getRoot().resolve(isWindows ? "gradlew.bat" : "gradlew").toFile();

        // init всегда через системный gradle
        if ("init".equalsIgnoreCase(task)) {
            return handleInit(initType, initDsl, extraArgs, timeout, isWindows, wrapperFile);
        }

        if (!wrapperFile.exists()) {
            if (generateWrapper) {
                String genResult = generateGradleWrapper(isWindows);
                if (genResult != null) {
                    return createTextResponse("### Gradle Wrapper Generation Failed\n\n" + genResult +
                            "\n\nAlternatives:\n- task='init' to create a new Gradle project\n" +
                            "- Manually add gradlew to your project");
                }
                wrapperFile = PathSanitizer.getRoot().resolve(isWindows ? "gradlew.bat" : "gradlew").toFile();
                if (!wrapperFile.exists()) {
                    return createTextResponse("### Gradle Wrapper Generation Failed\n\n" +
                            "Wrapper files were not created. Check project directory permissions.");
                }
            } else {
                String systemGradleHint = findSystemGradle(isWindows) != null
                        ? " (system gradle detected in PATH)"
                        : " (system gradle NOT found in PATH)";
                return createTextResponse("### Gradle Wrapper Not Found\n\n" +
                        "No " + wrapperFile.getName() + " in project root" + systemGradleHint + ".\n\n" +
                        "Options:\n" +
                        "1. generateWrapper=true — auto-generate wrapper from system gradle\n" +
                        "2. task='init' — initialize a new Gradle project\n" +
                        "3. Manually add gradlew to your project");
            }
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
        return formatResult(result, "Gradle task");
    }

    private JsonNode handleInit(String initType, String initDsl, String extraArgs,
                                long timeout, boolean isWindows, File wrapperFile) throws Exception {
        // init всегда через системный gradle (wrapper может не существовать)
        String systemGradle = findSystemGradle(isWindows);

        // Если wrapper уже есть — используем его
        String gradleExe = wrapperFile.exists() ? wrapperFile.getAbsolutePath() : systemGradle;

        if (gradleExe == null) {
            return createTextResponse(
                    "### Gradle Init Failed\n\n" +
                    "Cannot run 'gradle init': neither gradlew nor system gradle found.\n" +
                    "Install Gradle (https://gradle.org/install/) and ensure it's in PATH.");
        }

        List<String> command = new ArrayList<>();
        command.add(gradleExe);
        command.add("init");
        command.add("--no-interactive");

        if (initType != null && !initType.isBlank()) {
            command.add("--type");
            command.add(initType);
        }
        if (initDsl != null && !initDsl.isBlank()) {
            command.add("--dsl");
            command.add(initDsl);
        }

        if (!extraArgs.isEmpty()) {
            for (String arg : extraArgs.split("\\s+")) {
                if (!arg.isEmpty()) command.add(arg);
            }
        }

        ProcessExecutor.ExecutionResult result = ProcessExecutor.execute(command, timeout);
        return formatResult(result, "Gradle init");
    }

    private String generateGradleWrapper(boolean isWindows) throws Exception {
        String systemGradle = findSystemGradle(isWindows);
        if (systemGradle == null) {
            return "System gradle not found in PATH. Install Gradle (https://gradle.org/install/).";
        }

        List<String> command = List.of(systemGradle, "wrapper");
        ProcessExecutor.ExecutionResult result = ProcessExecutor.execute(command, 60);

        if (result.exitCode() != 0) {
            return "gradle wrapper failed (exit code: " + result.exitCode() + "):\n" + result.output();
        }
        return null; // success
    }

    private String findSystemGradle(boolean isWindows) {
        String gradleName = isWindows ? "gradle.bat" : "gradle";
        try {
            ProcessBuilder pb = isWindows
                    ? new ProcessBuilder("where", gradleName)
                    : new ProcessBuilder("which", gradleName);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (reader.readLine() != null) { /* drain */ }
            }
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                return gradleName;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private JsonNode formatResult(ProcessExecutor.ExecutionResult result, String label) {
        StringBuilder sb = new StringBuilder();

        String progress = extractProgress(result.output());
        String progressMark = progress != null ? " [" + progress + "]" : "";

        if (result.isRunning()) {
            sb.append("### ").append(label).append(" [").append(result.taskId()).append("] [IN_PROGRESS]").append(progressMark).append("\n");
            sb.append("The task is still running in the background. Use nts_task_log with taskId to poll for more output later.\n");
        } else {
            sb.append("### ").append(label).append(" [").append(result.taskId()).append("] FINISHED (code: ").append(result.exitCode()).append(")\n");
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

        return createTextResponse(sb.toString());
    }

    private JsonNode createTextResponse(String text) {
        ObjectNode response = mapper.createObjectNode();
        var content = response.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", text);
        return response;
    }

    private String extractProgress(String output) {
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
                String file = mj.group(1);
                String lineNum = mj.group(2);
                String message = mj.group(3);
                summary.append(String.format("[ERROR] %s:%s - %s\n", file, lineNum, message));
                summary.append(String.format("  [FIX: nts_file_read(path='%s', line=%s) to see context]\n", file, lineNum));
                compErrors++;
                continue;
            }

            Matcher mt = detailedTestFailure.matcher(line);
            if (mt.find() && testFails < 10) {
                String testInfo = mt.group(1);
                summary.append(String.format("[FAIL] %s\n", testInfo));
                String testName = extractTestName(testInfo);
                summary.append(String.format("  [FIX: nts_file_search(action='grep', pattern='%s') to find test]\n", testName));
                testFails++;
            }
        }

        if (output.contains("BUILD FAILED")) {
            summary.append("[STATUS] BUILD FAILED\n");
            if (compErrors > 0) {
                summary.append("[ACTION: Fix compilation errors first. Read each file with nts_file_read, ")
                       .append("then fix with nts_edit_file. Re-run nts_verify(action='compile') after fixes.]\n");
            }
        } else if (output.contains("BUILD SUCCESSFUL")) {
            summary.append("[STATUS] BUILD SUCCESSFUL\n");
        }

        return summary.toString().trim();
    }

    private String extractTestName(String testInfo) {
        int gt = testInfo.indexOf('>');
        int failed = testInfo.indexOf("FAILED");
        if (gt >= 0 && failed > gt) {
            return testInfo.substring(gt + 1, failed).trim();
        }
        return testInfo;
    }
}
