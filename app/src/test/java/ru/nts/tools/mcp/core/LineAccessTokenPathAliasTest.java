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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LineAccessToken path aliasing feature (REPORT4 Issue 2.1).
 * Verifies that tokens remain valid after file move/rename outside of batch.
 */
class LineAccessTokenPathAliasTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        SessionContext.resetAll();
        SessionContext ctx = SessionContext.getOrCreate("test");
        SessionContext.setCurrent(ctx);
    }

    @Test
    void testTokenValidAfterMoveOutsideBatch() throws Exception {
        // Create original file
        Path oldPath = tempDir.resolve("original.txt");
        Files.writeString(oldPath, "line1\nline2\nline3\n");

        // Register token for original path
        String content = "line1\nline2";
        LineAccessToken token = LineAccessTracker.registerAccess(oldPath, 1, 2, content, 3);
        String encodedToken = token.encode();

        // Move file (outside batch - no transaction)
        Path newPath = tempDir.resolve("renamed.txt");
        Files.move(oldPath, newPath);
        LineAccessTracker.moveTokens(oldPath, newPath);

        // Decode token with new path should succeed due to path aliasing
        assertDoesNotThrow(() -> {
            LineAccessToken decoded = LineAccessToken.decode(encodedToken, newPath);
            assertNotNull(decoded);
            assertEquals(1, decoded.startLine());
            assertEquals(2, decoded.endLine());
        });
    }

    @Test
    void testTokenValidAfterMultipleMoves() throws Exception {
        // Create original file
        Path path1 = tempDir.resolve("file1.txt");
        Files.writeString(path1, "content\n");

        // Register token for original path
        String content = "content\n";
        LineAccessToken token = LineAccessTracker.registerAccess(path1, 1, 1, content, 1);
        String encodedToken = token.encode();

        // Move file twice (outside batch)
        Path path2 = tempDir.resolve("file2.txt");
        Files.move(path1, path2);
        LineAccessTracker.moveTokens(path1, path2);

        Path path3 = tempDir.resolve("file3.txt");
        Files.move(path2, path3);
        LineAccessTracker.moveTokens(path2, path3);

        // Token should work with final path
        assertDoesNotThrow(() -> {
            LineAccessToken decoded = LineAccessToken.decode(encodedToken, path3);
            assertNotNull(decoded);
        });
    }

    @Test
    void testTokenInvalidForUnrelatedPath() throws Exception {
        // Create original file
        Path originalPath = tempDir.resolve("original.txt");
        Files.writeString(originalPath, "content\n");

        // Register token
        String content = "content\n";
        LineAccessToken token = LineAccessTracker.registerAccess(originalPath, 1, 1, content, 1);
        String encodedToken = token.encode();

        // Try to use token with unrelated path
        Path unrelatedPath = tempDir.resolve("unrelated.txt");
        Files.writeString(unrelatedPath, "content\n");

        // Should fail - paths are not related by aliasing
        assertThrows(SecurityException.class, () -> {
            LineAccessToken.decode(encodedToken, unrelatedPath);
        });
    }

    @Test
    void testPathAliasResolveChain() throws Exception {
        // Setup alias chain: a -> b -> c
        Path pathA = tempDir.resolve("a.txt");
        Path pathB = tempDir.resolve("b.txt");
        Path pathC = tempDir.resolve("c.txt");

        LineAccessTracker.registerPathAlias(pathA, pathB);
        LineAccessTracker.registerPathAlias(pathB, pathC);

        // Resolving pathA should yield pathC
        Path resolved = LineAccessTracker.resolveCurrentPath(pathA);
        assertEquals(pathC.toAbsolutePath().normalize(), resolved);

        // Resolving pathB should yield pathC
        Path resolvedB = LineAccessTracker.resolveCurrentPath(pathB);
        assertEquals(pathC.toAbsolutePath().normalize(), resolvedB);

        // Resolving pathC should return pathC (no alias)
        Path resolvedC = LineAccessTracker.resolveCurrentPath(pathC);
        assertEquals(pathC.toAbsolutePath().normalize(), resolvedC);
    }

    @Test
    void testGetPreviousPaths() throws Exception {
        Path oldPath1 = tempDir.resolve("old1.txt");
        Path oldPath2 = tempDir.resolve("old2.txt");
        Path newPath = tempDir.resolve("new.txt");

        // Register multiple aliases to the same target
        LineAccessTracker.registerPathAlias(oldPath1, newPath);
        LineAccessTracker.registerPathAlias(oldPath2, newPath);

        // Get previous paths for newPath
        var previousPaths = LineAccessTracker.getPreviousPaths(newPath);
        assertEquals(2, previousPaths.size());
        assertTrue(previousPaths.contains(oldPath1.toAbsolutePath().normalize()));
        assertTrue(previousPaths.contains(oldPath2.toAbsolutePath().normalize()));
    }

    @Test
    void testIsAliasOf() throws Exception {
        Path oldPath = tempDir.resolve("old.txt");
        Path newPath = tempDir.resolve("new.txt");

        LineAccessTracker.registerPathAlias(oldPath, newPath);

        assertTrue(LineAccessTracker.isAliasOf(oldPath, newPath));
        assertFalse(LineAccessTracker.isAliasOf(newPath, oldPath)); // Reverse is not true
    }

    @Test
    void testResetClearsAliases() throws Exception {
        Path oldPath = tempDir.resolve("old.txt");
        Path newPath = tempDir.resolve("new.txt");

        LineAccessTracker.registerPathAlias(oldPath, newPath);

        // Before reset - alias exists
        assertTrue(LineAccessTracker.isAliasOf(oldPath, newPath));

        // Reset
        LineAccessTracker.reset();

        // After reset - alias should be gone
        assertFalse(LineAccessTracker.isAliasOf(oldPath, newPath));
    }

    @Test
    void testMoveTokensCreatesAlias() throws Exception {
        Path oldPath = tempDir.resolve("old.txt");
        Path newPath = tempDir.resolve("new.txt");

        Files.writeString(oldPath, "test\n");

        // Register token and move
        LineAccessTracker.registerAccess(oldPath, 1, 1, "test\n", 1);
        LineAccessTracker.moveTokens(oldPath, newPath);

        // moveTokens should have created an alias
        assertTrue(LineAccessTracker.isAliasOf(oldPath, newPath));

        // Previous paths should include oldPath
        var previousPaths = LineAccessTracker.getPreviousPaths(newPath);
        assertTrue(previousPaths.contains(oldPath.toAbsolutePath().normalize()));
    }
}
