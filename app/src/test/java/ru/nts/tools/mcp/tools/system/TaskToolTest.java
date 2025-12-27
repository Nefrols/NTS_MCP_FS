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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.tools.system.TaskTool;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для инструмента управления задачами (TaskTool).
 */
class TaskToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskTool tool = new TaskTool();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
    }

    @Test
    void testUnknownTask() throws Exception {
        ObjectNode pLog = mapper.createObjectNode();
        pLog.put("action", "log");
        pLog.put("taskId", "none");
        JsonNode res = tool.execute(pLog);
        assertTrue(res.get("content").get(0).get("text").asText().contains("not found"));
    }
}
