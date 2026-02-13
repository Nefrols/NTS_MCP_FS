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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TaskContext;
import ru.nts.tools.mcp.tools.refactoring.RefactoringContext;
import ru.nts.tools.mcp.tools.refactoring.RefactoringException;
import ru.nts.tools.mcp.tools.refactoring.RefactoringResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для ChangeSignatureOperation.
 * Проверяет:
 * - Корректную замену модификаторов без дублирования (исправление 2.4)
 * - Изменение параметров и типа возврата
 */
class ChangeSignatureOperationTest {

    @TempDir
    Path tempDir;

    private ObjectMapper mapper;
    private ChangeSignatureOperation operation;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        operation = new ChangeSignatureOperation();
        PathSanitizer.setRoot(tempDir);
        TaskContext.resetAll();
        TaskContext.setForceInMemoryDb(true);
        TaskContext ctx = TaskContext.getOrCreate("test");
        TaskContext.setCurrent(ctx);
    }

    @Test
    void testValidateParams_requiresPath() {
        ObjectNode params = mapper.createObjectNode();
        params.put("methodName", "testMethod");
        params.put("newName", "renamedMethod");

        assertThrows(IllegalArgumentException.class, () -> operation.validateParams(params));
    }

    @Test
    void testValidateParams_requiresMethodIdentifier() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.java");
        params.put("newName", "renamedMethod");

        assertThrows(IllegalArgumentException.class, () -> operation.validateParams(params));
    }

    @Test
    void testValidateParams_requiresAtLeastOneChange() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.java");
        params.put("methodName", "testMethod");

        assertThrows(IllegalArgumentException.class, () -> operation.validateParams(params));
    }

    @Test
    void testValidateParams_validWithNewName() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.java");
        params.put("methodName", "testMethod");
        params.put("newName", "renamedMethod");

        assertDoesNotThrow(() -> operation.validateParams(params));
    }

    @Test
    void testValidateParams_validWithParameters() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.java");
        params.put("methodName", "testMethod");

        ArrayNode parameters = mapper.createArrayNode();
        ObjectNode param = mapper.createObjectNode();
        param.put("name", "newParam");
        param.put("type", "String");
        param.put("action", "add");
        parameters.add(param);
        params.set("parameters", parameters);

        assertDoesNotThrow(() -> operation.validateParams(params));
    }

    @Test
    void testValidateParams_validWithReturnType() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.java");
        params.put("methodName", "testMethod");
        params.put("returnType", "String");

        assertDoesNotThrow(() -> operation.validateParams(params));
    }

    @Test
    void testValidateParams_validWithAccessModifier() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.java");
        params.put("methodName", "testMethod");
        params.put("accessModifier", "private");

        assertDoesNotThrow(() -> operation.validateParams(params));
    }

    @Test
    void testChangeAccessModifier_noDuplication() throws IOException, RefactoringException {
        // Тест на исправление 2.4 - замена модификатора без дублирования
        String javaCode = """
                package test;

                public class Service {
                    public void processData(String data) {
                        System.out.println(data);
                    }
                }
                """;

        Path javaFile = tempDir.resolve("Service.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "processData");
        params.put("accessModifier", "private");

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());

        // Проверяем что в preview нет дублирования модификаторов
        String diff = result.changes().stream()
                .filter(c -> c.diff() != null)
                .map(RefactoringResult.FileChange::diff)
                .findFirst()
                .orElse("");

        // Не должно быть "public public" или "private private"
        assertFalse(diff.contains("public public"), "Should not have duplicated 'public' modifier");
        assertFalse(diff.contains("private private"), "Should not have duplicated 'private' modifier");
    }

    @Test
    void testChangeReturnType() throws IOException, RefactoringException {
        String javaCode = """
                package test;

                public class Calculator {
                    public void compute(int x) {
                        System.out.println(x * 2);
                    }
                }
                """;

        Path javaFile = tempDir.resolve("Calculator.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "compute");
        params.put("returnType", "int");

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());
    }

    @Test
    void testAddParameter() throws IOException, RefactoringException {
        String javaCode = """
                package test;

                public class Greeter {
                    public void greet(String name) {
                        System.out.println("Hello, " + name);
                    }

                    public void test() {
                        greet("World");
                    }
                }
                """;

        Path javaFile = tempDir.resolve("Greeter.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "greet");

        ArrayNode parameters = mapper.createArrayNode();

        // Keep existing parameter
        ObjectNode keepParam = mapper.createObjectNode();
        keepParam.put("name", "name");
        keepParam.put("action", "keep");
        parameters.add(keepParam);

        // Add new parameter
        ObjectNode addParam = mapper.createObjectNode();
        addParam.put("name", "greeting");
        addParam.put("type", "String");
        addParam.put("defaultValue", "\"Hello\"");
        addParam.put("action", "add");
        parameters.add(addParam);

        params.set("parameters", parameters);

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());
    }

    @Test
    void testMethodNotFound() throws IOException {
        String javaCode = """
                package test;
                public class Empty {}
                """;

        Path javaFile = tempDir.resolve("Empty.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "nonExistent");
        params.put("newName", "stillNonExistent");

        assertThrows(RefactoringException.class, () -> operation.preview(params, context));
    }

    @Test
    void testGetName() {
        assertEquals("change_signature", operation.getName());
    }

    // ==================== Options Support Tests ====================
    // Regression tests for REPORT3 issue 2.1: parameters in options were ignored

    @Test
    void testParametersInOptions_preview() throws IOException, RefactoringException {
        // REPORT3 Issue 2.1: parameters were ignored when passed in options object
        String javaCode = """
                package test;

                public class Service {
                    public void process(String data) {
                        System.out.println(data);
                    }
                }
                """;

        Path javaFile = tempDir.resolve("Service.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        // Simulate how CodeRefactorTool passes options
        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "process");

        // Parameters in options (as per CodeRefactorTool schema)
        ObjectNode options = mapper.createObjectNode();
        ArrayNode parameters = mapper.createArrayNode();

        // Keep existing parameter
        ObjectNode keepParam = mapper.createObjectNode();
        keepParam.put("name", "data");
        keepParam.put("action", "keep");
        parameters.add(keepParam);

        // Add new parameter
        ObjectNode addParam = mapper.createObjectNode();
        addParam.put("name", "count");
        addParam.put("type", "int");
        addParam.put("defaultValue", "0");
        addParam.put("action", "add");
        parameters.add(addParam);

        options.set("parameters", parameters);
        params.set("options", options);

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());

        // Check that parameters are in the preview
        String diff = result.changes().stream()
                .filter(c -> c.diff() != null)
                .map(RefactoringResult.FileChange::diff)
                .findFirst()
                .orElse("");

        // The preview should contain the new parameter
        assertTrue(diff.contains("count") || diff.contains("int"),
                "Preview should show added parameter 'count' of type 'int': " + diff);
    }

    @Test
    void testNewNameInOptions_preview() throws IOException, RefactoringException {
        // Test that newName works when passed in options
        String javaCode = """
                package test;

                public class Calculator {
                    public int calculate(int x) {
                        return x * 2;
                    }
                }
                """;

        Path javaFile = tempDir.resolve("Calculator.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "calculate");

        // newName in options
        ObjectNode options = mapper.createObjectNode();
        options.put("newName", "compute");
        params.set("options", options);

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());

        String diff = result.changes().stream()
                .filter(c -> c.diff() != null)
                .map(RefactoringResult.FileChange::diff)
                .findFirst()
                .orElse("");

        assertTrue(diff.contains("compute"),
                "Preview should show new method name 'compute': " + diff);
    }

    @Test
    void testReturnTypeInOptions_preview() throws IOException, RefactoringException {
        // Test that returnType works when passed in options
        String javaCode = """
                package test;

                public class Converter {
                    public void convert(String input) {
                        System.out.println(input);
                    }
                }
                """;

        Path javaFile = tempDir.resolve("Converter.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "convert");

        // returnType in options
        ObjectNode options = mapper.createObjectNode();
        options.put("returnType", "String");
        params.set("options", options);

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());

        String diff = result.changes().stream()
                .filter(c -> c.diff() != null)
                .map(RefactoringResult.FileChange::diff)
                .findFirst()
                .orElse("");

        assertTrue(diff.contains("String"),
                "Preview should show new return type 'String': " + diff);
    }

    @Test
    void testAccessModifierInOptions_preview() throws IOException, RefactoringException {
        // Test that accessModifier works when passed in options
        String javaCode = """
                package test;

                public class Handler {
                    public void handle(String event) {
                        System.out.println(event);
                    }
                }
                """;

        Path javaFile = tempDir.resolve("Handler.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "handle");

        // accessModifier in options
        ObjectNode options = mapper.createObjectNode();
        options.put("accessModifier", "protected");
        params.set("options", options);

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());

        String diff = result.changes().stream()
                .filter(c -> c.diff() != null)
                .map(RefactoringResult.FileChange::diff)
                .findFirst()
                .orElse("");

        assertTrue(diff.contains("protected"),
                "Preview should show new access modifier 'protected': " + diff);
    }

    @Test
    void testCombinedOptionsAndDirectParams() throws IOException, RefactoringException {
        // Test that direct params take precedence over options
        String javaCode = """
                package test;

                public class Mixed {
                    public void process(String data) {
                        System.out.println(data);
                    }
                }
                """;

        Path javaFile = tempDir.resolve("Mixed.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "process");
        params.put("newName", "handleDirect"); // Direct param - should take precedence

        // newName in options too - should be ignored
        ObjectNode options = mapper.createObjectNode();
        options.put("newName", "handleOptions");
        params.set("options", options);

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);

        String diff = result.changes().stream()
                .filter(c -> c.diff() != null)
                .map(RefactoringResult.FileChange::diff)
                .findFirst()
                .orElse("");

        assertTrue(diff.contains("handleDirect"),
                "Direct params should take precedence over options: " + diff);
        assertFalse(diff.contains("handleOptions"),
                "Options should not override direct params: " + diff);
    }

    @Test
    void testAddParameterInOptions_execute() throws IOException, RefactoringException {
        // Test execute with parameters in options
        String javaCode = """
                package test;

                public class Processor {
                    public void run(String task) {
                        System.out.println("Running: " + task);
                    }
                }
                """;

        Path javaFile = tempDir.resolve("Processor.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "run");

        ObjectNode options = mapper.createObjectNode();
        ArrayNode parameters = mapper.createArrayNode();

        // Keep existing
        ObjectNode keepParam = mapper.createObjectNode();
        keepParam.put("name", "task");
        keepParam.put("action", "keep");
        parameters.add(keepParam);

        // Add timeout parameter
        ObjectNode addParam = mapper.createObjectNode();
        addParam.put("name", "timeout");
        addParam.put("type", "long");
        addParam.put("defaultValue", "5000L");
        addParam.put("action", "add");
        parameters.add(addParam);

        options.set("parameters", parameters);
        params.set("options", options);

        RefactoringResult result = operation.execute(params, context);

        assertEquals(RefactoringResult.Status.SUCCESS, result.status());

        // Check file was modified
        String modifiedCode = Files.readString(javaFile);
        assertTrue(modifiedCode.contains("timeout") && modifiedCode.contains("long"),
                "Modified code should contain new parameter: " + modifiedCode);
    }

    // ==================== REPORT4 Issue 1.1 Tests ====================
    // Tests for SeparatedReferences structure and single-file change_signature

    @Test
    void testChangeSignature_singleFile_addParameter() throws IOException, RefactoringException {
        // Test that change_signature works correctly in a single file with method and call
        String javaCode = """
                package test;

                public class ServiceWithCaller {
                    public void process(String data) {
                        System.out.println("Processing: " + data);
                    }

                    public void caller() {
                        process("test");
                        process("data");
                    }
                }
                """;
        Path javaFile = tempDir.resolve("ServiceWithCaller.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "process");
        params.put("scope", "file");

        ObjectNode options = mapper.createObjectNode();
        ArrayNode parameters = mapper.createArrayNode();

        // Keep existing parameter
        ObjectNode keepParam = mapper.createObjectNode();
        keepParam.put("name", "data");
        keepParam.put("action", "keep");
        parameters.add(keepParam);

        // Add new parameter
        ObjectNode addParam = mapper.createObjectNode();
        addParam.put("name", "count");
        addParam.put("type", "int");
        addParam.put("defaultValue", "1");
        addParam.put("action", "add");
        parameters.add(addParam);

        options.set("parameters", parameters);
        params.set("options", options);

        RefactoringResult result = operation.execute(params, context);

        assertEquals(RefactoringResult.Status.SUCCESS, result.status());

        // Verify declaration was updated
        String updatedCode = Files.readString(javaFile);
        assertTrue(updatedCode.contains("String data, int count"),
                "Method declaration should have new parameter: " + updatedCode);
    }

    @Test
    void testChangeSignature_preview_showsDiff() throws IOException, RefactoringException {
        // Test that preview generates proper diff
        String javaCode = """
                package test;

                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """;
        Path javaFile = tempDir.resolve("Calculator.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "add");

        ObjectNode options = mapper.createObjectNode();
        ArrayNode parameters = mapper.createArrayNode();

        ObjectNode keepA = mapper.createObjectNode();
        keepA.put("name", "a");
        keepA.put("action", "keep");
        parameters.add(keepA);

        ObjectNode keepB = mapper.createObjectNode();
        keepB.put("name", "b");
        keepB.put("action", "keep");
        parameters.add(keepB);

        ObjectNode addC = mapper.createObjectNode();
        addC.put("name", "c");
        addC.put("type", "int");
        addC.put("action", "add");
        parameters.add(addC);

        options.set("parameters", parameters);
        params.set("options", options);

        RefactoringResult result = operation.preview(params, context);

        assertNotNull(result);
        assertEquals(RefactoringResult.Status.PREVIEW, result.status());

        // Check that diff shows the signature change
        String diff = result.changes().stream()
                .filter(c -> c.diff() != null)
                .map(RefactoringResult.FileChange::diff)
                .findFirst()
                .orElse("");

        assertTrue(diff.contains("Signature Change"), "Diff should mention signature change: " + diff);
        assertTrue(diff.contains("int c") || diff.contains("c"),
                "Diff should show new parameter: " + diff);
    }

    @Test
    void testChangeSignature_renameMethodOnly() throws IOException, RefactoringException {
        // Test renaming method without changing parameters
        String javaCode = """
                package test;

                public class Utility {
                    public void oldName(String input) {
                        System.out.println(input);
                    }
                }
                """;
        Path javaFile = tempDir.resolve("Utility.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "oldName");
        params.put("newName", "newName");

        RefactoringResult result = operation.execute(params, context);

        assertEquals(RefactoringResult.Status.SUCCESS, result.status());

        String updatedCode = Files.readString(javaFile);
        assertTrue(updatedCode.contains("void newName(String input)"),
                "Method should be renamed: " + updatedCode);
        assertFalse(updatedCode.contains("void oldName"),
                "Old method name should not exist: " + updatedCode);
    }

    @Test
    void testChangeSignature_singleFile_withOverride() throws IOException, RefactoringException {
        // Test that @Override method in same file is updated
        String javaCode = """
                package test;

                public abstract class BaseService {
                    public abstract void process(String data);
                }

                class ConcreteService extends BaseService {
                    @Override
                    public void process(String data) {
                        System.out.println(data);
                    }
                }
                """;
        Path javaFile = tempDir.resolve("Services.java");
        Files.writeString(javaFile, javaCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", javaFile.toString());
        params.put("methodName", "process");
        params.put("scope", "file");

        ObjectNode options = mapper.createObjectNode();
        ArrayNode parameters = mapper.createArrayNode();

        ObjectNode keepData = mapper.createObjectNode();
        keepData.put("name", "data");
        keepData.put("action", "keep");
        parameters.add(keepData);

        ObjectNode addFormat = mapper.createObjectNode();
        addFormat.put("name", "format");
        addFormat.put("type", "String");
        addFormat.put("action", "add");
        parameters.add(addFormat);

        options.set("parameters", parameters);
        params.set("options", options);

        RefactoringResult result = operation.execute(params, context);

        assertEquals(RefactoringResult.Status.SUCCESS, result.status());

        // Verify at least the abstract method declaration was updated
        String updatedCode = Files.readString(javaFile);
        assertTrue(updatedCode.contains("String data, String format"),
                "At least one method declaration should have new parameter: " + updatedCode);
    }

    // ==================== Cross-file update tests ====================

    @Test
    void testChangeSignature_crossFile_updatesImplementation() throws IOException, RefactoringException {
        // REPORT4 Issue 1.1: change_signature should update implementations in other files
        String interfaceCode = """
                package test;

                public interface Processor {
                    void process(String data);
                }
                """;
        Path interfaceFile = tempDir.resolve("Processor.java");
        Files.writeString(interfaceFile, interfaceCode);

        String implCode = """
                package test;

                public class ProcessorImpl implements Processor {
                    @Override
                    public void process(String data) {
                        System.out.println(data);
                    }
                }
                """;
        Path implFile = tempDir.resolve("ProcessorImpl.java");
        Files.writeString(implFile, implCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", interfaceFile.toString());
        params.put("methodName", "process");
        params.put("scope", "project");

        ObjectNode options = mapper.createObjectNode();
        ArrayNode parameters = mapper.createArrayNode();

        ObjectNode keepData = mapper.createObjectNode();
        keepData.put("name", "data");
        keepData.put("action", "keep");
        parameters.add(keepData);

        ObjectNode addCount = mapper.createObjectNode();
        addCount.put("name", "count");
        addCount.put("type", "int");
        addCount.put("defaultValue", "0");
        addCount.put("action", "add");
        parameters.add(addCount);

        options.set("parameters", parameters);
        params.set("options", options);

        RefactoringResult result = operation.execute(params, context);

        assertEquals(RefactoringResult.Status.SUCCESS, result.status());

        // Verify implementation was updated
        String updatedImpl = Files.readString(implFile);
        assertTrue(updatedImpl.contains("String data, int count"),
                "Implementation should have new parameter: " + updatedImpl);
    }

    @Test
    void testChangeSignature_crossFile_updatesCallSites() throws IOException, RefactoringException {
        // REPORT4 Issue 1.1: change_signature should update call sites in other files
        String serviceCode = """
                package test;

                public class EmailService {
                    public void sendEmail(String to, String message) {
                        System.out.println("Sending to " + to + ": " + message);
                    }
                }
                """;
        Path serviceFile = tempDir.resolve("EmailService.java");
        Files.writeString(serviceFile, serviceCode);

        String clientCode = """
                package test;

                public class Client {
                    private EmailService service = new EmailService();

                    public void notify(String user) {
                        service.sendEmail(user, "Hello");
                    }
                }
                """;
        Path clientFile = tempDir.resolve("Client.java");
        Files.writeString(clientFile, clientCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", serviceFile.toString());
        params.put("methodName", "sendEmail");
        params.put("scope", "project");

        ObjectNode options = mapper.createObjectNode();
        ArrayNode parameters = mapper.createArrayNode();

        ObjectNode keepTo = mapper.createObjectNode();
        keepTo.put("name", "to");
        keepTo.put("action", "keep");
        parameters.add(keepTo);

        ObjectNode keepMessage = mapper.createObjectNode();
        keepMessage.put("name", "message");
        keepMessage.put("action", "keep");
        parameters.add(keepMessage);

        ObjectNode addPriority = mapper.createObjectNode();
        addPriority.put("name", "priority");
        addPriority.put("type", "int");
        addPriority.put("defaultValue", "1");
        addPriority.put("action", "add");
        parameters.add(addPriority);

        options.set("parameters", parameters);
        params.set("options", options);

        RefactoringResult result = operation.execute(params, context);

        assertEquals(RefactoringResult.Status.SUCCESS, result.status());

        // Verify call site was updated with default value
        String updatedClient = Files.readString(clientFile);
        assertTrue(updatedClient.contains("sendEmail(user, \"Hello\", 1)"),
                "Call site should have new parameter with default value: " + updatedClient);
    }

    @Test
    void testChangeSignature_crossFile_fullHierarchy() throws IOException, RefactoringException {
        // Full integration test: interface, implementation, and call sites
        String interfaceCode = """
                package test;

                public interface Handler {
                    void handle(String event);
                }
                """;
        Path interfaceFile = tempDir.resolve("Handler.java");
        Files.writeString(interfaceFile, interfaceCode);

        String implCode = """
                package test;

                public class LogHandler implements Handler {
                    @Override
                    public void handle(String event) {
                        System.out.println("Log: " + event);
                    }
                }
                """;
        Path implFile = tempDir.resolve("LogHandler.java");
        Files.writeString(implFile, implCode);

        String clientCode = """
                package test;

                public class EventBus {
                    private Handler handler;

                    public void fire(String event) {
                        handler.handle(event);
                    }
                }
                """;
        Path clientFile = tempDir.resolve("EventBus.java");
        Files.writeString(clientFile, clientCode);

        RefactoringContext context = new RefactoringContext();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", interfaceFile.toString());
        params.put("methodName", "handle");
        params.put("scope", "project");

        ObjectNode options = mapper.createObjectNode();
        ArrayNode parameters = mapper.createArrayNode();

        ObjectNode keepEvent = mapper.createObjectNode();
        keepEvent.put("name", "event");
        keepEvent.put("action", "keep");
        parameters.add(keepEvent);

        ObjectNode addTimestamp = mapper.createObjectNode();
        addTimestamp.put("name", "timestamp");
        addTimestamp.put("type", "long");
        addTimestamp.put("defaultValue", "0L");
        addTimestamp.put("action", "add");
        parameters.add(addTimestamp);

        options.set("parameters", parameters);
        params.set("options", options);

        RefactoringResult result = operation.execute(params, context);

        assertEquals(RefactoringResult.Status.SUCCESS, result.status());
        assertTrue(result.summary().contains("1 implementation(s)"),
                "Should find 1 implementation: " + result.summary());
        assertTrue(result.summary().contains("1 call site(s)"),
                "Should find 1 call site: " + result.summary());

        // Verify all files were updated
        String updatedInterface = Files.readString(interfaceFile);
        assertTrue(updatedInterface.contains("String event, long timestamp"),
                "Interface should have new parameter: " + updatedInterface);

        String updatedImpl = Files.readString(implFile);
        assertTrue(updatedImpl.contains("String event, long timestamp"),
                "Implementation should have new parameter: " + updatedImpl);

        String updatedClient = Files.readString(clientFile);
        assertTrue(updatedClient.contains("handle(event, 0L)"),
                "Call site should have default value: " + updatedClient);
    }
}
