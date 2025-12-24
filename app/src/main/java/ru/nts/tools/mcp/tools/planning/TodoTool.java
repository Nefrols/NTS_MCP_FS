// Aristo 24.12.2025
package ru.nts.tools.mcp.tools.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Инструмент для управления планами работ (TODO).
 * Поддерживает создание, просмотр статуса и обновление пунктов плана.
 */
public class TodoTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern TODO_ITEM_PATTERN = Pattern.compile("^\\s*([-*]|\\d+\\.)\\s+\\[([ xX])]\\s+(.*)$");

    @Override
    public String getName() { return "nts_todo"; }

    @Override
    public String getDescription() {
        return "Internal progress tracker. Keep your goals organized with 'create', 'status', and 'update'. Use 'update' with an 'id' to mark tasks as 'done' or 'failed'.";
    }

    @Override
    public String getCategory() {
        return "planning";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("action").put("type", "string").put("description", "Flow: 'create' (initiate plan), 'status' (read current), 'update' (modify content or task status).");
        props.putObject("title").put("type", "string").put("description", "Brief objective for the new plan.");
        props.putObject("content").put("type", "string").put("description", "Detailed Markdown steps or task description.");
        props.putObject("id").put("type", "integer").put("description", "1-based task index for atomic updates.");
        props.putObject("status").put("type", "string").put("description", "Target state: 'todo', 'done', 'failed'.");
        props.putObject("comment").put("type", "string").put("description", "Context for the task update (e.g., why it failed).");
        props.putObject("fileName").put("type", "string").put("description", "Target specific plan. Defaults to active.");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String action = params.get("action").asText().toLowerCase();

        return switch (action) {
            case "create" -> executeCreate(params);
            case "status" -> executeStatus(params);
            case "update" -> executeUpdate(params);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private JsonNode executeCreate(JsonNode params) throws IOException {
        String title = params.path("title").asText("New Plan");
        String content = params.path("content").asText("");

        Path todoDir = PathSanitizer.getRoot().resolve(".nts/todos");
        if (!Files.exists(todoDir)) Files.createDirectories(todoDir);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "TODO_" + timestamp + ".md";
        Path todoFile = todoDir.resolve(fileName);

        String fullContent = "# TODO: " + title + "\n\n" + content;
        Files.writeString(todoFile, fullContent);

        TransactionManager.setActiveTodo(fileName);

        return createResponse("Plan created: " + fileName + "\nThis plan is now set as ACTIVE.");
    }

    private JsonNode executeStatus(JsonNode params) throws IOException {
        Path todoDir = PathSanitizer.getRoot().resolve(".nts/todos");
        if (!Files.exists(todoDir)) return createResponse("No TODOs found.");

        Path targetFile;
        String fileName = params.path("fileName").asText(null);
        if (fileName != null) {
            targetFile = todoDir.resolve(fileName);
        } else {
            try (Stream<Path> s = Files.list(todoDir)) {
                targetFile = s.filter(p -> p.getFileName().toString().startsWith("TODO_"))
                        .max(Comparator.comparing(Path::getFileName))
                        .orElse(null);
            }
        }

        if (targetFile == null || !Files.exists(targetFile)) return createResponse("No active TODO found.");

        String content = Files.readString(targetFile);
        TransactionManager.setActiveTodo(targetFile.getFileName().toString());

        return createResponse("### Current Plan: " + targetFile.getFileName() + "\n\n" + content);
    }

    private JsonNode executeUpdate(JsonNode params) throws Exception {
        Path todoDir = PathSanitizer.getRoot().resolve(".nts/todos");
        if (!Files.exists(todoDir)) throw new IllegalStateException("No TODOs found. Use 'create' action first.");

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
            resultMsg = updateItem(targetFile, params);
        } else if (params.has("content")) {
            Files.writeString(targetFile, params.get("content").asText());
            resultMsg = "Plan overwritten: " + targetFile.getFileName();
        } else {
            throw new IllegalArgumentException("Must provide 'content' or 'id' + 'status' for update.");
        }

        TransactionManager.setActiveTodo(targetFile.getFileName().toString());
        return createResponse(resultMsg);
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
                    String leading = line.substring(0, m.start(2) - 1);
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

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }
}
