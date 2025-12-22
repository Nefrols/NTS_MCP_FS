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

/**
 * Инструмент для запуска задач Gradle через wrapper.
 */
public class GradleTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "gradle_task";
    }

    @Override
    public String getDescription() {
        return "Executes a Gradle task (e.g., build, test, clean). Automatically uses gradlew wrapper.";
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
        String wrapperName = isWindows ? "gradlew.bat" : "./gradlew";
        
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
        sb.append("Output:\n").append(result.output());
        
        content.put("text", sb.toString());
        return response;
    }
}
