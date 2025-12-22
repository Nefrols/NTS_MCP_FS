// Aristo 23.12.2025
package ru.nts.tools.mcp.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.tools.ReadFileTool;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Интеграционный тест для проверки корректности обработки кириллицы.
 * Проверяет связку Reading -> Encoding Detection -> JSON Response.
 */
class CyrillicIntegrationTest {

    @TempDir
    Path sharedTempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReadFileTool readFileTool = new ReadFileTool();

    @org.junit.jupiter.api.BeforeEach
    void setup() {
        PathSanitizer.setRoot(sharedTempDir);
    }

    @Test
    void testReadWindows1251RussianText() throws Exception {
        // Создаем файл в Windows-1251 с русским текстом
        Path file = sharedTempDir.resolve("russian_win1251.txt");
        String russianText = "Прекрасный солнечный день в Москве.";
        Charset cp1251 = Charset.forName("windows-1251");
        Files.write(file, russianText.getBytes(cp1251));

        // Вызываем инструмент чтения
        var params = mapper.createObjectNode();
        params.put("path", file.toAbsolutePath().toString());
        
        JsonNode result = readFileTool.execute(params);
        
        // Извлекаем текст из ответа
        String responseText = result.get("content").get(0).get("text").asText();
        
        // Проверяем, что кириллица не превратилась в "кракозябры"
        assertTrue(responseText.contains(russianText), "Ответ должен содержать исходный русский текст без искажений. Получено: " + responseText);
        assertTrue(responseText.contains("ENCODING: windows-1251"), "Кодировка должна быть определена как windows-1251");
    }

    @Test
    void testReadUtf8RussianText() throws Exception {
        // Создаем файл в UTF-8 с русским текстом
        Path file = sharedTempDir.resolve("russian_utf8.txt");
        String russianText = "Привет из UTF-8! Как дела?";
        Files.writeString(file, russianText, StandardCharsets.UTF_8);

        // Вызываем инструмент чтения
        var params = mapper.createObjectNode();
        params.put("path", file.toAbsolutePath().toString());
        
        JsonNode result = readFileTool.execute(params);
        
        // Извлекаем текст из ответа
        String responseText = result.get("content").get(0).get("text").asText();
        
        // Проверяем корректность
        assertTrue(responseText.contains(russianText), "Ответ должен содержать исходный русский текст в UTF-8");
        assertTrue(responseText.contains("ENCODING: UTF-8"), "Кодировка должна быть определена как UTF-8");
    }
}
