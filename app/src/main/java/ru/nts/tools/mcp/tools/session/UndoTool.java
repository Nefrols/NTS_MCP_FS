// Aristo 22.12.2025
package ru.nts.tools.mcp.tools.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.TransactionManager;

/**
 * Инструмент для выполнения операции отмены (UNDO).
 * Позволяет откатить последние изменения, внесенные в файлы проекта, возвращая их
 * в состояние, зафиксированное перед выполнением последней транзакции.
 * Поддерживает многоуровневую историю (до 50 шагов).
 */
public class UndoTool implements McpTool {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_undo";
    }

    @Override
    public String getDescription() {
        return "Undo last transaction. Returns updated journal.";
    }

    @Override
    public String getCategory() {
        return "session";
    }

    @Override
    public JsonNode getInputSchema() {
        // Инструмент не требует параметров
        return mapper.createObjectNode().put("type", "object");
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        // Вызов логики отмены в менеджере транзакций
        String status = TransactionManager.undo();
        // Получение обновленного журнала для мгновенной обратной связи LLM
        String journal = TransactionManager.getJournal();

        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", status + "\n\n" + journal);
        return result;
    }
}
