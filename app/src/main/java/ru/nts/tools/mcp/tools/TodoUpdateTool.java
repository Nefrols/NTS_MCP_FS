// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Инструмент для обновления текущего плана работ.
 * Поддерживает как полную перезапись, так и атомарное обновление пунктов по ID.
 */
public class TodoUpdateTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern TODO_ITEM_PATTERN = Pattern.compile("^\\s*([-*]|\\d+\\.)\\s+\\[([ xX])]\\s+(.*)$");

    @Override
    public String getName() { return "nts_todo_update"; }

    @Override
    public String getDescription() { return "Update the current planning document. Supports atomic item updates."; }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("content").put("type", "string").put("description", "New full content (if not using id).");
        props.putObject("id").put("type", "integer").put("description", "1-based index of the task to update.");
        props.putObject("status").put("type", "string").put("description", "New status: 'todo', 'done', 'failed'.");
        props.putObject("comment").put("type", "string").put("description", "Optional comment to append to the task.");
        props.putObject("fileName").put("type", "string").put("description", "Optional: Specific TODO file name.");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        Path todoDir = PathSanitizer.getRoot().resolve(".nts/todos");
        if (!Files.exists(todoDir)) throw new IllegalStateException("No TODOs found. Use nts_todo_create first.");

        Path targetFile;
        String fileName = params.path("fileName").asText(null);
        if (fileName != null) {
            targetFile = todoDir.resolve(fileName);
        } else {
            try (Stream<Path> s = Files.list(todoDir)) {
                targetFile = s.filter(p -> p.getFileName().toString().startsWith("TODO_"))
                        .max(Comparator.comparing(Path::getFileName))
                        .orElseThrow(() -> new IllegalStateException("No active TODO found."));
            }
        }

        String resultMsg;
        if (params.has("id")) {
            // Атомарное обновление
            resultMsg = updateItem(targetFile, params);
        } else if (params.has("content")) {
            // Полная перезапись
            Files.writeString(targetFile, params.get("content").asText());
            resultMsg = "Plan overwritten: " + targetFile.getFileName();
        } else {
            throw new IllegalArgumentException("Must provide 'content' or 'id' + 'status'.");
        }

        TransactionManager.setActiveTodo(targetFile.getFileName().toString());
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", resultMsg);
        return res;
    }

    private String updateItem(Path file, JsonNode params) throws IOException {
        int targetId = params.get("id").asInt();
        String status = params.path("status").asText("todo");
        String comment = params.path("comment").asText(null);

        List<String> lines = Files.readAllLines(file);
        List<String> newLines = new ArrayList<>();
        int currentId = 0;
        boolean found = false;

        String statusChar = switch (status.toLowerCase()) {
            case "done" -> "x";
            case "failed" -> "X";
            default -> " ";
        };

        for (String line : lines) {
            Matcher m = TODO_ITEM_PATTERN.matcher(line);
            if (m.find()) {
                currentId++;
                if (currentId == targetId) {
                    found = true;
                    String taskText = m.group(3);
                    if (comment != null) taskText += " (" + comment + ")";
                    // Пересобираем строку: отступ + пуля + [статус] + текст
                    String leading = line.substring(0, m.start(2) - 1); // Все до скобки
                    newLines.add(leading + "[" + statusChar + "] " + taskText);
                    continue;
                }
            }
            newLines.add(line);
        }

        if (!found) throw new IllegalArgumentException("Task with ID " + targetId + " not found in plan.");

        Files.writeString(file, String.join("\n", newLines));
        return String.format("Task #%d updated to '%s' in %s", targetId, status, file.getFileName());
    }
}