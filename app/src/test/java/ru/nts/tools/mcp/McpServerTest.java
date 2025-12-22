// Aristo 22.12.2025
package ru.nts.tools.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testServerInitialization() throws Exception {
        String initRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n";
        
        // Перехватываем стандартный ввод и вывод
        System.setIn(new ByteArrayInputStream(initRequest.getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        // Запускаем сервер (он остановится, когда закончится ввод)
        McpServer.main(new String[0]);

        String response = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("\"id\":1"));
        assertTrue(response.contains("protocolVersion"));
        assertTrue(response.contains("L2NTS-FileSystem-MCP"));
    }

    @Test
    void testParallelRequests() throws Exception {
        // Симулируем два запроса подряд
        String requests = 
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}\n" +
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}\n";
        
        System.setIn(new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        McpServer.main(new String[0]);

        String out = outContent.toString(StandardCharsets.UTF_8);
        String[] lines = out.split("\n");
        
        // Должно быть как минимум два ответа (по одному на каждую строку ввода)
        // В многопоточной среде они могут прийти в любом порядке, но JSON должен быть валидным
        int responseCount = 0;
        for (String line : lines) {
            if (line.trim().startsWith("{")) {
                var json = mapper.readTree(line);
                if (json.has("id")) {
                    responseCount++;
                }
            }
        }
        assertEquals(2, responseCount, "Сервер должен вернуть два ответа на два запроса");
    }
}
