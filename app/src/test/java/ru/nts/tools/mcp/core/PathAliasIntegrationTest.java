/*
 * Copyright 2025 Aristo
 */
package ru.nts.tools.mcp.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.tools.fs.FileManageTool;
import ru.nts.tools.mcp.tools.fs.FileReadTool;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for path aliasing with FileManageTool.
 * Tests that old tokens work after file move/rename through the actual tool.
 */
class PathAliasIntegrationTest {

    @TempDir
    Path tempDir;

    private ObjectMapper mapper;
    private FileReadTool readTool;
    private FileManageTool manageTool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        readTool = new FileReadTool();
        manageTool = new FileManageTool();
        PathSanitizer.setRoot(tempDir);
        SessionContext.resetAll();
        SessionContext ctx = SessionContext.getOrCreate("test");
        SessionContext.setCurrent(ctx);
    }

    @Test
    void testOldTokenWorksAfterMoveViaFileManageTool() throws Exception {
        // 1. Create file with content
        Path originalFile = tempDir.resolve("original.txt");
        String content = "line1\nline2\nline3\nline4\nline5\n";
        Files.writeString(originalFile, content);

        // 2. Read file to get token (simulating first MCP request)
        ObjectNode readParams = mapper.createObjectNode();
        readParams.put("path", originalFile.toString());
        readParams.put("startLine", 1);
        readParams.put("endLine", 5);
        JsonNode readResult = readTool.execute(readParams);

        // Extract token from response
        String response = readResult.path("content").path(0).path("text").asText();
        System.out.println("=== READ RESPONSE ===");
        System.out.println(response);

        // Find token in response
        String oldToken = extractToken(response);
        assertNotNull(oldToken, "Should have received a token");
        System.out.println("Old token: " + oldToken);

        // 3. Move file via FileManageTool (simulating second MCP request)
        Path newPath = tempDir.resolve("renamed.txt");
        ObjectNode moveParams = mapper.createObjectNode();
        moveParams.put("path", originalFile.toString());
        moveParams.put("action", "rename");
        moveParams.put("newName", "renamed.txt");

        JsonNode moveResult = manageTool.execute(moveParams);
        String moveResponse = moveResult.path("content").path(0).path("text").asText();
        System.out.println("\n=== MOVE RESPONSE ===");
        System.out.println(moveResponse);

        // Verify file was moved
        assertFalse(Files.exists(originalFile), "Original file should not exist");
        assertTrue(Files.exists(newPath), "New file should exist");

        // 4. Try to decode OLD token with NEW path (simulating third MCP request)
        // This should work due to path aliasing!
        System.out.println("\n=== DECODING OLD TOKEN WITH NEW PATH ===");

        assertDoesNotThrow(() -> {
            LineAccessToken decoded = LineAccessToken.decode(oldToken, newPath);
            System.out.println("Successfully decoded! Token: " + decoded);
            assertEquals(1, decoded.startLine());
        }, "Old token should work with new path due to path aliasing");
    }

    @Test
    void testOldTokenWorksAfterMoveAction() throws Exception {
        // Similar test but using 'move' action instead of 'rename'
        Path originalFile = tempDir.resolve("source.txt");
        String content = "content\n";
        Files.writeString(originalFile, content);

        // Read to get token
        ObjectNode readParams = mapper.createObjectNode();
        readParams.put("path", originalFile.toString());
        readParams.put("startLine", 1);
        readParams.put("endLine", 1);
        JsonNode readResult = readTool.execute(readParams);
        String response = readResult.path("content").path(0).path("text").asText();
        String oldToken = extractToken(response);
        assertNotNull(oldToken);

        // Move file
        Path destPath = tempDir.resolve("subdir").resolve("dest.txt");
        ObjectNode moveParams = mapper.createObjectNode();
        moveParams.put("path", originalFile.toString());
        moveParams.put("action", "move");
        moveParams.put("targetPath", destPath.toString());
        manageTool.execute(moveParams);

        // Old token should work with new path
        assertDoesNotThrow(() -> {
            LineAccessToken decoded = LineAccessToken.decode(oldToken, destPath);
            assertNotNull(decoded);
        });
    }

    private String extractToken(String response) {
        // Look for token pattern: LAT:HASH:start:end:crc:lines
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("LAT:[A-F0-9]+:\\d+:\\d+:[A-F0-9]+:\\d+");
        java.util.regex.Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }
}
