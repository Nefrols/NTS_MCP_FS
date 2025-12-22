// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

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
 * Инструмент для запуска задач Gradle через wrapper.
 * Особенности:
 * - Автоматически находит gradlew / gradlew.bat.
 * - Выполняет задачи в корне проекта.
 * - Содержит Smart Error Parser для выделения критических ошибок из длинного лога.
 */
public class GradleTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "gradle_task";
    }

    @Override
    public String getDescription() {
        return "Executes a Gradle task (e.g., build, test). Automatically parses logs to highlight errors.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("task").put("type", "string").put("description", "Gradle task to execute (e.g., 'build').");
        props.putObject("arguments").put("type", "string").put("description", "Optional additional arguments.");
        
        schema.putArray("required").add("task");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String task = params.get("task").asText();
        String extraArgs = params.path("arguments").asText("");

        // Определяем правильное имя исполняемого файла для текущей ОС
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        
        File wrapperFile = PathSanitizer.getRoot().resolve(isWindows ? "gradlew.bat" : "gradlew").toFile();
        if (!wrapperFile.exists()) {
            throw new IllegalStateException("Gradle wrapper not found at " + wrapperFile.getAbsolutePath());
        }

        List<String> command = new ArrayList<>();
        command.add(wrapperFile.getAbsolutePath());
        command.add(task);
        
        if (!extraArgs.isEmpty()) {
            // Разбиваем аргументы, игнорируя пустые строки
            for (String arg : extraArgs.split("\\s+")) {
                if (!arg.isEmpty()) command.add(arg);
            }
        }

        ProcessExecutor.ExecutionResult result = ProcessExecutor.execute(command);

        ObjectNode response = mapper.createObjectNode();
        var content = response.putArray("content").addObject();
        content.put("type", "text");
        
        StringBuilder sb = new StringBuilder();
        sb.append("Gradle execution finished with exit code: ").append(result.exitCode()).append("\n\n");
        
        // Добавляем ERROR SUMMARY если выполнение завершилось со сбоем
        if (result.exitCode() != 0) {
            String summary = parseErrors(result.output());
            if (!summary.isEmpty()) {
                sb.append("=== ERROR SUMMARY ===\n").append(summary).append("\n");
            }
        }

        sb.append("Full Output:\n").append(result.output());
        
        content.put("text", sb.toString());
        return response;
    }

    /**
     * Анализирует лог сборки и извлекает информацию об ошибках компиляции и падениях тестов.
     */
    private String parseErrors(String output) {
        StringBuilder summary = new StringBuilder();
        
        // Паттерн для Java компилятора: "path\File.java:line: error: message"
        Pattern javaError = Pattern.compile("([^\\s]+\\.java):(\\d+): error: (.*)");
        // Паттерн для падения тестов JUnit: "TestName > method() FAILED"
        Pattern testFailure = Pattern.compile("([^\\s]+ > [^\\s]+ FAILED)");

        String[] lines = output.split("\\n");
        for (String line : lines) {
            Matcher mj = javaError.matcher(line);
            if (mj.find()) {
                summary.append(String.format("[COMPILATION] %s (Line %s): %s\n", mj.group(1), mj.group(2), mj.group(3)));
                continue;
            }
            
            Matcher mt = testFailure.matcher(line);
            if (mt.find()) {
                summary.append(String.format("[TEST FAILED] %s\n", mt.group(1)));
            }
        }
        
        return summary.toString();
    }
}