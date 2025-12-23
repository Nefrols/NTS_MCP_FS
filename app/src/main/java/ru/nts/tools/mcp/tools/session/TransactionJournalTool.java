// Aristo 22.12.2025
package ru.nts.tools.mcp.tools.session;

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
 * Инструмент для мониторинга состояния текущей сессии и истории изменений.
 * Предоставляет LLM обзор:
 * 1. Журнала транзакций (UNDO/REDO стеки) с описанием операций и временными метками.
 * 2. Контекста доступа (AccessTracker): список файлов, которые были прочитаны и
 * в данный момент доступны для редактирования.
 */
public class TransactionJournalTool implements McpTool {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_transaction_journal";
    }

    @Override
    public String getDescription() {
        return "Shows transaction history and current session context (files unlocked for writing).";
    }

    @Override
    public String getCategory() {
        return "session";
    }

    @Override
    public JsonNode getInputSchema() {
        // Инструмент не принимает параметров
        return mapper.createObjectNode().put("type", "object");
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        // Получение текстового дампа истории из менеджера транзакций
        String journal = TransactionManager.getJournal();

        // Сбор информации о текущем "разблокированном" контексте сессии
        Set<Path> readFiles = AccessTracker.getReadFiles();
        Path root = PathSanitizer.getRoot();

        // Формирование списка прочитанных файлов (относительные пути для краткости)
        String context = readFiles.isEmpty() ? "  (no files read yet)\n" : readFiles.stream().map(p -> "  - " + root.relativize(p)).sorted().collect(Collectors.joining("\n", "", "\n"));

        // Объединение журнала и контекста в единый отчет
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
