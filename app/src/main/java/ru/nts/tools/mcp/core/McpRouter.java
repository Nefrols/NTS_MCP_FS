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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Роутер для управления инструментами (Tools) и маршрутизации запросов от MCP клиента.
 * Реализует реестр инструментов и предоставляет интерфейс для их динамического вызова.
 */
public class McpRouter {

    /**
     * Карта зарегистрированных инструментов, где ключ — имя инструмента.
     */
    private final Map<String, McpTool> tools = new HashMap<>();

    /**
     * Объект для манипуляции JSON узлами при формировании списков инструментов.
     */
    private final ObjectMapper mapper;

    /**
     * Создает новый роутер с привязкой к ObjectMapper.
     *
     * @param mapper Объект ObjectMapper, используемый для работы с JSON.
     */
    public McpRouter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Регистрирует новый инструмент в реестре сервера.
     * После регистрации инструмент становится доступен для вызова через 'tools/call'.
     *
     * @param tool Объект реализации инструмента.
     */
    public void registerTool(McpTool tool) {
        tools.put(tool.getName(), tool);
    }

    /**
     * Формирует список всех доступных инструментов в формате, соответствующем протоколу MCP.
     * Используется для ответа на запрос 'tools/list'.
     *
     * Для инструментов, требующих задачу (requiresTask=true), автоматически добавляется
     * обязательный параметр taskId в inputSchema. Это позволяет агентам передавать
     * taskId напрямую в аргументах вызова, а не только через _meta.
     *
     * @return JsonNode, содержащий массив объектов с описанием имен, описаний и схем параметров инструментов.
     */
    public JsonNode listTools() {
        return listTools(false);
    }

    /**
     * Формирует список инструментов с возможностью включения внутренних.
     * Оркестратор (доверенный клиент) передает includeInternal=true,
     * чтобы получить finish-сигналы и другие внутренние инструменты.
     *
     * @param includeInternal если true, включает internal-инструменты в список
     */
    public JsonNode listTools(boolean includeInternal) {
        ArrayNode toolsArray = mapper.createArrayNode();
        for (McpTool tool : tools.values()) {
            // Внутренние инструменты (finish-сигналы, вопросы архитектора, вызов скаута)
            // не отдаются внешним MCP-клиентам, но доступны оркестратору
            if (!includeInternal && tool.isInternal()) continue;

            ObjectNode toolNode = mapper.createObjectNode();
            toolNode.put("name", tool.getName());
            // Добавляем префикс категории в описание для лучшей визуализации в UI клиентов
            String description = "[" + tool.getCategory().toUpperCase() + "] " + tool.getDescription();
            toolNode.put("description", description);
            toolNode.put("category", tool.getCategory());

            // Получаем базовую схему инструмента
            JsonNode baseSchema = tool.getInputSchema();

            // Для инструментов, требующих задачу, добавляем taskId в схему
            if (tool.requiresTask() && baseSchema instanceof ObjectNode schemaNode) {
                ObjectNode enrichedSchema = schemaNode.deepCopy();

                // Добавляем taskId в properties
                ObjectNode properties = (ObjectNode) enrichedSchema.get("properties");
                if (properties == null) {
                    properties = mapper.createObjectNode();
                    enrichedSchema.set("properties", properties);
                }

                ObjectNode taskIdProp = mapper.createObjectNode();
                taskIdProp.put("type", "string");
                taskIdProp.put("description", "Task UUID obtained from nts_init. Required for all tools except nts_init.");
                properties.set("taskId", taskIdProp);

                // Добавляем taskId в required
                ArrayNode required = (ArrayNode) enrichedSchema.get("required");
                if (required == null) {
                    required = mapper.createArrayNode();
                    enrichedSchema.set("required", required);
                }
                required.add("taskId");

                toolNode.set("inputSchema", enrichedSchema);
            } else {
                toolNode.set("inputSchema", baseSchema);
            }

            toolsArray.add(toolNode);
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", toolsArray);
        return result;
    }

    /**
     * Выполняет вызов инструмента по его уникальному имени.
     *
     * @param name   Имя инструмента.
     * @param params JSON-узел с аргументами вызова.
     *
     * @return JSON-узел с результатом выполнения инструмента.
     *
     * @throws Exception Если инструмент не найден или произошла ошибка во время его выполнения.
     */
    public JsonNode callTool(String name, JsonNode params) throws Exception {
        McpTool tool = getTool(name);
        if (tool == null) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }
        return tool.executeWithFeedback(params);
    }

    /**
     * Возвращает объект реализации инструмента по его имени.
     *
     * @param name Имя инструмента.
     *
     * @return Реализация {@link McpTool} или null, если инструмент не зарегистрирован.
     */
    public McpTool getTool(String name) {
        return tools.get(name);
    }

    /**
     * Проверяет, требует ли инструмент валидную задачу.
     *
     * @param name Имя инструмента.
     * @return true если инструмент требует задачу, false если может работать без неё.
     * @throws IllegalArgumentException если инструмент не найден.
     */
    public boolean toolRequiresTask(String name) {
        McpTool tool = getTool(name);
        if (tool == null) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }
        return tool.requiresTask();
    }
}
