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
package ru.nts.tools.mcp.tools.editing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.LineAccessToken;
import ru.nts.tools.mcp.core.LineAccessTracker;
import ru.nts.tools.mcp.core.NtsTokenException;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;
import ru.nts.tools.mcp.tools.editing.EditFileTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для инструмента построчного редактирования файлов (EditFileTool).
 * Проверяют:
 * 1. Замену строк по диапазону с токеном доступа.
 * 2. Многофайловое пакетное редактирование.
 * 3. Атомарность транзакций и корректный откат при сбоях.
 * 4. Валидацию токенов перед редактированием.
 */
class EditFileToolTest {

    private final EditFileTool tool = new EditFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        LineAccessTracker.reset();
    }

    /**
     * Вспомогательный метод: регистрирует доступ и возвращает токен.
     */
    private String registerAccess(Path file, int startLine, int endLine) throws Exception {
        String content = Files.readString(file);
        int lineCount = content.split("\n", -1).length;
        String rangeContent = buildRangeContent(content, startLine, endLine);
        LineAccessToken token = LineAccessTracker.registerAccess(file, startLine, endLine, rangeContent, lineCount);
        return token.encode();
    }

    /**
     * Вспомогательный метод: регистрирует доступ ко всем строкам файла.
     */
    private String registerFullAccess(Path file) throws Exception {
        String content = Files.readString(file);
        int lineCount = content.split("\n", -1).length;
        return registerAccess(file, 1, lineCount);
    }

    /**
     * Проверяет базовую замену строки с токеном.
     */
    @Test
    void testReplaceLine() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello World");
        String token = registerFullAccess(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 1);
        params.put("content", "Hello Java");
        params.put("accessToken", token);

        tool.execute(params);
        assertEquals("Hello Java", Files.readString(file));
    }

    /**
     * Проверяет, что редактирование без токена отклоняется.
     */
    @Test
    void testEditWithoutTokenFails() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Content");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 1);
        params.put("content", "New Content");

        NtsTokenException ex = assertThrows(NtsTokenException.class, () -> tool.execute(params));
        assertTrue(ex.getMessage().contains("TOKEN_REQUIRED"));
    }

    /**
     * Проверяет, что редактирование вне диапазона токена отклоняется.
     */
    @Test
    void testEditOutsideTokenRangeFails() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.write(file, List.of("Line 1", "Line 2", "Line 3", "Line 4", "Line 5"));
        // Токен только на строки 1-2
        String token = registerAccess(file, 1, 2);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 4);
        params.put("content", "Modified");
        params.put("accessToken", token);

        SecurityException ex = assertThrows(SecurityException.class, () -> tool.execute(params));
        assertTrue(ex.getMessage().contains("does not cover"));
    }

    /**
     * Проверяет успешное редактирование нескольких файлов в одной транзакции.
     */
    @Test
    void testMultiFileBatchEdit() throws Exception {
        Path f1 = tempDir.resolve("file1.txt");
        Path f2 = tempDir.resolve("file2.txt");
        Files.writeString(f1, "Original 1");
        Files.writeString(f2, "Original 2");
        String token1 = registerFullAccess(f1);
        String token2 = registerFullAccess(f2);

        ObjectNode params = mapper.createObjectNode();
        ArrayNode edits = params.putArray("edits");

        edits.addObject().put("path", f1.toString()).put("startLine", 1).put("content", "Changed 1").put("accessToken", token1);
        edits.addObject().put("path", f2.toString()).put("startLine", 1).put("content", "Changed 2").put("accessToken", token2);

        tool.execute(params);

        assertEquals("Changed 1", Files.readString(f1));
        assertEquals("Changed 2", Files.readString(f2));
    }

    /**
     * Проверяет откат при ошибке валидации содержимого.
     */
    @Test
    void testMultiFileRollback() throws Exception {
        Path f1 = tempDir.resolve("file1.txt");
        Path f2 = tempDir.resolve("file2.txt");
        Files.writeString(f1, "Safe");
        Files.writeString(f2, "Danger");
        String token1 = registerFullAccess(f1);
        String token2 = registerFullAccess(f2);

        ObjectNode params = mapper.createObjectNode();
        ArrayNode edits = params.putArray("edits");

        edits.addObject().put("path", f1.toString()).put("startLine", 1).put("content", "Broken").put("accessToken", token1);

        ObjectNode e2 = edits.addObject();
        e2.put("path", f2.toString());
        e2.put("startLine", 1).put("endLine", 1);
        e2.put("expectedContent", "WRONG_CONTENT");
        e2.put("content", "ShouldNotChange");
        e2.put("accessToken", token2);

        assertThrows(IllegalStateException.class, () -> tool.execute(params));

        assertEquals("Safe", Files.readString(f1));
        assertEquals("Danger", Files.readString(f2));
    }

    /**
     * Проверяет автоматическую коррекцию индексов при нескольких операциях.
     */
    @Test
    void testBatchOperationsWithOffsets() throws Exception {
        Path file = tempDir.resolve("batch.txt");
        Files.write(file, List.of("Line 1", "Line 2", "Line 3"));
        String token = registerFullAccess(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("accessToken", token);
        ArrayNode ops = params.putArray("operations");

        ops.addObject().put("operation", "replace").put("startLine", 1).put("endLine", 1).put("content", "A\nB\nC");
        ops.addObject().put("operation", "delete").put("startLine", 2).put("endLine", 2);

        tool.execute(params);

        List<String> result = Files.readAllLines(file);
        assertEquals(4, result.size());
        assertEquals("A", result.get(0));
        assertEquals("B", result.get(1));
        assertEquals("C", result.get(2));
        assertEquals("Line 3", result.get(3));
    }

    /**
     * Тестирует удаление одиночной строки (regression test для off-by-one бага).
     * Баг: при delete startLine=N, endLine=N, lineDelta=-1 приводило к
     * newEditEnd = N + (-1) = N-1 < N, что вызывало ошибку валидации токена.
     */
    @Test
    void testDeleteSingleLine() throws Exception {
        Path file = tempDir.resolve("delete_single.txt");
        Files.write(file, List.of("Line 1", "Line 2", "Line 3", "Line 4", "Line 5"));
        String token = registerFullAccess(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("accessToken", token);
        ArrayNode ops = params.putArray("operations");

        // Удаление только строки 3 (startLine == endLine)
        ops.addObject().put("operation", "delete").put("startLine", 3).put("endLine", 3);

        // Это не должно выбрасывать "endLine (2) must be >= startLine (3)"
        tool.execute(params);

        List<String> result = Files.readAllLines(file);
        assertEquals(4, result.size());
        assertEquals("Line 1", result.get(0));
        assertEquals("Line 2", result.get(1));
        assertEquals("Line 4", result.get(2));  // Line 3 удалена
        assertEquals("Line 5", result.get(3));
    }

    /**
     * Тестирует операцию вставки после указанной строки.
     */
    @Test
    void testInsertAfter() throws Exception {
        Path file = tempDir.resolve("insert.txt");
        Files.write(file, List.of("Start", "End"));
        String token = registerFullAccess(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("accessToken", token);
        ArrayNode ops = params.putArray("operations");

        ops.addObject().put("operation", "insert_after").put("line", 1).put("content", "Middle");

        tool.execute(params);

        List<String> result = Files.readAllLines(file);
        assertEquals("Middle", result.get(1));
    }

    /**
     * Проверяет возврат нового токена после успешного редактирования.
     */
    @Test
    void testNewTokenReturnedAfterEdit() throws Exception {
        Path file = tempDir.resolve("token.txt");
        Files.writeString(file, "Original");
        String token = registerFullAccess(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 1);
        params.put("content", "Modified");
        params.put("accessToken", token);

        var result = tool.execute(params);
        String responseText = result.get("content").get(0).get("text").asText();

        assertTrue(responseText.contains("[NEW TOKEN:"));
        assertTrue(responseText.contains("LAT:"));
    }

    /**
     * Проверяет инвалидацию старого токена после редактирования.
     */
    @Test
    void testOldTokenInvalidAfterEdit() throws Exception {
        Path file = tempDir.resolve("invalid.txt");
        Files.writeString(file, "Line 1");
        String oldToken = registerFullAccess(file);

        // Первое редактирование
        ObjectNode params1 = mapper.createObjectNode();
        params1.put("path", file.toString());
        params1.put("startLine", 1);
        params1.put("content", "Modified");
        params1.put("accessToken", oldToken);
        tool.execute(params1);

        // Попытка использовать старый токен
        ObjectNode params2 = mapper.createObjectNode();
        params2.put("path", file.toString());
        params2.put("startLine", 1);
        params2.put("content", "Again");
        params2.put("accessToken", oldToken);

        SecurityException ex = assertThrows(SecurityException.class, () -> tool.execute(params2));
        assertTrue(ex.getMessage().contains("Token validation failed"));
    }

    /**
     * Проверяет DryRun режим (без изменений файла).
     */
    @Test
    void testDryRunMode() throws Exception {
        Path file = tempDir.resolve("dry.txt");
        Files.writeString(file, "Original");
        String token = registerFullAccess(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 1);
        params.put("content", "Modified");
        params.put("accessToken", token);
        params.put("dryRun", true);

        var result = tool.execute(params);
        String responseText = result.get("content").get(0).get("text").asText();

        assertTrue(responseText.contains("[DRY RUN]"));
        assertEquals("Original", Files.readString(file), "Файл не должен измениться");
    }

    /**
     * Проверяет диагностику при ошибке валидации expectedContent.
     */
    @Test
    void testExpectedContentFailureDiagnostics() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.write(file, List.of("AAA", "BBB", "CCC"));
        String token = registerFullAccess(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 2).put("endLine", 2);
        params.put("expectedContent", "WRONG");
        params.put("content", "XXX");
        params.put("accessToken", token);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> tool.execute(params));
        assertTrue(ex.getMessage().contains("ACTUAL CONTENT IN FILE:"));
        assertTrue(ex.getMessage().contains("[BBB]"));
    }

    @Test
    void testContextStartPatternWithRelativeOffset() throws Exception {
        // Тест: contextStartPattern + startLine: 0 должен правильно разрешаться для проверки токена
        // Баг: раньше startLine=0 проверялся напрямую (0 < 1), а не как абсолютный номер строки
        Path file = tempDir.resolve("pattern.java");
        Files.writeString(file, """
                package com.example;

                public class MyClass {
                    public void method1() {
                        // line 5
                    }

                    public void targetMethod() {
                        // line 9 - this is the anchor
                    }

                    public void method2() {
                        // line 13
                    }
                }
                """);

        // Регистрируем доступ к строкам 8-10 (где targetMethod)
        String token = registerAccess(file, 8, 10);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "pattern.java");
        params.put("contextStartPattern", "public void targetMethod");
        params.put("startLine", 0);  // Относительный офсет: 0 = сама anchor строка
        params.put("endLine", 0);    // То есть строка 8 (где найден паттерн)
        params.put("content", "    public void renamedMethod() {");
        params.put("accessToken", token);

        // Должно успешно выполниться, а не падать с "Token does not cover edit range [0-0]"
        var result = tool.execute(params);
        String resultText = result.get("content").get(0).get("text").asText();
        assertTrue(resultText.contains("Edits applied") || resultText.contains("applied"),
                "Expected successful edit but got: " + resultText);

        String content = Files.readString(file);
        assertTrue(content.contains("renamedMethod"), "Method should be renamed");
    }

    /**
     * Тест на исправление 4.1: валидация конфликта operations + content верхнего уровня.
     */
    @Test
    void testOperationsAndContentConflictThrowsError() throws Exception {
        Path file = tempDir.resolve("conflict.txt");
        Files.write(file, List.of("Line 1", "Line 2", "Line 3"));
        String token = registerFullAccess(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("accessToken", token);

        // Одновременно operations И content верхнего уровня - конфликт
        params.put("content", "This content should be ignored but causes error");
        ArrayNode ops = params.putArray("operations");
        ops.addObject().put("operation", "replace").put("startLine", 1).put("content", "Modified");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> tool.execute(params));
        assertTrue(ex.getMessage().contains("CONFLICT"),
                "Expected CONFLICT error but got: " + ex.getMessage());
    }

    /**
     * Тест: operations без content верхнего уровня - должно работать.
     */
    @Test
    void testOperationsWithoutTopLevelContentWorks() throws Exception {
        Path file = tempDir.resolve("noconflict.txt");
        Files.write(file, List.of("Line 1", "Line 2"));
        String token = registerFullAccess(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("accessToken", token);

        ArrayNode ops = params.putArray("operations");
        ops.addObject().put("operation", "replace").put("startLine", 1).put("content", "Modified");

        assertDoesNotThrow(() -> tool.execute(params));
        assertTrue(Files.readString(file).contains("Modified"));
    }

    /**
     * Вспомогательный метод: строит содержимое диапазона для токена.
     */
    private String buildRangeContent(String content, int startLine, int endLine) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, startLine - 1);
        int end = Math.min(lines.length, endLine);
        for (int i = start; i < end; i++) {
            if (i > start) sb.append("\n");
            sb.append(String.format("%4d\t%s", i + 1, lines[i].replace("\r", "")));
        }
        return sb.toString();
    }
}
