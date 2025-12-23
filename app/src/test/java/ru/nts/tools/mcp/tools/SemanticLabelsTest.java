// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для проверки семантических меток (Semantic Labels / Instructions).
 */
class SemanticLabelsTest {

    private final CreateFileTool createTool = new CreateFileTool();
    private final TransactionJournalTool journalTool = new TransactionJournalTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testInstructionInJournal(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();

        // 1. Создаем файл с инструкцией
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "labeled.txt");
        params.put("content", "hello");
        params.put("instruction", "Initial setup: created hello file");

        createTool.execute(params);

        // 2. Проверяем журнал
        JsonNode journalResult = journalTool.execute(mapper.createObjectNode());
        String journalText = journalResult.get("content").get(0).get("text").asText();

        assertTrue(journalText.contains("Initial setup: created hello file"), 
            "Журнал должен содержать семантическую метку транзакции. Journal: " + journalText);
    }

    @Test
    void testBatchInstruction(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        
        // Регистрируем инструменты в роутере для nts_batch_tools (если бы мы тестировали через сервер),
        // но здесь проверим прямое использование TransactionManager для лаконичности
        
        TransactionManager.startTransaction("Technical Action", "Feature: added complex logic");
        TransactionManager.backup(tempDir.resolve("file.txt"));
        Files.writeString(tempDir.resolve("file.txt"), "data");
        TransactionManager.commit();

        JsonNode journalResult = journalTool.execute(mapper.createObjectNode());
        String journalText = journalResult.get("content").get(0).get("text").asText();

        assertTrue(journalText.contains("Feature: added complex logic"), 
            "Журнал должен отображать инструкцию вместо технического описания или вместе с ним.");
    }
}