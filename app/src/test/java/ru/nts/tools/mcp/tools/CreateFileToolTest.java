// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для инструмента создания файлов (CreateFileTool).
 * Проверяют корректность создания объектов, автоматическое формирование листинга директории
 * и соблюдение политик безопасности.
 */
class CreateFileToolTest {

    /**
     * Тестируемый инструмент создания файлов.
     */
    private final CreateFileTool tool = new CreateFileTool();

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Тестирует создание нового файла и получение расширенной обратной связи.
     * Проверяет, что после создания файла в ответе присутствует актуальный список файлов директории.
     */
    @Test
    void testCreateFileWithListing(@TempDir Path tempDir) throws Exception {
        // Изоляция тестового окружения
        PathSanitizer.setRoot(tempDir);
        // Создаем существующий файл для проверки полноты листинга
        Files.createFile(tempDir.resolve("existing.txt"));

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "new.txt");
        params.put("content", "hello");

        // Выполнение инструмента
        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        // Верификация текстового отчета
        assertTrue(text.contains("File created successfully: new.txt"), "Отчет должен содержать подтверждение создания");
        assertTrue(text.contains("Directory content"), "Отчет должен содержать секцию листинга");
        assertTrue(text.contains("[FILE] existing.txt"), "Листинг должен содержать старый файл");
        assertTrue(text.contains("[FILE] new.txt"), "Листинг должен содержать только что созданный файл");
    }
}