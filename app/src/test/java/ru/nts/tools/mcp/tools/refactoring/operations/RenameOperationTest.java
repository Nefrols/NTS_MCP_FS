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
 * Тесты для RenameOperation.
 * Проверяет:
 * - Поиск символа по kind без column (исправление 2.1)
 * - Поддержку signature для различения перегрузок (исправление 2.2)
 */
class RenameOperationTest {

    @TempDir
    Path tempDir;

    private ObjectMapper mapper;
    private RenameOperation operation;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        operation = new RenameOperation();
        PathSanitizer.setRoot(tempDir);
        SessionContext.resetAll();
        SessionContext ctx = SessionContext.getOrCreate("test");
        SessionContext.setCurrent(ctx);
    }

    @Test
    void testValidateParams_requiresPath() {
        ObjectNode params = mapper.createObjectNode();
        params.put("newName", "newMethod");
        params.put("symbol", "oldMethod");

        assertThrows(IllegalArgumentException.class, () -> operation.validateParams(params));
    }

    @Test
    void testValidateParams_requiresNewName() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.java");
        params.put("symbol", "oldMethod");

        assertThrows(IllegalArgumentException.class, () -> operation.validateParams(params));
    }

    @Test
    void testValidateParams_requiresSymbolOrLine() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.java");
        params.put("newName", "newMethod");

        assertThrows(IllegalArgumentException.class, () -> operation.validateParams(params));
    }

    @Test
    void testValidateParams_validWithSymbol() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.java");
        params.put("newName", "newMethod");
        params.put("symbol", "oldMethod");

        assertDoesNotThrow(() -> operation.validateParams(params));
    }

    @Test
    void testValidateParams_validWithLine() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.java");
        params.put("newName", "newMethod");
        params.put("line", 10);

        assertDoesNotThrow(() -> operation.validateParams(params));
    }

    @Test
    void testValidateParams_invalidIdentifier() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.java");
        params.put("newName", "123invalid");
        params.put("symbol", "oldMethod");

        assertThrows(IllegalArgumentException.class, () -> operation.validateParams(params));
    }

    @Test
    void testValidateParams_signatureFormat() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.java");
        params.put("newName", "newMethod");
        params.put("symbol", "oldMethod");
        params.put("signature", "(String, int)");

        assertDoesNotThrow(() -> operation.validateParams(params));
    }

    @Test
    void testValidateParams_invalidSignatureFormat() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.java");
        params.put("newName", "newMethod");
        params.put("symbol", "oldMethod");
        params.put("signature", "String, int"); // Missing parentheses

        assertThrows(IllegalArgumentException.class, () -> operation.validateParams(params));
    }

    @Test
    void testFindSymbolByKindOnLine() throws IOException, RefactoringException {
        // Создаём Java файл с интерфейсом и методом на одной строке
        String javaCode = """
                package test;

                public interface TestInterface {
                    void testMethod();
                    void anotherMethod(String param);
                }
                """;

        Path javaFile = tempDir.resolve("TestInterface.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("newName", "renamedMethod");
        params.put("line", 4);  // Строка с testMethod
        params.put("kind", "method");  // Указываем что ищем метод

        // Preview должен найти метод, а не интерфейс
        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());
        // Проверяем что нашли метод testMethod, а не интерфейс
        assertTrue(result.summary().contains("testMethod") ||
                result.changes().stream()
                        .flatMap(c -> c.details().stream())
                        .anyMatch(d -> d.before().contains("testMethod")));
    }

    @Test
    void testRenameWithSignatureFilter() throws IOException, RefactoringException {
        // Создаём Java файл с перегруженными методами
        String javaCode = """
                package test;

                public class TestClass {
                    public void process(String text) {
                        System.out.println(text);
                    }

                    public void process(String text, int count) {
                        for (int i = 0; i < count; i++) {
                            System.out.println(text);
                        }
                    }

                    public void process(int number) {
                        System.out.println(number);
                    }
                }
                """;

        Path javaFile = tempDir.resolve("TestClass.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        // Переименовываем только process(String)
        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("newName", "processText");
        params.put("symbol", "process");
        params.put("kind", "method");
        params.put("signature", "(String)");  // Только версия с одним String параметром

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());
    }

    @Test
    void testPreview_symbolNotFound() throws IOException, RefactoringException {
        String javaCode = """
                package test;
                public class Empty {}
                """;

        Path javaFile = tempDir.resolve("Empty.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("newName", "newMethod");
        params.put("symbol", "nonExistent");

        // Метод handleSymbolNotFound возвращает RefactoringResult.error(), а не выбрасывает исключение
        RefactoringResult result = operation.preview(params, context);
        assertEquals(RefactoringResult.Status.ERROR, result.status());
        assertTrue(result.error().contains("not found"));
    }

    @Test
    void testGetName() {
        assertEquals("rename", operation.getName());
    }

    // ==================== REPORT4 Issue 1.2 Tests ====================
    // Regression tests: rename should not confuse return type with method name

    @Test
    void testRenameMethod_doesNotConfuseWithReturnType() throws IOException, RefactoringException {
        // REPORT4 Issue 1.2: When kind=method is specified, rename should find the method,
        // not the return type (which might be a class with the same name on the line)
        String javaCode = """
                package test;

                public class UserService {
                    public User getUser(String id) {
                        return new User(id);
                    }
                }
                """;

        Path javaFile = tempDir.resolve("UserService.java");
        Files.writeString(javaFile, javaCode);

        // Create User class too
        String userClass = """
                package test;
                public class User {
                    private String id;
                    public User(String id) { this.id = id; }
                }
                """;
        Files.writeString(tempDir.resolve("User.java"), userClass);

        RefactoringContext context = new RefactoringContext();

        // Rename method 'getUser' on line 4 with kind=method
        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("newName", "findUser");
        params.put("line", 4);  // Line with "public User getUser(String id)"
        params.put("kind", "method");

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());

        // Should rename 'getUser' to 'findUser', NOT 'User' to 'findUser'
        String diff = result.changes().stream()
                .map(RefactoringResult.FileChange::diff)
                .filter(d -> d != null)
                .findFirst()
                .orElse("");

        assertTrue(diff.contains("getUser") || diff.contains("findUser"),
                "Should be renaming method 'getUser', not type 'User': " + diff);
        assertFalse(diff.contains("+findUser(") && diff.contains("-User "),
                "Should NOT be renaming class 'User' to 'findUser': " + diff);
    }

    @Test
    void testRenameMethod_kindMethodWithLine_noColumn() throws IOException, RefactoringException {
        // Test that line + kind=method works without specifying column
        String javaCode = """
                package test;

                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }

                    public int subtract(int a, int b) {
                        return a - b;
                    }
                }
                """;

        Path javaFile = tempDir.resolve("Calculator.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("newName", "sum");
        params.put("line", 4);  // Line with "public int add"
        params.put("kind", "method");
        // No column specified!

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());

        // Should find 'add' method - verify by checking diff contains add->sum change
        boolean foundAddMethod = result.changes().stream()
                .flatMap(c -> c.details().stream())
                .anyMatch(d -> d.before().contains("add") && d.after().contains("sum"));
        assertTrue(foundAddMethod,
                "Should find method 'add' on line 4 and rename to 'sum': " + result.changes());
    }

    @Test
    void testRenameClass_kindClass() throws IOException, RefactoringException {
        // Test that kind=class finds class, not method
        String javaCode = """
                package test;

                public class MyClass {
                    public MyClass create() {
                        return new MyClass();
                    }
                }
                """;

        Path javaFile = tempDir.resolve("MyClass.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("newName", "RenamedClass");
        params.put("line", 3);  // Line with "public class MyClass"
        params.put("kind", "class");

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());

        // Should find class 'MyClass', not method 'create'
        // Verify by checking that changes contain MyClass->RenamedClass transformation
        boolean foundClassRename = result.changes().stream()
                .flatMap(c -> c.details().stream())
                .anyMatch(d -> d.before().contains("MyClass") && d.after().contains("RenamedClass"));
        assertTrue(foundClassRename,
                "Should find class 'MyClass' and rename to 'RenamedClass': " + result.changes());
    }

    @Test
    void testRenameKindMethod_returnsNullWhenNoMethodOnLine() throws IOException, RefactoringException {
        // If kind=method but no method on the line, should return null (symbol not found)
        String javaCode = """
                package test;

                public class NoMethodHere {
                    private String field;
                }
                """;

        Path javaFile = tempDir.resolve("NoMethodHere.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("newName", "newMethod");
        params.put("line", 4);  // Line with field, not method
        params.put("kind", "method");

        RefactoringResult result = operation.preview(params, context);

        // Should return error because no method on line 4
        assertEquals(RefactoringResult.Status.ERROR, result.status(),
                "Should return error when no method found on line with kind=method");
    }
}
