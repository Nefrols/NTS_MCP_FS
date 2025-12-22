// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для инструмента получения информации о файле (FileInfoTool).
 * Проверяют корректность извлечения метаданных: размера, количества строк и контрольной суммы.
 */
class FileInfoToolTest {

    /**
     * Тестируемый инструмент.
     */
    private final FileInfoTool tool = new FileInfoTool();

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Тестирует успешное получение метаданных существующего файла.
     * Проверяет соответствие выводимого размера и количества строк реальным данным.
     */
    @Test
    void testExecute(@TempDir Path tempDir) throws Exception {
        // Изоляция окружения
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        String contentStr = "Hello World\nLine 2";
        Files.writeString(file, contentStr);
        long expectedSize = Files.size(file);

        JsonNode params = mapper.createObjectNode().put("path", file.toString());
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        // Верификация ключевых полей отчета
        assertTrue(text.contains("Size: " + expectedSize + " bytes"), "Отчет должен содержать верный размер");
        assertTrue(text.contains("Lines: 2"), "Отчет должен содержать верное количество строк");
        assertTrue(text.contains("CRC32:"), "Отчет должен содержать контрольную сумму");
    }

    /**
     * Тестирует поведение при запросе информации о несуществующем файле.
     * Ожидается выброс исключения IllegalArgumentException.
     */
    @Test
    void testFileNotFound() {
        JsonNode params = mapper.createObjectNode().put("path", "non_existent_file.txt");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(params), "Должна быть ошибка, если файл не найден");
    }
}