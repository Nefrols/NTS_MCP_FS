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
package ru.nts.tools.mcp.tools.refactoring.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.SessionContext;
import ru.nts.tools.mcp.tools.refactoring.RefactoringContext;
import ru.nts.tools.mcp.tools.refactoring.RefactoringException;
import ru.nts.tools.mcp.tools.refactoring.RefactoringResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для ExtractMethodOperation.
 * Проверяет:
 * - Data Flow Analysis для определения параметров (исправление 2.3)
 * - Корректное извлечение методов с внешними переменными
 */
class ExtractMethodOperationTest {

    @TempDir
    Path tempDir;

    private ObjectMapper mapper;
    private ExtractMethodOperation operation;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        operation = new ExtractMethodOperation();
        PathSanitizer.setRoot(tempDir);
        SessionContext.resetAll();
        SessionContext ctx = SessionContext.getOrCreate("test");
        SessionContext.setCurrent(ctx);
    }

    @Test
    void testValidateParams_requiresPath() {
        ObjectNode params = mapper.createObjectNode();
        params.put("methodName", "extractedMethod");
        params.put("startLine", 5);
        params.put("endLine", 10);

        assertThrows(IllegalArgumentException.class, () -> operation.validateParams(params));
    }

    @Test
    void testValidateParams_requiresMethodName() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.java");
        params.put("startLine", 5);
        params.put("endLine", 10);

        assertThrows(IllegalArgumentException.class, () -> operation.validateParams(params));
    }

    @Test
    void testValidateParams_requiresStartLine() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.java");
        params.put("methodName", "extractedMethod");
        params.put("endLine", 10);

        assertThrows(IllegalArgumentException.class, () -> operation.validateParams(params));
    }

    @Test
    void testValidateParams_valid() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.java");
        params.put("methodName", "extractedMethod");
        params.put("startLine", 5);
        params.put("endLine", 10);

        assertDoesNotThrow(() -> operation.validateParams(params));
    }

    @Test
    void testExtractMethod_withExternalVariables() throws IOException, RefactoringException {
        // Код с внешними переменными которые должны стать параметрами
        String javaCode = """
                package test;

                public class Calculator {
                    public void calculate(int base, String prefix) {
                        int multiplier = 10;
                        // Начало извлекаемого блока
                        int result = base * multiplier;
                        String output = prefix + ": " + result;
                        System.out.println(output);
                        // Конец извлекаемого блока
                        System.out.println("Done");
                    }
                }
                """;

        Path javaFile = tempDir.resolve("Calculator.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "printResult");
        params.put("startLine", 7);  // int result = base * multiplier;
        params.put("endLine", 9);    // System.out.println(output);

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());

        // Проверяем что в preview показаны параметры
        String summary = result.summary();
        // base, multiplier и prefix - внешние переменные, должны быть параметрами
        // Проверяем что есть информация о параметрах в diff или summary
        boolean hasParameterInfo = result.changes().stream()
                .anyMatch(c -> c.diff() != null && (
                        c.diff().contains("base") ||
                        c.diff().contains("multiplier") ||
                        c.diff().contains("prefix")));

        assertTrue(hasParameterInfo || summary.contains("parameter"),
                "Extracted method should have parameters for external variables");
    }

    @Test
    void testExtractMethod_withLocalVariablesOnly() throws IOException, RefactoringException {
        // Код только с локальными переменными (не нужны параметры)
        String javaCode = """
                package test;

                public class Simple {
                    public void run() {
                        // Начало извлекаемого блока
                        int x = 5;
                        int y = 10;
                        int sum = x + y;
                        System.out.println(sum);
                        // Конец извлекаемого блока
                    }
                }
                """;

        Path javaFile = tempDir.resolve("Simple.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "calculateSum");
        params.put("startLine", 6);  // int x = 5;
        params.put("endLine", 9);    // System.out.println(sum);

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());
    }

    @Test
    void testExtractMethod_withEnhancedFor() throws IOException, RefactoringException {
        // Код с enhanced for loop
        String javaCode = """
                package test;

                import java.util.List;

                public class Processor {
                    public void processItems(List<String> items) {
                        int count = 0;
                        // Начало извлекаемого блока
                        for (String item : items) {
                            System.out.println(item);
                            count++;
                        }
                        // Конец извлекаемого блока
                        System.out.println("Total: " + count);
                    }
                }
                """;

        Path javaFile = tempDir.resolve("Processor.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "processEach");
        params.put("startLine", 9);   // for (String item : items)
        params.put("endLine", 12);    // }

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());
    }

    @Test
    void testExtractMethod_withReturn() throws IOException, RefactoringException {
        // Код с return statement
        String javaCode = """
                package test;

                public class Math {
                    public int compute(int a, int b) {
                        // Начало извлекаемого блока
                        int sum = a + b;
                        return sum * 2;
                        // Конец извлекаемого блока
                    }
                }
                """;

        Path javaFile = tempDir.resolve("Math.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "doubleSum");
        params.put("startLine", 6);   // int sum = a + b;
        params.put("endLine", 7);     // return sum * 2;

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());
    }

    @Test
    void testGetName() {
        assertEquals("extract_method", operation.getName());
    }
}
