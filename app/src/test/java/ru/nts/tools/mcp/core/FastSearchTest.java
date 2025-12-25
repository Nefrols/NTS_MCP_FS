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
package ru.nts.tools.mcp.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для FastSearch - высокопроизводительного поиска.
 */
class FastSearchTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
    }

    // ==================== Литеральный поиск ====================

    @Test
    void testLiteralSearchSimple() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello World\nFoo Bar\nHello Again");

        var result = FastSearch.search(file, "Hello", false, 0, 0, 0);

        assertNotNull(result);
        assertEquals(3, result.lineCount());
        assertEquals(2, result.matches().size());

        var matchLines = result.matches().stream()
                .filter(FastSearch.MatchedLine::isMatch)
                .map(FastSearch.MatchedLine::lineNumber)
                .toList();
        assertTrue(matchLines.contains(1));
        assertTrue(matchLines.contains(3));
    }

    @Test
    void testLiteralSearchBoyerMooreHorspool() throws Exception {
        // Паттерн >= 4 символов использует BMH алгоритм
        Path file = tempDir.resolve("bmh.txt");
        Files.writeString(file, """
                This is a test file
                Pattern matching test
                Another line here
                PatternMatch found
                End of file
                """);

        var result = FastSearch.search(file, "Pattern", false, 0, 0, 0);

        assertNotNull(result);
        assertEquals(2, result.matches().size());
        assertEquals(2, result.matches().get(0).lineNumber());
        assertEquals(4, result.matches().get(1).lineNumber());
    }

    @Test
    void testLiteralSearchShortPattern() throws Exception {
        // Паттерн < 4 символов использует indexOf
        Path file = tempDir.resolve("short.txt");
        Files.writeString(file, "abc\nab\nabc\nxyz");

        var result = FastSearch.search(file, "ab", false, 0, 0, 0);

        assertNotNull(result);
        assertEquals(3, result.matches().size());
    }

    @Test
    void testLiteralSearchNoMatch() throws Exception {
        Path file = tempDir.resolve("nomatch.txt");
        Files.writeString(file, "Hello World");

        var result = FastSearch.search(file, "NotFound", false, 0, 0, 0);

        assertNull(result);
    }

    // ==================== Regex поиск ====================

    @Test
    void testRegexSearch() throws Exception {
        Path file = tempDir.resolve("regex.txt");
        Files.writeString(file, """
                error: something wrong
                warning: minor issue
                ERROR: critical
                info: all good
                """);

        var result = FastSearch.search(file, "(?i)error", true, 0, 0, 0);

        assertNotNull(result);
        assertEquals(2, result.matches().size());
        assertEquals(1, result.matches().get(0).lineNumber());
        assertEquals(3, result.matches().get(1).lineNumber());
    }

    @Test
    void testRegexSearchWithGroups() throws Exception {
        Path file = tempDir.resolve("groups.txt");
        Files.writeString(file, """
                user: john
                user: jane
                admin: root
                """);

        var result = FastSearch.search(file, "user:\\s+\\w+", true, 0, 0, 0);

        assertNotNull(result);
        assertEquals(2, result.matches().size());
    }

    // ==================== maxResults ====================

    @Test
    void testMaxResultsLimit() throws Exception {
        Path file = tempDir.resolve("many.txt");
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            content.append("match line ").append(i).append("\n");
        }
        Files.writeString(file, content.toString());

        var result = FastSearch.search(file, "match", false, 5, 0, 0);

        assertNotNull(result);
        assertEquals(5, result.matches().size());
        // Первые 5 строк
        assertEquals(1, result.matches().get(0).lineNumber());
        assertEquals(5, result.matches().get(4).lineNumber());
    }

    @Test
    void testMaxResultsZeroMeansUnlimited() throws Exception {
        Path file = tempDir.resolve("unlimited.txt");
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            content.append("match ").append(i).append("\n");
        }
        Files.writeString(file, content.toString());

        var result = FastSearch.search(file, "match", false, 0, 0, 0);

        assertNotNull(result);
        assertEquals(50, result.matches().size());
    }

    // ==================== Контекст ====================

    @Test
    void testContextBefore() throws Exception {
        Path file = tempDir.resolve("ctx_before.txt");
        Files.writeString(file, """
                line 1
                line 2
                line 3
                MATCH here
                line 5
                line 6
                """);

        var result = FastSearch.search(file, "MATCH", false, 0, 2, 0);

        assertNotNull(result);
        // Должно быть 3 строки: 2 контекста + 1 матч
        assertEquals(3, result.matches().size());
        assertEquals(2, result.matches().get(0).lineNumber());
        assertFalse(result.matches().get(0).isMatch());
        assertEquals(4, result.matches().get(2).lineNumber());
        assertTrue(result.matches().get(2).isMatch());
    }

    @Test
    void testContextAfter() throws Exception {
        Path file = tempDir.resolve("ctx_after.txt");
        Files.writeString(file, """
                line 1
                MATCH here
                line 3
                line 4
                line 5
                """);

        var result = FastSearch.search(file, "MATCH", false, 0, 0, 2);

        assertNotNull(result);
        assertEquals(3, result.matches().size());
        assertEquals(2, result.matches().get(0).lineNumber());
        assertTrue(result.matches().get(0).isMatch());
        assertEquals(4, result.matches().get(2).lineNumber());
        assertFalse(result.matches().get(2).isMatch());
    }

    @Test
    void testContextBothSides() throws Exception {
        Path file = tempDir.resolve("ctx_both.txt");
        Files.writeString(file, """
                line 1
                line 2
                MATCH here
                line 4
                line 5
                """);

        var result = FastSearch.search(file, "MATCH", false, 0, 1, 1);

        assertNotNull(result);
        assertEquals(3, result.matches().size());
        assertEquals(2, result.matches().get(0).lineNumber());
        assertEquals(3, result.matches().get(1).lineNumber());
        assertEquals(4, result.matches().get(2).lineNumber());
        assertTrue(result.matches().get(1).isMatch());
    }

    @Test
    void testContextMergesOverlapping() throws Exception {
        Path file = tempDir.resolve("ctx_merge.txt");
        Files.writeString(file, """
                line 1
                MATCH 1
                line 3
                MATCH 2
                line 5
                """);

        var result = FastSearch.search(file, "MATCH", false, 0, 1, 1);

        assertNotNull(result);
        // Контексты перекрываются, должны слиться
        // line 1, MATCH 1, line 3, MATCH 2, line 5 = 5 уникальных строк
        assertEquals(5, result.matches().size());
    }

    // ==================== CRC ====================

    @Test
    void testCrcCalculation() throws Exception {
        Path file = tempDir.resolve("crc.txt");
        String content = "Test content for CRC";
        Files.writeString(file, content);

        var result = FastSearch.search(file, "Test", false, 0, 0, 0);

        assertNotNull(result);
        assertTrue(result.crc32c() != 0);

        // CRC должен меняться при изменении содержимого
        Files.writeString(file, content + " modified");
        var result2 = FastSearch.search(file, "Test", false, 0, 0, 0);

        assertNotNull(result2);
        assertNotEquals(result.crc32c(), result2.crc32c());
    }

    // ==================== Номера строк ====================

    @Test
    void testLineNumbersAccuracy() throws Exception {
        Path file = tempDir.resolve("lines.txt");
        Files.writeString(file, """
                Line 1
                Line 2
                Target Line 3
                Line 4
                Another Target Line 5
                Line 6
                """);

        var result = FastSearch.search(file, "Target", false, 0, 0, 0);

        assertNotNull(result);
        assertEquals(2, result.matches().size());
        assertEquals(3, result.matches().get(0).lineNumber());
        assertEquals(5, result.matches().get(1).lineNumber());
        assertTrue(result.matches().get(0).text().contains("Target Line 3"));
        assertTrue(result.matches().get(1).text().contains("Another Target Line 5"));
    }

    @Test
    void testLineCountCorrect() throws Exception {
        Path file = tempDir.resolve("count.txt");
        Files.writeString(file, "1\n2\n3\n4\n5");

        var result = FastSearch.search(file, "3", false, 0, 0, 0);

        assertNotNull(result);
        assertEquals(5, result.lineCount());
    }

    // ==================== Граничные случаи ====================

    @Test
    void testEmptyFile() throws Exception {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "");

        var result = FastSearch.search(file, "anything", false, 0, 0, 0);

        assertNull(result);
    }

    @Test
    void testSingleLineFile() throws Exception {
        Path file = tempDir.resolve("single.txt");
        Files.writeString(file, "Single line content");

        var result = FastSearch.search(file, "line", false, 0, 0, 0);

        assertNotNull(result);
        assertEquals(1, result.lineCount());
        assertEquals(1, result.matches().size());
        assertEquals(1, result.matches().get(0).lineNumber());
    }

    @Test
    void testMatchAtStartOfFile() throws Exception {
        Path file = tempDir.resolve("start.txt");
        Files.writeString(file, "MATCH at start\nLine 2\nLine 3");

        var result = FastSearch.search(file, "MATCH", false, 0, 2, 0);

        assertNotNull(result);
        assertEquals(1, result.matches().get(0).lineNumber());
    }

    @Test
    void testMatchAtEndOfFile() throws Exception {
        Path file = tempDir.resolve("end.txt");
        Files.writeString(file, "Line 1\nLine 2\nMATCH at end");

        var result = FastSearch.search(file, "MATCH", false, 0, 0, 2);

        assertNotNull(result);
        assertEquals(3, result.matches().get(0).lineNumber());
        // Контекст после не должен выйти за границы файла
        assertEquals(1, result.matches().size());
    }

    @Test
    void testMultipleMatchesSameLine() throws Exception {
        Path file = tempDir.resolve("multi.txt");
        Files.writeString(file, "foo foo foo\nbar\nfoo");

        var result = FastSearch.search(file, "foo", false, 0, 0, 0);

        assertNotNull(result);
        // Должно быть 2 уникальные строки (1 и 3), не 4 совпадения
        assertEquals(2, result.matches().size());
    }

    @Test
    void testWindowsLineEndings() throws Exception {
        Path file = tempDir.resolve("crlf.txt");
        Files.writeString(file, "Line 1\r\nMATCH here\r\nLine 3\r\n");

        var result = FastSearch.search(file, "MATCH", false, 0, 0, 0);

        assertNotNull(result);
        assertEquals(1, result.matches().size());
        assertEquals(2, result.matches().get(0).lineNumber());
        // \r должен быть удалён из текста строки
        assertFalse(result.matches().get(0).text().contains("\r"));
    }

    // ==================== Бинарные файлы ====================

    @Test
    void testBinaryFileSkipped() throws Exception {
        Path file = tempDir.resolve("binary.bin");
        byte[] binaryContent = new byte[]{0x48, 0x65, 0x6C, 0x00, 0x6C, 0x6F}; // "Hel\0lo"
        Files.write(file, binaryContent);

        var result = FastSearch.search(file, "Hel", false, 0, 0, 0);

        assertNull(result); // Бинарный файл должен быть пропущен
    }

    // ==================== Большие файлы ====================

    @Test
    void testLargeFilePerformance() throws Exception {
        Path file = tempDir.resolve("large.txt");
        // Создаём файл ~1MB
        String line = "This is a line of text for performance testing. ";
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            content.append(line).append(i).append("\n");
        }
        content.append("UNIQUE_MARKER_12345\n");
        Files.writeString(file, content.toString());

        long start = System.currentTimeMillis();
        var result = FastSearch.search(file, "UNIQUE_MARKER", false, 0, 0, 0);
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(result);
        assertEquals(1, result.matches().size());
        assertEquals(20001, result.matches().get(0).lineNumber());

        // Должно быть быстро (< 500ms даже на медленном диске)
        assertTrue(elapsed < 500, "Search took too long: " + elapsed + "ms");
    }

    @Test
    void testLargeFileWithManyMatches() throws Exception {
        Path file = tempDir.resolve("many_matches.txt");
        // 10000 строк, каждая содержит "match"
        String content = IntStream.range(0, 10000)
                .mapToObj(i -> "match line " + i)
                .collect(Collectors.joining("\n"));
        Files.writeString(file, content);

        long start = System.currentTimeMillis();
        var result = FastSearch.search(file, "match", false, 100, 0, 0);
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(result);
        assertEquals(100, result.matches().size()); // Ограничение maxResults
        assertTrue(elapsed < 200, "Search took too long: " + elapsed + "ms");
    }

    // ==================== Кодировки ====================

    @Test
    void testUtf8Content() throws Exception {
        Path file = tempDir.resolve("utf8.txt");
        Files.writeString(file, "Привет мир\nПоиск кириллицы\nКонец");

        var result = FastSearch.search(file, "кириллицы", false, 0, 0, 0);

        assertNotNull(result);
        assertEquals(1, result.matches().size());
        assertEquals(2, result.matches().get(0).lineNumber());
    }

    @Test
    void testSpecialCharacters() throws Exception {
        Path file = tempDir.resolve("special.txt");
        Files.writeString(file, "Line with $pecial ch@rs\nAnother [line]\nEnd");

        // Литеральный поиск не должен интерпретировать как regex
        var result = FastSearch.search(file, "$pecial", false, 0, 0, 0);

        assertNotNull(result);
        assertEquals(1, result.matches().size());
    }

    @Test
    void testRegexSpecialCharactersEscaped() throws Exception {
        Path file = tempDir.resolve("regex_special.txt");
        Files.writeString(file, "Price: $100\nPrice: $200\nNo price");

        // В regex $ нужно экранировать
        var result = FastSearch.search(file, "\\$\\d+", true, 0, 0, 0);

        assertNotNull(result);
        assertEquals(2, result.matches().size());
    }
}
