// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Инструмент для просмотра журнала транзакций.
 * Отображает историю операций и текущий контекст сессии (прочитанные файлы).
 */
public class TransactionJournalTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "transaction_journal";
    }

    @Override
    public String getDescription() {
        return "Returns a list of completed transactions and the current session context (read files).";
    }

    @Override
    public JsonNode getInputSchema() {
        return mapper.createObjectNode().put("type", "object");
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String journal = TransactionManager.getJournal();
        
        // Получаем список файлов, к которым есть доступ на запись
        Set<Path> readFiles = AccessTracker.getReadFiles();
        Path root = PathSanitizer.getRoot();
        
        String context = readFiles.isEmpty() 
            ? "  (no files read yet)\n" 
            : readFiles.stream()
                .map(p -> "  - " + root.relativize(p))
                .sorted()
                .collect(Collectors.joining("\n", "", "\n"));

        StringBuilder sb = new StringBuilder();
        sb.append(journal).append("\n");
        sb.append("=== ACTIVE SESSION CONTEXT ===\n");
        sb.append("Files unlocked for writing (AccessTracker):\n");
        sb.append(context);
        
        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", sb.toString());
        return result;
    }
}