// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.TransactionManager;

/**
 * Инструмент для выполнения операции повтора (REDO).
 * Позволяет вернуть изменения, которые были ранее отменены командой 'undo'.
 * Инструмент взаимодействует с глобальным стеком транзакций.
 */
public class RedoTool implements McpTool {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "redo";
    }

    @Override
    public String getDescription() {
        return "Redo last undone transaction. Returns updated journal.";
    }

    @Override
    public JsonNode getInputSchema() {
        // Инструмент не требует входных параметров
        return mapper.createObjectNode().put("type", "object");
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        // Выполнение команды REDO через менеджер транзакций
        String status = TransactionManager.redo();
        // Получение актуального состояния журнала после операции
        String journal = TransactionManager.getJournal();

        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", status + "\n\n" + journal);
        return result;
    }
}
