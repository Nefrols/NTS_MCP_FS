/*
 * Copyright 2025 Aristo
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
import ru.nts.tools.mcp.tools.refactoring.CodeRefactorTool;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for the EXACT bug reported by user:
 * Parameters without "action" field + newName combination.
 */
class ChangeSignatureReportBugTest {

    @TempDir
    Path tempDir;

    private ObjectMapper mapper;
    private CodeRefactorTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        tool = new CodeRefactorTool();
        PathSanitizer.setRoot(tempDir);
        TaskContext.resetAll();
        TaskContext.setForceInMemoryDb(true);
        TaskContext.setCurrent(TaskContext.getOrCreate("test"));
    }

    /**
     * EXACT case from user report:
     * - Interface with single param
     * - newName + parameters array WITHOUT action field
     * - New parameter should be added
     */
    @Test
    void test_reportedBug_newNameWithParametersWithoutAction() throws Exception {
        String code = """
                package test;
                public interface IService {
                    void run(String task);
                }
                """;

        Path file = tempDir.resolve("IService.java");
        Files.writeString(file, code);

        // EXACT request format from user report - NO action field!
        ObjectNode params = mapper.createObjectNode();
        params.put("action", "change_signature");
        params.put("path", file.toString());
        params.put("symbol", "run");
        params.put("newName", "execute");
        params.put("preview", false);

        ObjectNode options = mapper.createObjectNode();
        ArrayNode parameters = mapper.createArrayNode();

        // NO ACTION FIELD - just name and type
        ObjectNode taskParam = mapper.createObjectNode();
        taskParam.put("name", "task");
        taskParam.put("type", "String");
        parameters.add(taskParam);

        ObjectNode priorityParam = mapper.createObjectNode();
        priorityParam.put("name", "priority");
        priorityParam.put("type", "int");
        parameters.add(priorityParam);

        options.set("parameters", parameters);
        params.set("options", options);

        System.out.println("=== REQUEST ===");
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(params));

        tool.execute(params);

        String result = Files.readString(file);
        System.out.println("\n=== RESULT ===");
        System.out.println(result);

        // Must have BOTH: renamed method AND new parameter
        assertTrue(result.contains("void execute(String task, int priority)"),
                "Expected 'void execute(String task, int priority)' but got:\n" + result);
    }

    /**
     * Same bug but with call sites - default values must be added
     */
    @Test
    void test_reportedBug_callSitesGetDefaultValues() throws Exception {
        String code = """
                package test;
                public class Client {
                    private IService service;
                    public void doWork() {
                        service.run("task1");
                    }
                }
                interface IService {
                    void run(String task);
                }
                """;

        Path file = tempDir.resolve("Client.java");
        Files.writeString(file, code);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "change_signature");
        params.put("path", file.toString());
        params.put("symbol", "run");
        params.put("newName", "execute");
        params.put("preview", false);

        ObjectNode options = mapper.createObjectNode();
        ArrayNode parameters = mapper.createArrayNode();

        // Keep existing param
        ObjectNode taskParam = mapper.createObjectNode();
        taskParam.put("name", "task");
        taskParam.put("type", "String");
        parameters.add(taskParam);

        // Add new param with default value
        ObjectNode priorityParam = mapper.createObjectNode();
        priorityParam.put("name", "priority");
        priorityParam.put("type", "int");
        priorityParam.put("defaultValue", "0");
        parameters.add(priorityParam);

        options.set("parameters", parameters);
        params.set("options", options);

        tool.execute(params);

        String result = Files.readString(file);
        System.out.println("=== RESULT ===");
        System.out.println(result);

        // Interface should be updated
        assertTrue(result.contains("void execute(String task, int priority)"),
                "Interface should have new signature. Got:\n" + result);

        // Call site should have default value
        assertTrue(result.contains("service.execute(\"task1\", 0)"),
                "Call site should be: service.execute(\"task1\", 0). Got:\n" + result);
    }

    /**
     * Parameters without action but parameter exists - should keep it
     */
    @Test
    void test_parametersWithoutAction_existingParam() throws Exception {
        String code = """
                public class A {
                    public void foo(String x) {}
                }
                """;

        Path file = tempDir.resolve("A.java");
        Files.writeString(file, code);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "change_signature");
        params.put("path", file.toString());
        params.put("methodName", "foo");
        params.put("preview", false);

        ObjectNode options = mapper.createObjectNode();
        ArrayNode parameters = mapper.createArrayNode();

        // No action - just the param spec
        ObjectNode xParam = mapper.createObjectNode();
        xParam.put("name", "x");
        xParam.put("type", "String");
        parameters.add(xParam);

        // New param without action
        ObjectNode yParam = mapper.createObjectNode();
        yParam.put("name", "y");
        yParam.put("type", "int");
        parameters.add(yParam);

        options.set("parameters", parameters);
        params.set("options", options);

        tool.execute(params);

        String result = Files.readString(file);

        assertTrue(result.contains("String x, int y"),
                "Should have both params. Got:\n" + result);
    }
}
