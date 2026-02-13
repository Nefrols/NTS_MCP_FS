/*
 * Copyright 2025 Aristo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.nts.tools.mcp.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TaskContext;
import ru.nts.tools.mcp.tools.fs.FileSearchTool;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для инструмента поиска (FileSearchTool).
 */
class FileSearchToolTest {

    private final FileSearchTool tool = new FileSearchTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        TaskContext.setForceInMemoryDb(true);
        TaskContext ctx = TaskContext.getOrCreate("test-search-" + System.nanoTime());
        TaskContext.setCurrent(ctx);
    }

    // ==================== Базовые операции ====================

    @Test
    void testList() throws Exception {
        Files.createFile(tempDir.resolve("file1.txt"));
        Files.createDirectory(tempDir.resolve("subdir"));

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "list");
        params.put("path", ".");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("file1.txt"));
        assertTrue(text.contains("[DIR] subdir"));
    }

    @Test
    void testFind() throws Exception {
        Files.createDirectories(tempDir.resolve("a/b"));
        Files.createFile(tempDir.resolve("a/b/find_me.txt"));

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "find");
        params.put("path", ".");
        params.put("pattern", "find_me.txt");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("find_me.txt"));
    }

    @Test
    void testGrep() throws Exception {
        Files.writeString(tempDir.resolve("grep.txt"), "Hello World");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", ".");
        params.put("pattern", "Hello");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("grep.txt"));
        assertTrue(text.contains("Hello World"));
    }

    @Test
    void testStructure() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main"));

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "structure");
        params.put("path", ".");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("src"));
        assertTrue(text.contains("main"));
    }

    // ==================== Grep с maxResults ====================

    @Test
    void testGrepMaxResultsLimit() throws Exception {
        // Создаём 10 файлов с совпадениями
        for (int i = 1; i <= 10; i++) {
            Files.writeString(tempDir.resolve("file" + i + ".txt"), "SearchPattern in file " + i);
        }

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", ".");
        params.put("pattern", "SearchPattern");
        params.put("maxResults", 3);
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("Matches found in 3 files"));
        assertTrue(text.contains("limit reached"));
    }

    @Test
    void testGrepMaxResultsUnlimited() throws Exception {
        // Создаём 5 файлов
        for (int i = 1; i <= 5; i++) {
            Files.writeString(tempDir.resolve("unlim" + i + ".txt"), "MatchMe");
        }

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", ".");
        params.put("pattern", "MatchMe");
        params.put("maxResults", 0); // Без ограничения
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("Matches found in 5 files"));
        assertFalse(text.contains("limit reached"));
    }

    @Test
    void testGrepDefaultMaxResults() throws Exception {
        // По умолчанию maxResults = 100, создаём меньше файлов
        Files.writeString(tempDir.resolve("default.txt"), "DefaultTest");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", ".");
        params.put("pattern", "DefaultTest");
        // maxResults не указан, должен быть 100 по умолчанию
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("Matches found in 1 files"));
    }

    // ==================== Grep с токенами ====================

    @Test
    void testGrepReturnsTokens() throws Exception {
        Files.writeString(tempDir.resolve("tokens.txt"), "Line 1\nSearchHere\nLine 3");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", ".");
        params.put("pattern", "SearchHere");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("TOKEN: LAT:"));
        assertTrue(text.contains("Lines 2-2"));
    }

    @Test
    void testGrepMultipleLinesGroupedIntoRanges() throws Exception {
        Files.writeString(tempDir.resolve("ranges.txt"),
                "FINDME1\nFINDME2\nFINDME3\nother line\nFINDME4\nFINDME5");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", ".");
        params.put("pattern", "FINDME");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        // Должно быть 2 диапазона: 1-3 и 5-6
        assertTrue(text.contains("Lines 1-3"));
        assertTrue(text.contains("Lines 5-6"));
    }

    // ==================== Grep Regex ====================

    @Test
    void testGrepRegex() throws Exception {
        Files.writeString(tempDir.resolve("regex.txt"),
                "error: first\nwarning: second\nERROR: third");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", ".");
        params.put("pattern", "(?i)error");
        params.put("isRegex", true);
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("error: first"));
        assertTrue(text.contains("ERROR: third"));
    }

    // ==================== Grep No Match ====================

    @Test
    void testGrepNoMatch() throws Exception {
        Files.writeString(tempDir.resolve("nomatch.txt"), "Hello World");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", ".");
        params.put("pattern", "NotExisting");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("No matches found"));
        assertTrue(text.contains("Scanned"));
    }

    // ==================== Grep в поддиректориях ====================

    @Test
    void testGrepRecursive() throws Exception {
        Files.createDirectories(tempDir.resolve("sub1/sub2"));
        Files.writeString(tempDir.resolve("root.txt"), "FindMe");
        Files.writeString(tempDir.resolve("sub1/nested.txt"), "FindMe");
        Files.writeString(tempDir.resolve("sub1/sub2/deep.txt"), "FindMe");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", ".");
        params.put("pattern", "FindMe");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("Matches found in 3 files"));
    }

    // ==================== Grep в одиночном файле ====================

    @Test
    void testGrepSingleFile() throws Exception {
        Path testFile = tempDir.resolve("single.txt");
        Files.writeString(testFile, "First line\nSearchTarget\nThird line");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", testFile.toString());
        params.put("pattern", "SearchTarget");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("Matches found in 1 files"));
        assertTrue(text.contains("single.txt"));
        assertTrue(text.contains("SearchTarget"));
    }

    @Test
    void testGrepSingleFileNoMatch() throws Exception {
        Path testFile = tempDir.resolve("nomatch_single.txt");
        Files.writeString(testFile, "Hello World\nGoodbye");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", testFile.toString());
        params.put("pattern", "NotFound");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("No matches found"));
        assertTrue(text.contains("Scanned 1 files"));
    }

    @Test
    void testGrepSingleFileWithRegex() throws Exception {
        Path testFile = tempDir.resolve("regex_single.txt");
        Files.writeString(testFile, "error: message1\nWARNING: msg2\nError: message3");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", testFile.toString());
        params.put("pattern", "(?i)error");
        params.put("isRegex", true);
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("error: message1"));
        assertTrue(text.contains("Error: message3"));
        assertFalse(text.contains("WARNING"));
    }

    @Test
    void testGrepSingleFileWithContext() throws Exception {
        Path testFile = tempDir.resolve("context_single.txt");
        Files.writeString(testFile, "Line 1\nLine 2\nTARGET\nLine 4\nLine 5");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", testFile.toString());
        params.put("pattern", "TARGET");
        params.put("before", 1);
        params.put("after", 1);
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("Line 2"));  // контекст before
        assertTrue(text.contains("TARGET"));  // совпадение
        assertTrue(text.contains("Line 4"));  // контекст after
    }

    @Test
    void testGrepSingleFileNotFound() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", tempDir.resolve("nonexistent.txt").toString());
        params.put("pattern", "test");

        Exception exception = assertThrows(Exception.class, () -> tool.execute(params));
        assertTrue(exception.getMessage().contains("Search path not found"));
    }

    @Test
    void testGrepSingleFileMultipleMatches() throws Exception {
        Path testFile = tempDir.resolve("multi_match.txt");
        Files.writeString(testFile, "MATCH1\nother\nMATCH2\nmore text\nMATCH3");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", testFile.toString());
        params.put("pattern", "MATCH");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("MATCH1"));
        assertTrue(text.contains("MATCH2"));
        assertTrue(text.contains("MATCH3"));
        // Все совпадения в одном файле
        assertTrue(text.contains("Matches found in 1 files"));
    }

    // ==================== Find с glob ====================

    @Test
    void testFindGlobDoubleStarMatchesRoot() throws Exception {
        // Файл в корне
        Files.writeString(tempDir.resolve("RootFile.java"), "class RootFile {}");
        // Файл в подпапке
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/Nested.java"), "class Nested {}");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "find");
        params.put("path", ".");
        params.put("pattern", "**/*.java");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        // Должен найти ОБА файла
        assertTrue(text.contains("RootFile.java"), "Should find root file with **/*.java");
        assertTrue(text.contains("Nested.java"), "Should find nested file with **/*.java");
        assertTrue(text.contains("Found 2 matches"));
    }

    @Test
    void testFindSimpleGlobInRoot() throws Exception {
        Files.writeString(tempDir.resolve("Test.java"), "class Test {}");
        Files.writeString(tempDir.resolve("Other.txt"), "text");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "find");
        params.put("path", ".");
        params.put("pattern", "*.java");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("Test.java"));
        assertFalse(text.contains("Other.txt"));
    }

    // ==================== Кириллица ====================

    @Test
    void testGrepCyrillic() throws Exception {
        Files.writeString(tempDir.resolve("cyrillic.txt"), "Привет мир\nПоиск работает\nКонец");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", ".");
        params.put("pattern", "Поиск");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("cyrillic.txt"));
        assertTrue(text.contains("Поиск работает"));
    }
}
