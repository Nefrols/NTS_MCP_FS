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
package ru.nts.tools.mcp.tools.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.nts.tools.mcp.core.McpRouter;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.tools.editing.EditFileTool;
import ru.nts.tools.mcp.tools.editing.ProjectReplaceTool;
import ru.nts.tools.mcp.tools.external.GitCombinedTool;
import ru.nts.tools.mcp.tools.external.GradleTool;
import ru.nts.tools.mcp.tools.fs.CompareFilesTool;
import ru.nts.tools.mcp.tools.fs.FileManageTool;
import ru.nts.tools.mcp.tools.fs.FileReadTool;
import ru.nts.tools.mcp.tools.fs.FileSearchTool;
import ru.nts.tools.mcp.tools.navigation.CodeNavigateTool;
import ru.nts.tools.mcp.tools.planning.TodoTool;
import ru.nts.tools.mcp.tools.refactoring.CodeRefactorTool;
import ru.nts.tools.mcp.tools.session.InitTool;
import ru.nts.tools.mcp.tools.session.SessionTool;
import ru.nts.tools.mcp.tools.system.BatchToolsTool;
import ru.nts.tools.mcp.tools.system.TaskTool;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Валидация JSON Schema для всех MCP инструментов.
 * Проверяет соответствие схем спецификации JSON Schema Draft-07.
 */
class ToolSchemaValidationTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static JsonSchemaFactory schemaFactory;
    private static List<McpTool> allTools;

    /**
     * JSON Schema meta-schema для валидации схем инструментов.
     * Используем Draft-07, который является стандартом для MCP.
     */
    private static final String META_SCHEMA = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "required": ["type"],
              "properties": {
                "type": { "type": "string", "enum": ["object", "array", "string", "integer", "number", "boolean", "null"] },
                "properties": { "type": "object" },
                "required": { "type": "array", "items": { "type": "string" } },
                "items": { "type": "object" },
                "description": { "type": "string" },
                "enum": { "type": "array" },
                "default": {}
              }
            }
            """;

    @BeforeAll
    static void setup() {
        schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

        // Создаем все инструменты
        McpRouter router = new McpRouter(mapper);
        allTools = new ArrayList<>();

        allTools.add(new InitTool());
        allTools.add(new FileReadTool());
        allTools.add(new FileManageTool());
        allTools.add(new FileSearchTool());
        allTools.add(new EditFileTool());
        allTools.add(new CompareFilesTool());
        allTools.add(new SessionTool());
        allTools.add(new GradleTool());
        allTools.add(new GitCombinedTool());
        allTools.add(new BatchToolsTool(router));
        allTools.add(new TaskTool());
        allTools.add(new ProjectReplaceTool());
        allTools.add(new TodoTool());
        allTools.add(new CodeNavigateTool());
        allTools.add(new CodeRefactorTool());
    }

    /**
     * Провайдер инструментов для параметризованных тестов.
     */
    static Stream<McpTool> toolProvider() {
        setup();
        return allTools.stream();
    }

    /**
     * Проверяет, что схема инструмента является валидным JSON.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("toolProvider")
    void schemaIsValidJson(McpTool tool) {
        JsonNode schema = tool.getInputSchema();
        assertNotNull(schema, "Schema should not be null for tool: " + tool.getName());
        assertTrue(schema.isObject(), "Schema should be an object for tool: " + tool.getName());
    }

    /**
     * Проверяет, что схема имеет обязательное поле type: "object".
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("toolProvider")
    void schemaHasTypeObject(McpTool tool) {
        JsonNode schema = tool.getInputSchema();
        assertTrue(schema.has("type"), "Schema must have 'type' field for tool: " + tool.getName());
        assertEquals("object", schema.get("type").asText(),
                "Schema type must be 'object' for tool: " + tool.getName());
    }

    /**
     * Проверяет, что все массивы в схеме имеют свойство items.
     * Это критически важно для соответствия JSON Schema спецификации.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("toolProvider")
    void allArraysHaveItems(McpTool tool) {
        JsonNode schema = tool.getInputSchema();
        List<String> violations = new ArrayList<>();
        validateArraysRecursively(schema, "", violations);

        assertTrue(violations.isEmpty(),
                "Tool '" + tool.getName() + "' has arrays without 'items' property:\n" +
                        String.join("\n", violations));
    }

    /**
     * Рекурсивно проверяет все массивы на наличие items.
     */
    private void validateArraysRecursively(JsonNode node, String path, List<String> violations) {
        if (node == null || !node.isObject()) {
            return;
        }

        // Проверяем текущий узел
        if (node.has("type") && "array".equals(node.get("type").asText())) {
            if (!node.has("items")) {
                violations.add("  - " + (path.isEmpty() ? "root" : path) + ": type=array but no 'items' defined");
            }
        }

        // Рекурсивно проверяем properties
        if (node.has("properties")) {
            JsonNode props = node.get("properties");
            Iterator<String> fieldNames = props.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                String newPath = path.isEmpty() ? fieldName : path + "." + fieldName;
                validateArraysRecursively(props.get(fieldName), newPath, violations);
            }
        }

        // Проверяем items (для вложенных массивов)
        if (node.has("items")) {
            validateArraysRecursively(node.get("items"), path + ".items", violations);
        }
    }

    /**
     * Проверяет, что все типы в схеме являются валидными JSON Schema типами.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("toolProvider")
    void allTypesAreValid(McpTool tool) {
        JsonNode schema = tool.getInputSchema();
        Set<String> validTypes = Set.of("object", "array", "string", "integer", "number", "boolean", "null");
        List<String> violations = new ArrayList<>();

        validateTypesRecursively(schema, "", validTypes, violations);

        assertTrue(violations.isEmpty(),
                "Tool '" + tool.getName() + "' has invalid types:\n" +
                        String.join("\n", violations));
    }

    /**
     * Рекурсивно проверяет все типы в схеме.
     */
    private void validateTypesRecursively(JsonNode node, String path, Set<String> validTypes, List<String> violations) {
        if (node == null || !node.isObject()) {
            return;
        }

        // Проверяем тип текущего узла
        if (node.has("type")) {
            String type = node.get("type").asText();
            if (!validTypes.contains(type)) {
                violations.add("  - " + (path.isEmpty() ? "root" : path) + ": invalid type '" + type + "'");
            }
        }

        // Рекурсивно проверяем properties
        if (node.has("properties")) {
            JsonNode props = node.get("properties");
            Iterator<String> fieldNames = props.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                String newPath = path.isEmpty() ? fieldName : path + "." + fieldName;
                validateTypesRecursively(props.get(fieldName), newPath, validTypes, violations);
            }
        }

        // Проверяем items
        if (node.has("items")) {
            validateTypesRecursively(node.get("items"), path + ".items", validTypes, violations);
        }
    }

    /**
     * Проверяет, что required содержит только существующие properties.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("toolProvider")
    void requiredFieldsExistInProperties(McpTool tool) {
        JsonNode schema = tool.getInputSchema();

        if (!schema.has("required")) {
            return; // Нет обязательных полей - ОК
        }

        JsonNode required = schema.get("required");
        assertTrue(required.isArray(), "'required' must be an array for tool: " + tool.getName());

        JsonNode properties = schema.get("properties");
        if (properties == null) {
            assertTrue(required.isEmpty(),
                    "Tool '" + tool.getName() + "' has 'required' but no 'properties'");
            return;
        }

        List<String> missingProps = new ArrayList<>();
        for (JsonNode reqField : required) {
            String fieldName = reqField.asText();
            if (!properties.has(fieldName)) {
                missingProps.add(fieldName);
            }
        }

        assertTrue(missingProps.isEmpty(),
                "Tool '" + tool.getName() + "' has required fields not in properties: " + missingProps);
    }

    /**
     * Проверяет, что схема соответствует JSON Schema Draft-07 мета-схеме.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("toolProvider")
    void schemaIsValidAgainstMetaSchema(McpTool tool) throws Exception {
        JsonNode schema = tool.getInputSchema();
        JsonNode metaSchema = mapper.readTree(META_SCHEMA);

        JsonSchema validator = schemaFactory.getSchema(metaSchema);
        Set<ValidationMessage> errors = validator.validate(schema);

        assertTrue(errors.isEmpty(),
                "Tool '" + tool.getName() + "' schema validation errors:\n" +
                        errors.stream()
                                .map(ValidationMessage::getMessage)
                                .reduce("", (a, b) -> a + "\n  - " + b));
    }

    /**
     * Проверяет, что все инструменты имеют непустое описание.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("toolProvider")
    void toolHasDescription(McpTool tool) {
        String description = tool.getDescription();
        assertNotNull(description, "Tool '" + tool.getName() + "' must have a description");
        assertFalse(description.isBlank(), "Tool '" + tool.getName() + "' description must not be blank");
    }

    /**
     * Проверяет, что все инструменты имеют категорию.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("toolProvider")
    void toolHasCategory(McpTool tool) {
        String category = tool.getCategory();
        assertNotNull(category, "Tool '" + tool.getName() + "' must have a category");
        assertFalse(category.isBlank(), "Tool '" + tool.getName() + "' category must not be blank");
    }

    /**
     * Сводный тест - выводит информацию обо всех схемах.
     */
    @Test
    void printAllSchemasSummary() throws Exception {
        System.out.println("\n=== MCP Tools Schema Summary ===\n");

        for (McpTool tool : allTools) {
            JsonNode schema = tool.getInputSchema();
            int propsCount = schema.has("properties") ? schema.get("properties").size() : 0;
            int requiredCount = schema.has("required") ? schema.get("required").size() : 0;

            // Подсчет массивов
            int arrayCount = countArrays(schema);

            System.out.printf("%-25s | Props: %2d | Required: %2d | Arrays: %d | Category: %s%n",
                    tool.getName(), propsCount, requiredCount, arrayCount, tool.getCategory());
        }

        System.out.println("\nTotal tools: " + allTools.size());
    }

    /**
     * Подсчитывает количество массивов в схеме.
     */
    private int countArrays(JsonNode node) {
        if (node == null || !node.isObject()) {
            return 0;
        }

        int count = 0;
        if (node.has("type") && "array".equals(node.get("type").asText())) {
            count++;
        }

        if (node.has("properties")) {
            JsonNode props = node.get("properties");
            Iterator<String> fieldNames = props.fieldNames();
            while (fieldNames.hasNext()) {
                count += countArrays(props.get(fieldNames.next()));
            }
        }

        if (node.has("items")) {
            count += countArrays(node.get("items"));
        }

        return count;
    }
}
