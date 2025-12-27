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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.LineAccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;
import ru.nts.tools.mcp.tools.fs.FileManageTool;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для инструмента управления файлами (FileManageTool).
 */
class FileManageToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FileManageTool tool = new FileManageTool();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        LineAccessTracker.reset();
    }

    @Test
    void testFileManageActions() throws Exception {
        // Action: create
        ObjectNode pCreate = mapper.createObjectNode();
        pCreate.put("action", "create");
        pCreate.put("path", "new.txt");
        pCreate.put("content", "hello");
        tool.execute(pCreate);
        assertTrue(Files.exists(tempDir.resolve("new.txt")));

        // Action: move
        ObjectNode pMove = mapper.createObjectNode();
        pMove.put("action", "move");
        pMove.put("path", "new.txt");
        pMove.put("targetPath", "sub/moved.txt");
        tool.execute(pMove);
        assertTrue(Files.exists(tempDir.resolve("sub/moved.txt")));
        assertFalse(Files.exists(tempDir.resolve("new.txt")));

        // Action: rename
        ObjectNode pRename = mapper.createObjectNode();
        pRename.put("action", "rename");
        pRename.put("path", "sub/moved.txt");
        pRename.put("newName", "renamed.txt");
        tool.execute(pRename);
        assertTrue(Files.exists(tempDir.resolve("sub/renamed.txt")));
        assertFalse(Files.exists(tempDir.resolve("sub/moved.txt")));

        // Action: delete
        ObjectNode pDelete = mapper.createObjectNode();
        pDelete.put("action", "delete");
        pDelete.put("path", "sub/renamed.txt");
        tool.execute(pDelete);
        assertFalse(Files.exists(tempDir.resolve("sub/renamed.txt")));
    }
}