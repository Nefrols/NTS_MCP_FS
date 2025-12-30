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
import ru.nts.tools.mcp.core.SessionContext;
import ru.nts.tools.mcp.tools.refactoring.CodeRefactorTool;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for change_signature covering ALL scenarios.
 */
class ChangeSignatureComprehensiveTest {

    @TempDir
    Path tempDir;

    private ObjectMapper mapper;
    private CodeRefactorTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        tool = new CodeRefactorTool();
        PathSanitizer.setRoot(tempDir);
        SessionContext.resetAll();
        SessionContext.setCurrent(SessionContext.getOrCreate("test"));
    }

    // ==================== ADD PARAMETER ====================

    @Test
    void test_addParameter_toMethodWithParams() throws Exception {
        String code = """
                public class A {
                    public void foo(String x) {}
                }
                """;
        Path file = writeFile("A.java", code);

        ObjectNode params = buildParams(file, "foo",
                keep("x"),
                add("y", "int", "0"));

        tool.execute(params);
        String result = Files.readString(file);

        assertTrue(result.contains("String x, int y"), "Expected 'String x, int y', got:\n" + result);
    }

    @Test
    void test_addParameter_toMethodWithoutParams() throws Exception {
        String code = """
                public class A {
                    public void foo() {}
                    public void caller() { foo(); }
                }
                """;
        Path file = writeFile("A.java", code);

        ObjectNode params = buildParams(file, "foo",
                add("x", "String", "null"));

        tool.execute(params);
        String result = Files.readString(file);

        assertTrue(result.contains("foo(String x)"), "Method should have param. Got:\n" + result);
        assertTrue(result.contains("foo(null)"), "Call should have default. Got:\n" + result);
    }

    @Test
    void test_addMultipleParameters() throws Exception {
        String code = """
                public class A {
                    public void foo(String a) {}
                }
                """;
        Path file = writeFile("A.java", code);

        ObjectNode params = buildParams(file, "foo",
                keep("a"),
                add("b", "int", "1"),
                add("c", "boolean", "true"));

        tool.execute(params);
        String result = Files.readString(file);

        assertTrue(result.contains("String a, int b, boolean c"),
                "Expected 3 params. Got:\n" + result);
    }

    // ==================== REMOVE PARAMETER ====================

    @Test
    void test_removeParameter_single() throws Exception {
        String code = """
                public class A {
                    public void foo(String x, int y) {}
                    public void caller() { foo("a", 1); }
                }
                """;
        Path file = writeFile("A.java", code);

        ObjectNode params = buildParams(file, "foo",
                keep("x"),
                remove("y"));

        tool.execute(params);
        String result = Files.readString(file);

        assertTrue(result.contains("foo(String x)"), "Should have only x. Got:\n" + result);
        assertTrue(result.contains("foo(\"a\")"), "Call should have only first arg. Got:\n" + result);
    }

    @Test
    void test_removeAllParameters() throws Exception {
        String code = """
                public class A {
                    public void foo(String x, int y) {}
                }
                """;
        Path file = writeFile("A.java", code);

        ObjectNode params = buildParams(file, "foo",
                remove("x"),
                remove("y"));

        tool.execute(params);
        String result = Files.readString(file);

        assertTrue(result.contains("foo()"), "Should have no params. Got:\n" + result);
    }

    // ==================== RENAME PARAMETER ====================

    @Test
    void test_renameParameter() throws Exception {
        String code = """
                public class A {
                    public void foo(String oldName) {
                        System.out.println(oldName);
                    }
                }
                """;
        Path file = writeFile("A.java", code);

        ObjectNode params = buildParams(file, "foo",
                rename("oldName", "newName"));

        tool.execute(params);
        String result = Files.readString(file);

        assertTrue(result.contains("String newName"), "Param should be renamed. Got:\n" + result);
    }

    // ==================== RETYPE PARAMETER ====================

    @Test
    void test_retypeParameter() throws Exception {
        String code = """
                public class A {
                    public void foo(String x) {}
                }
                """;
        Path file = writeFile("A.java", code);

        ObjectNode params = buildParams(file, "foo",
                retype("x", "Object"));

        tool.execute(params);
        String result = Files.readString(file);

        assertTrue(result.contains("Object x"), "Type should change. Got:\n" + result);
    }

    // ==================== REORDER PARAMETERS ====================

    @Test
    void test_reorderParameters() throws Exception {
        String code = """
                public class A {
                    public void foo(String a, int b, boolean c) {}
                    public void caller() { foo("x", 1, true); }
                }
                """;
        Path file = writeFile("A.java", code);

        // Reorder: c, a, b
        ObjectNode params = buildParamsReorder(file, "foo",
                keepAt("c", 0),
                keepAt("a", 1),
                keepAt("b", 2));

        tool.execute(params);
        String result = Files.readString(file);

        assertTrue(result.contains("boolean c, String a, int b"),
                "Should reorder. Got:\n" + result);
        assertTrue(result.contains("foo(true, \"x\", 1)"),
                "Call args should reorder. Got:\n" + result);
    }

    // ==================== COMBINED OPERATIONS ====================

    @Test
    void test_addAndRemove() throws Exception {
        String code = """
                public class A {
                    public void foo(String x, int y) {}
                }
                """;
        Path file = writeFile("A.java", code);

        ObjectNode params = buildParams(file, "foo",
                keep("x"),
                remove("y"),
                add("z", "double", "0.0"));

        tool.execute(params);
        String result = Files.readString(file);

        assertTrue(result.contains("String x, double z"),
                "Should have x and z. Got:\n" + result);
    }

    @Test
    void test_renameAndAdd() throws Exception {
        String code = """
                public class A {
                    public void foo(String x) {}
                }
                """;
        Path file = writeFile("A.java", code);

        ObjectNode params = buildParams(file, "foo",
                rename("x", "input"),
                add("count", "int", "1"));

        tool.execute(params);
        String result = Files.readString(file);

        assertTrue(result.contains("String input, int count"),
                "Should rename and add. Got:\n" + result);
    }

    // ==================== METHOD NAME CHANGE ====================

    @Test
    void test_changeMethodName_only() throws Exception {
        String code = """
                public class A {
                    public void oldMethod(String x) {}
                    public void caller() { oldMethod("a"); }
                }
                """;
        Path file = writeFile("A.java", code);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "change_signature");
        params.put("path", file.toString());
        params.put("methodName", "oldMethod");
        params.put("newName", "newMethod");
        params.put("preview", false);

        tool.execute(params);
        String result = Files.readString(file);

        assertTrue(result.contains("newMethod(String x)"), "Name should change. Got:\n" + result);
        assertTrue(result.contains("newMethod(\"a\")"), "Call should update. Got:\n" + result);
    }

    @Test
    void test_changeMethodName_withParams() throws Exception {
        String code = """
                public class A {
                    public void oldMethod(String x) {}
                }
                """;
        Path file = writeFile("A.java", code);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "change_signature");
        params.put("path", file.toString());
        params.put("methodName", "oldMethod");
        params.put("newName", "newMethod");
        params.put("preview", false);

        ObjectNode options = mapper.createObjectNode();
        ArrayNode parameters = mapper.createArrayNode();
        parameters.add(keep("x"));
        parameters.add(add("y", "int", "0"));
        options.set("parameters", parameters);
        params.set("options", options);

        tool.execute(params);
        String result = Files.readString(file);

        assertTrue(result.contains("newMethod(String x, int y)"),
                "Name and params should change. Got:\n" + result);
    }

    // ==================== CROSS-FILE ====================

    @Test
    void test_crossFile_interface_impl_client() throws Exception {
        Path iface = writeFile("IProcessor.java", """
                public interface IProcessor {
                    void process(String data);
                }
                """);

        Path impl = writeFile("ProcessorImpl.java", """
                public class ProcessorImpl implements IProcessor {
                    @Override
                    public void process(String data) {
                        System.out.println(data);
                    }
                }
                """);

        Path client = writeFile("Client.java", """
                public class Client {
                    private IProcessor p;
                    public void run() { p.process("test"); }
                }
                """);

        ObjectNode params = buildParams(iface, "process",
                keep("data"),
                add("count", "int", "0"));
        params.put("scope", "project");

        tool.execute(params);

        String ifaceResult = Files.readString(iface);
        String implResult = Files.readString(impl);
        String clientResult = Files.readString(client);

        assertTrue(ifaceResult.contains("String data, int count"),
                "Interface should update. Got:\n" + ifaceResult);
        assertTrue(implResult.contains("String data, int count"),
                "Impl should update. Got:\n" + implResult);
        assertTrue(clientResult.contains("process(\"test\", 0)"),
                "Client call should update. Got:\n" + clientResult);
    }

    // ==================== EDGE CASES ====================

    @Test
    void test_methodWithGenerics() throws Exception {
        String code = """
                public class A {
                    public <T> void foo(T item) {}
                }
                """;
        Path file = writeFile("A.java", code);

        ObjectNode params = buildParams(file, "foo",
                keep("item"),
                add("count", "int", "1"));

        tool.execute(params);
        String result = Files.readString(file);

        assertTrue(result.contains("T item, int count"),
                "Generic method should work. Got:\n" + result);
    }

    @Test
    void test_methodWithVarargs() throws Exception {
        String code = """
                public class A {
                    public void foo(String... args) {}
                }
                """;
        Path file = writeFile("A.java", code);

        ObjectNode params = buildParams(file, "foo",
                add("prefix", "String", "\"\""),
                keep("args"));

        tool.execute(params);
        String result = Files.readString(file);

        assertTrue(result.contains("String prefix, String... args"),
                "Varargs should work. Got:\n" + result);
    }

    @Test
    void test_staticMethod() throws Exception {
        String code = """
                public class A {
                    public static void foo(String x) {}
                    public void caller() { A.foo("test"); }
                }
                """;
        Path file = writeFile("A.java", code);

        ObjectNode params = buildParams(file, "foo",
                keep("x"),
                add("y", "int", "0"));

        tool.execute(params);
        String result = Files.readString(file);

        assertTrue(result.contains("static void foo(String x, int y)"),
                "Static method should work. Got:\n" + result);
        assertTrue(result.contains("A.foo(\"test\", 0)"),
                "Static call should update. Got:\n" + result);
    }

    @Test
    void test_constructorLikeMethod() throws Exception {
        // Method that looks like constructor (same name as would-be class)
        String code = """
                public class Builder {
                    public Builder create(String name) { return this; }
                }
                """;
        Path file = writeFile("Builder.java", code);

        ObjectNode params = buildParams(file, "create",
                keep("name"),
                add("id", "int", "0"));

        tool.execute(params);
        String result = Files.readString(file);

        assertTrue(result.contains("create(String name, int id)"),
                "Should work. Got:\n" + result);
    }

    // ==================== HELPERS ====================

    private Path writeFile(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private ObjectNode buildParams(Path file, String methodName, ObjectNode... paramDefs) {
        ObjectNode params = mapper.createObjectNode();
        params.put("action", "change_signature");
        params.put("path", file.toString());
        params.put("methodName", methodName);
        params.put("preview", false);

        ObjectNode options = mapper.createObjectNode();
        ArrayNode parameters = mapper.createArrayNode();
        for (ObjectNode p : paramDefs) {
            parameters.add(p);
        }
        options.set("parameters", parameters);
        params.set("options", options);

        return params;
    }

    private ObjectNode buildParamsReorder(Path file, String methodName, ObjectNode... paramDefs) {
        // Same as buildParams but for reorder tests
        return buildParams(file, methodName, paramDefs);
    }

    private ObjectNode keep(String name) {
        ObjectNode p = mapper.createObjectNode();
        p.put("name", name);
        p.put("action", "keep");
        return p;
    }

    private ObjectNode keepAt(String name, int position) {
        ObjectNode p = mapper.createObjectNode();
        p.put("name", name);
        p.put("action", "keep");
        p.put("position", position);
        return p;
    }

    private ObjectNode add(String name, String type, String defaultValue) {
        ObjectNode p = mapper.createObjectNode();
        p.put("name", name);
        p.put("type", type);
        p.put("defaultValue", defaultValue);
        p.put("action", "add");
        return p;
    }

    private ObjectNode remove(String name) {
        ObjectNode p = mapper.createObjectNode();
        p.put("name", name);
        p.put("action", "remove");
        return p;
    }

    private ObjectNode rename(String oldName, String newName) {
        ObjectNode p = mapper.createObjectNode();
        p.put("name", oldName);
        p.put("newName", newName);
        p.put("action", "rename");
        return p;
    }

    private ObjectNode retype(String name, String newType) {
        ObjectNode p = mapper.createObjectNode();
        p.put("name", name);
        p.put("type", newType);
        p.put("action", "retype");
        return p;
    }
}
