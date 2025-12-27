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

package ru.nts.tools.mcp.tools.fs;


import ru.nts.tools.mcp.tools.fs.*;
import ru.nts.tools.mcp.tools.editing.*;
import ru.nts.tools.mcp.tools.session.*;
import ru.nts.tools.mcp.tools.external.*;
import ru.nts.tools.mcp.tools.planning.*;
import ru.nts.tools.mcp.tools.system.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CompareFilesToolTest {
    private final CompareFilesTool tool = new CompareFilesTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testCompare(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path f1 = tempDir.resolve("a.txt");
        Path f2 = tempDir.resolve("b.txt");
        Files.writeString(f1, "Line 1\nLine 2");
        Files.writeString(f2, "Line 1\nLine 3");

        var params = mapper.createObjectNode();
        params.put("path1", "a.txt");
        params.put("path2", "b.txt");

        JsonNode res = tool.execute(params);
        String text = res.get("content").get(0).get("text").asText();

        assertTrue(text.contains("-Line 2"));
        assertTrue(text.contains("+Line 3"));
    }

    @Test
    void testIdentical(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path f1 = tempDir.resolve("a.txt");
        Path f2 = tempDir.resolve("b.txt");
        Files.writeString(f1, "same");
        Files.writeString(f2, "same");

        var params = mapper.createObjectNode();
        params.put("path1", "a.txt");
        params.put("path2", "b.txt");

        JsonNode res = tool.execute(params);
        String text = res.get("content").get(0).get("text").asText();

        assertEquals("Files are identical.", text);
    }
}
