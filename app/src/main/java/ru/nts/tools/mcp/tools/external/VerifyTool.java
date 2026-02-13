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
import ru.nts.tools.mcp.core.TransactionManager;
import ru.nts.tools.mcp.core.treesitter.SyntaxChecker;
import ru.nts.tools.mcp.core.treesitter.SyntaxChecker.SyntaxCheckResult;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verification tool: syntax check (tree-sitter), compile, or test via Gradle.
 * Returns structured error output with actionable fix hints.
 */
public class VerifyTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_verify";
    }

    @Override
    public String getDescription() {
        return """
            Verify code correctness: syntax check, compilation, or tests.

            ACTIONS:
            - syntax  — Fast tree-sitter syntax check (no build needed)
            - compile — Run 'gradlew build -x test' for compilation check
            - test    — Run 'gradlew test' for full test verification

            WHEN TO USE:
            - After a series of edits, verify syntax before moving on
            - After major changes, verify compilation
            - Before finishing a task, run tests

            OUTPUT:
            Returns structured errors with file:line references and fix hints.
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

        var actionNode = props.putObject("action");
        actionNode.put("type", "string");
        actionNode.put("description",
                "Verification action: 'syntax' (fast tree-sitter), 'compile' (gradlew build -x test), 'test' (gradlew test).");
        actionNode.putArray("enum").add("syntax").add("compile").add("test");

        props.putObject("path").put("type", "string").put("description",
                "File path for syntax check. If omitted with action='syntax', checks all modified files in current transaction.");

        props.putObject("timeout").put("type", "integer").put("description",
                "Timeout in seconds for compile/test. Default: 120.");

        props.putObject("arguments").put("type", "string").put("description",
                "Additional Gradle arguments for compile/test actions.");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String action = params.get("action").asText();

        return switch (action) {
            case "syntax" -> executeSyntax(params);
            case "compile" -> executeGradle(params, "build", "-x test");
            case "test" -> executeGradle(params, "test", "");
            default -> createErrorResponse("Unknown action: " + action + ". Use 'syntax', 'compile', or 'test'.");
        };
    }

    private JsonNode executeSyntax(JsonNode params) {
        String pathStr = params.path("path").asText(null);

        if (pathStr != null && !pathStr.isBlank()) {
            // Single file syntax check
            Path path = PathSanitizer.sanitize(pathStr, false);
            SyntaxCheckResult result = SyntaxChecker.check(path);
            return formatSyntaxResult(List.of(new FileCheckResult(path, result)));
        }

        // Check all modified files in current transaction
        List<String> affectedPaths = TransactionManager.getAffectedPaths();
        if (affectedPaths.isEmpty()) {
            return createTextResponse("[VERIFY: syntax | STATUS: OK]\nNo modified files to check.");
        }

        List<FileCheckResult> results = new ArrayList<>();
        for (String affected : affectedPaths) {
            Path path = Path.of(affected);
            SyntaxCheckResult result = SyntaxChecker.check(path);
            results.add(new FileCheckResult(path, result));
        }

        return formatSyntaxResult(results);
    }

    private JsonNode formatSyntaxResult(List<FileCheckResult> results) {
        int totalErrors = results.stream().mapToInt(r -> r.result.errorCount()).sum();

        StringBuilder sb = new StringBuilder();
        sb.append("[VERIFY: syntax | STATUS: ").append(totalErrors == 0 ? "OK" : "FAILED").append("]");

        if (totalErrors == 0) {
            sb.append("\nAll ").append(results.size()).append(" file(s) passed syntax check.");
            // Reset verify counter
            TransactionManager.resetVerifyCounter();
            return createTextResponse(sb.toString());
        }

        sb.append("\n[ERRORS: ").append(totalErrors).append("]\n");
        int errorNum = 0;
        for (FileCheckResult fr : results) {
            if (!fr.result.hasErrors()) continue;
            for (var error : fr.result.errors()) {
                errorNum++;
                sb.append("\n").append(errorNum).append(". ")
                        .append(fr.path.getFileName()).append(":").append(error.line())
                        .append(" — ").append(error.message());
                if (!error.context().isEmpty()) {
                    sb.append("\n   Code: `").append(error.context()).append("`");
                }
                sb.append("\n   [FIX: nts_file_read(path='").append(fr.path)
                        .append("', line=").append(error.line()).append(") to see context]");
            }
        }

        long filesWithErrors = results.stream().filter(r -> r.result.hasErrors()).count();
        sb.append("\n\n[SUMMARY: ").append(filesWithErrors).append(" file(s) with errors. Fix and re-run nts_verify(action='syntax')]");

        return createTextResponse(sb.toString());
    }

    private JsonNode executeGradle(JsonNode params, String task, String defaultArgs) throws Exception {
        long timeout = params.path("timeout").asLong(120);
        String extraArgs = params.path("arguments").asText("");

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        File wrapperFile = PathSanitizer.getRoot().resolve(isWindows ? "gradlew.bat" : "gradlew").toFile();

        if (!wrapperFile.exists()) {
            return createErrorResponse("Gradle wrapper not found. Run nts_gradle_task(task='init') to initialize project.");
        }

        List<String> command = new ArrayList<>();
        command.add(wrapperFile.getAbsolutePath());
        command.add(task);
        if (!defaultArgs.isBlank()) {
            for (String arg : defaultArgs.split("\\s+")) {
                command.add(arg);
            }
        }
        if (!extraArgs.isBlank()) {
            for (String arg : extraArgs.split("\\s+")) {
                command.add(arg);
            }
        }
        command.add("--no-daemon");

        ProcessExecutor.ExecutionResult result = ProcessExecutor.execute(command, timeout);
        String output = result.output();

        // Parse structured errors
        StringBuilder sb = new StringBuilder();
        String status = (result.exitCode() == 0) ? "OK" : "FAILED";
        sb.append("[VERIFY: ").append(task).append(" | STATUS: ").append(status).append("]");

        if (result.exitCode() == 0) {
            sb.append("\nBuild successful.");
            TransactionManager.resetVerifyCounter();
        } else {
            // Parse compilation and test errors
            String structured = parseStructuredErrors(output);
            if (!structured.isEmpty()) {
                sb.append("\n").append(structured);
            } else {
                // Fallback: last 30 lines of output
                String[] lines = output.split("\n");
                int start = Math.max(0, lines.length - 30);
                sb.append("\n");
                for (int i = start; i < lines.length; i++) {
                    sb.append(lines[i]).append("\n");
                }
            }
        }

        return createTextResponse(sb.toString().trim());
    }

    private static final Pattern JAVA_ERROR = Pattern.compile(
            "(.+\\.java):(\\d+): error: (.+)");
    private static final Pattern TEST_FAILURE = Pattern.compile(
            "(.+) > (.+) FAILED");

    private String parseStructuredErrors(String output) {
        StringBuilder sb = new StringBuilder();
        String[] lines = output.split("\n");
        int compErrors = 0;
        int testFails = 0;

        for (String line : lines) {
            Matcher mj = JAVA_ERROR.matcher(line);
            if (mj.find() && compErrors < 10) {
                String file = mj.group(1);
                String lineNum = mj.group(2);
                String message = mj.group(3);
                compErrors++;
                sb.append("\n").append(compErrors).append(". ").append(file).append(":").append(lineNum)
                        .append(" — ").append(message);
                sb.append("\n   [FIX: nts_file_read(path='").append(file)
                        .append("', line=").append(lineNum).append(") to see context]");
                continue;
            }

            Matcher mt = TEST_FAILURE.matcher(line);
            if (mt.find() && testFails < 10) {
                testFails++;
                String className = mt.group(1);
                String methodName = mt.group(2);
                sb.append("\n").append(compErrors + testFails).append(". FAIL: ")
                        .append(className).append(" > ").append(methodName);
                sb.append("\n   [FIX: nts_file_search(action='grep', pattern='").append(methodName)
                        .append("') to find test]");
            }
        }

        if (compErrors + testFails > 0) {
            sb.append("\n\n[SUMMARY: ").append(compErrors).append(" compilation error(s), ")
                    .append(testFails).append(" test failure(s)]");
            if (compErrors > 0) {
                sb.append("\n[ACTION: Fix compilation errors first. Read each file, fix, then re-run nts_verify(action='compile')]");
            }
        }

        return sb.toString();
    }

    private JsonNode createTextResponse(String text) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", text);
        return res;
    }

    private JsonNode createErrorResponse(String message) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", "Error: " + message);
        res.put("isError", true);
        return res;
    }

    private record FileCheckResult(Path path, SyntaxCheckResult result) {}
}
