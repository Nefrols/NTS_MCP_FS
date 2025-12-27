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
package ru.nts.tools.mcp.tools.editing;

import ru.nts.tools.mcp.tools.fs.*;
import ru.nts.tools.mcp.tools.editing.*;
import ru.nts.tools.mcp.tools.session.*;
import ru.nts.tools.mcp.tools.external.*;
import ru.nts.tools.mcp.tools.planning.*;
import ru.nts.tools.mcp.tools.system.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для инструмента глобальной замены (ProjectReplaceTool).
 */
class ProjectReplaceToolTest {

    private final ProjectReplaceTool tool = new ProjectReplaceTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testGlobalReplace(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file1 = tempDir.resolve("file1.txt");
        Path file2 = tempDir.resolve("file2.java");
        Files.writeString(file1, "old content");
        Files.writeString(file2, "some text with old content");

        ObjectNode params = mapper.createObjectNode();
        params.put("pattern", "old");
        params.put("replacement", "new");

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("Files affected: 2"), "Должно быть затронуто 2 файла. Output: " + text);
        assertEquals("new content", Files.readString(file1));
        assertEquals("some text with new content", Files.readString(file2));
    }

    @Test
    void testRegexReplace(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("data.txt");
        Files.writeString(file, "ID: 123, ID: 456");

        ObjectNode params = mapper.createObjectNode();
        params.put("pattern", "ID: (\\d+)");
        params.put("replacement", "NUM: $1");
        params.put("isRegex", true);

        tool.execute(params);

        assertEquals("NUM: 123, NUM: 456", Files.readString(file));
    }

    @Test
    void testIncludeFilter(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.writeString(tempDir.resolve("test.txt"), "old");
        Path javaFile = tempDir.resolve("test.java");
        Files.writeString(javaFile, "old");

        ObjectNode params = mapper.createObjectNode();
        params.put("pattern", "old");
        params.put("replacement", "new");
        params.put("include", "*.java");

        tool.execute(params);

        assertEquals("old", Files.readString(tempDir.resolve("test.txt")), "Файл .txt должен быть пропущен");
        assertEquals("new", Files.readString(javaFile), "Файл .java должен быть изменен");
    }

    @Test
    void testUndoGlobalReplace(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        Path file = tempDir.resolve("undo_test.txt");
        Files.writeString(file, "initial");

        ObjectNode params = mapper.createObjectNode();
        params.put("pattern", "initial");
        params.put("replacement", "changed");
        tool.execute(params);

        assertEquals("changed", Files.readString(file));

        TransactionManager.undo();
        assertEquals("initial", Files.readString(file), "Undo должен вернуть исходное состояние файла");
    }
}