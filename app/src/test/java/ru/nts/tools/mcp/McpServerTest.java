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
package ru.nts.tools.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Интеграционные тесты для MCP сервера.
 * Проверяют корректность обработки JSON-RPC сообщений, инициализацию протокола
 * и стабильность работы сервера при параллельных запросах.
 */
class McpServerTest {

    /**
     * JSON манипулятор для проверки ответов сервера.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Тестирует процесс инициализации MCP сервера.
     * Проверяет, что сервер корректно отвечает на метод 'initialize' и возвращает информацию о себе.
     */
    @Test
    void testServerInitialization() throws Exception {
        // Формируем стандартный JSON-RPC запрос инициализации
        String initRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n";

        // Перехватываем стандартные потоки ввода-вывода для симуляции взаимодействия с клиентом
        System.setIn(new ByteArrayInputStream(initRequest.getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        // Запускаем основной цикл сервера
        McpServer.main(new String[0]);

        // Проверяем содержимое сформированного ответа
        String response = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("\"id\":1"), "Ответ должен содержать ID запроса");
        assertTrue(response.contains("protocolVersion"), "Ответ должен содержать версию протокола");
        assertTrue(response.contains("NTS-FileSystem-MCP"), "Ответ должен содержать имя сервера");
        assertTrue(response.contains("capabilities"), "Ответ должен содержать capabilities");
        assertTrue(response.contains("tools"), "Ответ должен содержать поддержку инструментов");
        assertTrue(response.contains("listChanged"), "Должен поддерживаться listChanged для инструментов");
    }

    /**
     * Тестирует выполнение метода 'ping'.
     * Ожидается пустой результат в ответе.
     */
    @Test
    void testPing() throws Exception {
        String pingRequest = "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"ping\"}\n";

        System.setIn(new ByteArrayInputStream(pingRequest.getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        McpServer.main(new String[0]);

        String out = outContent.toString(StandardCharsets.UTF_8);
        var json = mapper.readTree(out);
        assertEquals(42, json.get("id").asInt());
        assertTrue(json.has("result"));
        assertTrue(json.get("result").isObject());
        assertEquals(0, json.get("result").size());
    }

    /**
     * Тестирует способность сервера обрабатывать несколько запросов подряд.
     * Проверяет корректность работы механизма виртуальных потоков и синхронизации вывода.
     */
    @Test
    void testParallelRequests() throws Exception {
        // Симулируем поток из двух независимых запросов
        String requests = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}\n" + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}\n";

        System.setIn(new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        McpServer.main(new String[0]);

        String out = outContent.toString(StandardCharsets.UTF_8);
        String[] lines = out.split("\n");

        // В многопоточной среде ответы могут прийти в любом порядке, 
        // но каждый ответ должен быть валидным JSON объектом.
        int responseCount = 0;
        for (String line : lines) {
            if (line.trim().startsWith("{")) {
                var json = mapper.readTree(line);
                if (json.has("id")) {
                    responseCount++;
                }
            }
        }
        assertEquals(2, responseCount, "Сервер должен вернуть ровно два ответа на два входных запроса");
    }
}