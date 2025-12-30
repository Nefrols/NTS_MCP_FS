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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.LineAccessToken;
import ru.nts.tools.mcp.core.LineAccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.SessionContext;
import ru.nts.tools.mcp.core.TransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        SessionContext.resetAll();
        SessionContext ctx = SessionContext.getOrCreate("test");
        SessionContext.setCurrent(ctx);
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

    // ==================== Token Move/Rename Tests ====================
    // Regression tests for REPORT3 issue 2.2: tokens invalidated after move/rename

    @Nested
    class TokenMoveRenameTests {

        @Test
        void moveReturnsUpdatedTokens() throws Exception {
            // REPORT3 Issue 2.2: tokens should be updated after move
            // Create a file and register a token
            Path original = tempDir.resolve("original.java");
            String content = "public class Original {\n    void method() {}\n}\n";
            Files.writeString(original, content);

            // Register access token for the file
            LineAccessToken originalToken = LineAccessTracker.registerAccess(
                    original, 1, 3, content, 3);
            assertNotNull(originalToken);
            String originalEncoded = originalToken.encode();

            // Move the file
            ObjectNode pMove = mapper.createObjectNode();
            pMove.put("action", "move");
            pMove.put("path", "original.java");
            pMove.put("targetPath", "moved/new.java");

            JsonNode result = tool.execute(pMove);

            // Verify file was moved
            Path newPath = tempDir.resolve("moved/new.java");
            assertTrue(Files.exists(newPath), "File should be moved");
            assertFalse(Files.exists(original), "Original should not exist");

            // Check that response contains updated tokens
            String responseText = result.get("content").get(0).get("text").asText();
            assertTrue(responseText.contains("Updated Tokens"),
                    "Response should contain updated tokens section: " + responseText);
            assertTrue(responseText.contains("TOKEN"),
                    "Response should contain new TOKEN: " + responseText);

            // Verify tokens are now associated with new path
            List<LineAccessToken> newTokens = LineAccessTracker.getTokensForFile(newPath);
            assertFalse(newTokens.isEmpty(), "New path should have tokens");

            // Verify new token encodes with new path (different hash)
            LineAccessToken newToken = newTokens.get(0);
            String newEncoded = newToken.encode();
            assertNotEquals(originalEncoded, newEncoded,
                    "Token encoding should change after move (different path hash)");
        }

        @Test
        void renameReturnsUpdatedTokens() throws Exception {
            // REPORT3 Issue 2.2: tokens should be updated after rename
            Path original = tempDir.resolve("OldName.java");
            String content = "public class OldName {\n}\n";
            Files.writeString(original, content);

            // Register access token
            LineAccessToken originalToken = LineAccessTracker.registerAccess(
                    original, 1, 2, content, 2);
            String originalEncoded = originalToken.encode();

            // Rename the file
            ObjectNode pRename = mapper.createObjectNode();
            pRename.put("action", "rename");
            pRename.put("path", "OldName.java");
            pRename.put("newName", "NewName.java");

            JsonNode result = tool.execute(pRename);

            // Verify file was renamed
            Path newPath = tempDir.resolve("NewName.java");
            assertTrue(Files.exists(newPath), "File should be renamed");
            assertFalse(Files.exists(original), "Original should not exist");

            // Check that response contains updated tokens
            String responseText = result.get("content").get(0).get("text").asText();
            assertTrue(responseText.contains("Updated Tokens"),
                    "Response should contain updated tokens section: " + responseText);

            // Verify tokens are now associated with new path
            List<LineAccessToken> newTokens = LineAccessTracker.getTokensForFile(newPath);
            assertFalse(newTokens.isEmpty(), "New path should have tokens");

            // Verify new token encodes with new path
            LineAccessToken newToken = newTokens.get(0);
            String newEncoded = newToken.encode();
            assertNotEquals(originalEncoded, newEncoded,
                    "Token encoding should change after rename (different path hash)");
        }

        @Test
        void movePreservesTokenRanges() throws Exception {
            // Verify that token ranges are preserved after move
            Path original = tempDir.resolve("code.java");
            String content = """
                    package test;

                    public class Code {
                        void method1() {}
                        void method2() {}
                        void method3() {}
                    }
                    """;
            Files.writeString(original, content);

            // Register multiple tokens for different ranges
            LineAccessTracker.registerAccess(original, 1, 3,
                    "package test;\n\npublic class Code {", 7);
            LineAccessTracker.registerAccess(original, 4, 6,
                    "    void method1() {}\n    void method2() {}\n    void method3() {}", 7);

            List<LineAccessToken> originalTokens = LineAccessTracker.getTokensForFile(original);
            int originalTokenCount = originalTokens.size();

            // Move the file
            ObjectNode pMove = mapper.createObjectNode();
            pMove.put("action", "move");
            pMove.put("path", "code.java");
            pMove.put("targetPath", "src/code.java");
            tool.execute(pMove);

            Path newPath = tempDir.resolve("src/code.java");

            // Verify tokens were transferred
            List<LineAccessToken> newTokens = LineAccessTracker.getTokensForFile(newPath);
            assertEquals(originalTokenCount, newTokens.size(),
                    "Token count should be preserved after move");

            // Verify old path has no tokens
            List<LineAccessToken> oldPathTokens = LineAccessTracker.getTokensForFile(original);
            assertTrue(oldPathTokens.isEmpty(), "Old path should have no tokens after move");
        }

        @Test
        void moveWithNoTokensDoesNotFail() throws Exception {
            // Move should work fine even without any tokens
            Path original = tempDir.resolve("notread.txt");
            Files.writeString(original, "content");

            // No tokens registered for this file
            List<LineAccessToken> beforeTokens = LineAccessTracker.getTokensForFile(original);
            assertTrue(beforeTokens.isEmpty(), "No tokens should exist before move");

            ObjectNode pMove = mapper.createObjectNode();
            pMove.put("action", "move");
            pMove.put("path", "notread.txt");
            pMove.put("targetPath", "moved.txt");

            JsonNode result = tool.execute(pMove);

            Path newPath = tempDir.resolve("moved.txt");
            assertTrue(Files.exists(newPath), "File should be moved");

            // Response should not have "Updated Tokens" section since there were none
            String responseText = result.get("content").get(0).get("text").asText();
            assertFalse(responseText.contains("Updated Tokens"),
                    "Response should not contain tokens section when no tokens exist");
        }

        @Test
        void createReturnsToken() throws Exception {
            // Create should return a token for immediate editing
            ObjectNode pCreate = mapper.createObjectNode();
            pCreate.put("action", "create");
            pCreate.put("path", "newfile.java");
            pCreate.put("content", "public class NewFile {}\n");

            JsonNode result = tool.execute(pCreate);

            String responseText = result.get("content").get(0).get("text").asText();
            assertTrue(responseText.contains("TOKEN"),
                    "Create response should contain a TOKEN: " + responseText);

            // Verify token was registered
            Path newPath = tempDir.resolve("newfile.java");
            List<LineAccessToken> tokens = LineAccessTracker.getTokensForFile(newPath);
            assertFalse(tokens.isEmpty(), "Created file should have a token");
        }

        @Test
        void deleteInvalidatesTokens() throws Exception {
            // Delete should invalidate all tokens for the file
            Path file = tempDir.resolve("todelete.java");
            Files.writeString(file, "content");

            // Register a token
            LineAccessTracker.registerAccess(file, 1, 1, "content", 1);
            List<LineAccessToken> beforeTokens = LineAccessTracker.getTokensForFile(file);
            assertFalse(beforeTokens.isEmpty(), "Token should exist before delete");

            ObjectNode pDelete = mapper.createObjectNode();
            pDelete.put("action", "delete");
            pDelete.put("path", "todelete.java");
            tool.execute(pDelete);

            // Verify tokens are invalidated
            List<LineAccessToken> afterTokens = LineAccessTracker.getTokensForFile(file);
            assertTrue(afterTokens.isEmpty(), "Tokens should be invalidated after delete");
        }
    }
}