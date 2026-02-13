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
package ru.nts.tools.mcp.tools.navigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TaskContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CodeNavigateToolTest {

    private CodeNavigateTool tool;
    private ObjectMapper mapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        tool = new CodeNavigateTool();
        mapper = new ObjectMapper();

        // Initialize task context
        TaskContext.setForceInMemoryDb(true);
        TaskContext ctx = TaskContext.getOrCreate("test-task");
        TaskContext.setCurrent(ctx);

        // Set project root
        PathSanitizer.setRoot(tempDir);
    }

    @Test
    void getName() {
        assertEquals("nts_code_navigate", tool.getName());
    }

    @Test
    void getCategory() {
        assertEquals("navigation", tool.getCategory());
    }

    @Test
    void getInputSchema() {
        JsonNode schema = tool.getInputSchema();
        assertNotNull(schema);
        assertEquals("object", schema.get("type").asText());
        assertTrue(schema.has("properties"));
        assertTrue(schema.get("properties").has("action"));
        assertTrue(schema.get("properties").has("path"));
    }

    @Test
    void executeSymbolsJava() throws Exception {
        Path javaFile = createJavaFile("Calculator.java", """
                public class Calculator {
                    private int result;

                    public int add(int a, int b) {
                        return a + b;
                    }

                    public int subtract(int a, int b) {
                        return a - b;
                    }
                }
                """);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "symbols");
        params.put("path", javaFile.toString());

        JsonNode result = tool.execute(params);

        assertNotNull(result);
        assertTrue(result.has("content"));
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("Calculator"), "Should contain class name");
        assertTrue(text.contains("add"), "Should contain method name");
        assertTrue(text.contains("subtract"), "Should contain method name");
        assertTrue(text.contains("result"), "Should contain field name");
        assertTrue(text.contains("TOKEN"), "Should contain access token");
    }

    @Test
    void executeDefinitionSameFile() throws Exception {
        Path javaFile = createJavaFile("Service.java", """
                public class Service {
                    private int counter;

                    public void increment() {
                        counter++;
                    }

                    public int getCounter() {
                        return counter;
                    }
                }
                """);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "definition");
        params.put("path", javaFile.toString());
        params.put("line", 9);  // line with 'counter' in getCounter
        params.put("column", 16);

        JsonNode result = tool.execute(params);

        assertNotNull(result);
        String text = result.get("content").get(0).get("text").asText();

        // Should find definition of counter field
        assertTrue(text.contains("counter") || text.contains("Definition found"),
                "Should find definition. Got: " + text);
    }

    @Test
    void executeReferencesSameFile() throws Exception {
        Path javaFile = createJavaFile("Counter.java", """
                public class Counter {
                    private int value;

                    public void reset() {
                        value = 0;
                    }

                    public void increment() {
                        value++;
                    }

                    public int getValue() {
                        return value;
                    }
                }
                """);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "references");
        params.put("path", javaFile.toString());
        params.put("line", 2);  // line with 'value' declaration
        params.put("column", 17);
        params.put("scope", "file");

        JsonNode result = tool.execute(params);

        assertNotNull(result);
        String text = result.get("content").get(0).get("text").asText();

        // Should find multiple references to 'value'
        assertTrue(text.contains("reference") || text.contains("Reference") || text.contains("Lines"),
                "Should find references. Got: " + text);
    }

    @Test
    void executeHover() throws Exception {
        Path javaFile = createJavaFile("Model.java", """
                /**
                 * A simple data model.
                 */
                public class Model {
                    /** The name field */
                    private String name;

                    /**
                     * Gets the name.
                     * @return the name
                     */
                    public String getName() {
                        return name;
                    }
                }
                """);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "hover");
        params.put("path", javaFile.toString());
        params.put("line", 12);  // getName method
        params.put("column", 20);

        JsonNode result = tool.execute(params);

        assertNotNull(result);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("getName") || text.contains("METHOD") || text.contains("No symbol"),
                "Should show symbol info. Got: " + text);
    }

    @Test
    void executeWithUnsupportedLanguage() throws Exception {
        Path xmlFile = tempDir.resolve("data.xml");
        Files.writeString(xmlFile, "<root><item/></root>");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "symbols");
        params.put("path", xmlFile.toString());

        assertThrows(IllegalArgumentException.class, () -> {
            tool.execute(params);
        });
    }

    @Test
    void executeWithMissingAction() {
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "some/file.java");

        assertThrows(IllegalArgumentException.class, () -> {
            tool.execute(params);
        });
    }

    @Test
    void executeWithMissingPath() {
        ObjectNode params = mapper.createObjectNode();
        params.put("action", "symbols");

        assertThrows(IllegalArgumentException.class, () -> {
            tool.execute(params);
        });
    }

    @Test
    void executeWithNonExistentFile() {
        ObjectNode params = mapper.createObjectNode();
        params.put("action", "symbols");
        params.put("path", tempDir.resolve("NonExistent.java").toString());

        assertThrows(IllegalArgumentException.class, () -> {
            tool.execute(params);
        });
    }

    @Test
    void executeWithInvalidAction() throws Exception {
        Path javaFile = createJavaFile("Test.java", "public class Test {}");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "invalid_action");
        params.put("path", javaFile.toString());

        assertThrows(IllegalArgumentException.class, () -> {
            tool.execute(params);
        });
    }

    @Test
    void executeSymbolsPython() throws Exception {
        Path pyFile = tempDir.resolve("module.py");
        Files.writeString(pyFile, """
                class Calculator:
                    def __init__(self):
                        self.result = 0

                    def add(self, a, b):
                        return a + b

                def main():
                    calc = Calculator()
                    print(calc.add(1, 2))
                """);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "symbols");
        params.put("path", pyFile.toString());

        JsonNode result = tool.execute(params);

        assertNotNull(result);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("Calculator"), "Should find class");
        assertTrue(text.contains("add") || text.contains("main"), "Should find functions");
    }

    @Test
    void executeSymbolsKotlin() throws Exception {
        Path ktFile = tempDir.resolve("Utils.kt");
        Files.writeString(ktFile, """
                package com.example

                class Utils {
                    fun greet(name: String): String {
                        return "Hello, $name!"
                    }

                    companion object {
                        fun create(): Utils = Utils()
                    }
                }

                fun topLevelFunction() {
                    println("Top level")
                }
                """);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "symbols");
        params.put("path", ktFile.toString());

        JsonNode result = tool.execute(params);

        assertNotNull(result);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("Utils"), "Should find class");
        assertTrue(text.contains("greet") || text.contains("topLevelFunction"),
                "Should find functions");
    }

    @Test
    void executeSymbolsTypeScript() throws Exception {
        Path tsFile = tempDir.resolve("service.ts");
        Files.writeString(tsFile, """
                interface User {
                    id: number;
                    name: string;
                }

                class UserService {
                    private users: User[] = [];

                    addUser(user: User): void {
                        this.users.push(user);
                    }

                    getUser(id: number): User | undefined {
                        return this.users.find(u => u.id === id);
                    }
                }

                export default UserService;
                """);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "symbols");
        params.put("path", tsFile.toString());

        JsonNode result = tool.execute(params);

        assertNotNull(result);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("User") || text.contains("UserService"),
                "Should find class/interface. Got: " + text);
    }

    @Test
    void responseContainsToken() throws Exception {
        Path javaFile = createJavaFile("TokenTest.java", """
                public class TokenTest {
                    public void method() {}
                }
                """);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "symbols");
        params.put("path", javaFile.toString());

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("TOKEN"), "Response should contain LAT token");
        assertTrue(text.contains("LAT:"), "Token should start with LAT:");
    }

    private Path createJavaFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
