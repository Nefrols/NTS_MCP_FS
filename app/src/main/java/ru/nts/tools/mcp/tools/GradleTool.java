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
 * Инструмент для выполнения задач автоматизации сборки через Gradle.
 * Особенности:
 * 1. Использование Wrapper: Автоматически находит и использует gradlew (Unix) или gradlew.bat (Windows) в корне проекта.
 * 2. Интеллектуальный парсинг (Smart Error Parser): В случае ошибки выполнения (например, сбой компиляции или тестов),
 * инструмент анализирует лог и выводит краткую сводку (ERROR SUMMARY) для быстрой навигации LLM.
 * 3. Безопасность: Выполняет задачи только в рамках текущего проекта.
 * 4. Управление задачами: Поддерживает контроль таймаутов и асинхронное накопление логов.
 */
public class GradleTool implements McpTool {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_gradle_task";
    }

    @Override
    public String getDescription() {
        return "Runs Gradle tasks (build, test, etc.). Auto-parses logs to highlight errors.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("task").put("type", "string").put("description", "Task name (e.g. 'build').");

        props.putObject("arguments").put("type", "string").put("description", "CLI arguments.");

        props.putObject("timeout").put("type", "integer").put("description", "Timeout in seconds (REQUIRED).");

        schema.putArray("required").add("task").add("timeout");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String task = params.get("task").asText();
        String extraArgs = params.path("arguments").asText("");
        long timeout = params.get("timeout").asLong();

        // Детекция операционной системы для выбора правильного скрипта враппера
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        // Поиск файла враппера относительно текущего корня "песочницы"
        File wrapperFile = PathSanitizer.getRoot().resolve(isWindows ? "gradlew.bat" : "gradlew").toFile();
        if (!wrapperFile.exists()) {
            throw new IllegalStateException("Gradle wrapper not found at " + wrapperFile.getAbsolutePath());
        }

        // Формирование команды для ProcessExecutor
        List<String> command = new ArrayList<>();
        command.add(wrapperFile.getAbsolutePath());
        command.add(task);

        if (!extraArgs.isEmpty()) {
            // Безопасное разделение аргументов по пробелам
            for (String arg : extraArgs.split("\\s+")) {
                if (!arg.isEmpty()) {
                    command.add(arg);
                }
            }
        }

        // Выполнение Gradle задачи с контролем времени и идентификацией задачи
        ProcessExecutor.ExecutionResult result = ProcessExecutor.execute(command, timeout);

        ObjectNode response = mapper.createObjectNode();
        var content = response.putArray("content").addObject();
        content.put("type", "text");

        StringBuilder sb = new StringBuilder();
        sb.append("Gradle task [").append(result.taskId()).append("] ");
        if (result.isRunning()) {
            sb.append("STILL RUNNING IN BACKGROUND\n");
        } else {
            sb.append("finished with exit code: ").append(result.exitCode()).append("\n");
        }

        // Если задача завершилась ошибкой (и не таймаутом), пытаемся выделить самое важное из логов
        if (result.exitCode() != 0 && !result.isRunning()) {
            String summary = parseErrors(result.output());
            if (!summary.isEmpty()) {
                sb.append("\n=== ERROR SUMMARY ===\n").append(summary).append("\n");
            }
        }

        sb.append("\nOutput:\n").append(result.output());

        content.put("text", sb.toString());
        return response;
    }

    /**
     * Выполняет анализ текста лога сборки для выделения критических ошибок.
     * Распознает ошибки компиляции Java и падения тестов JUnit.
     *
     * @param output Полный текстовый вывод Gradle.
     *
     * @return Краткая сводка ошибок или пустая строка, если паттерны не найдены.
     */
    private String parseErrors(String output) {
        StringBuilder summary = new StringBuilder();

        // Паттерн для ошибок Java компилятора: извлекает путь к файлу, строку и описание ошибки.
        Pattern javaError = Pattern.compile("([^\\s]+\\.java):(\\d+): error: (.*)");
        // Паттерн для отчет о падении тестов в консоли.
        Pattern testFailure = Pattern.compile("([^\\s]+ > [^\\s]+ FAILED)");

        String[] lines = output.split("\\n");
        for (String line : lines) {
            // Поиск ошибок компиляции
            Matcher mj = javaError.matcher(line);
            if (mj.find()) {
                summary.append(String.format("[COMPILATION] %s (Line %s): %s\n", mj.group(1), mj.group(2), mj.group(3)));
                continue;
            }

            // Поиск проваленных тестов
            Matcher mt = testFailure.matcher(line);
            if (mt.find()) {
                summary.append(String.format("[TEST FAILED] %s\n", mt.group(1)));
            }
        }

        return summary.toString();
    }
}