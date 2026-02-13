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
package ru.nts.tools.mcp.core.treesitter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TaskContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SyntaxCheckerTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        TaskContext.resetAll();
        TaskContext.setForceInMemoryDb(true);
        TaskContext ctx = TaskContext.getOrCreate("test");
        TaskContext.setCurrent(ctx);
    }

    @Test
    void testValidJavaCode_noErrors() throws IOException {
        Path file = tempDir.resolve("Valid.java");
        Files.writeString(file, """
                package test;

                public class Valid {
                    public void method() {
                        System.out.println("Hello");
                    }
                }
                """);

        SyntaxChecker.SyntaxCheckResult result = SyntaxChecker.check(file);
        assertFalse(result.hasErrors(), "Valid Java should have no errors: " + result.errors());
    }

    @Test
    void testMissingSemicolon_returnsError() throws IOException {
        Path file = tempDir.resolve("Missing.java");
        Files.writeString(file, """
                package test;

                public class Missing {
                    public void method() {
                        int x = 5
                    }
                }
                """);

        SyntaxChecker.SyntaxCheckResult result = SyntaxChecker.check(file);
        assertTrue(result.hasErrors(), "Missing semicolon should be detected");
        assertTrue(result.errorCount() >= 1);

        // Verify error has line number
        SyntaxChecker.SyntaxError error = result.errors().getFirst();
        assertTrue(error.line() > 0, "Error should have a valid line number");
    }

    @Test
    void testMissingClosingBrace_returnsError() throws IOException {
        Path file = tempDir.resolve("NoBrace.java");
        Files.writeString(file, """
                package test;

                public class NoBrace {
                    public void method() {
                        System.out.println("oops");

                }
                """);

        SyntaxChecker.SyntaxCheckResult result = SyntaxChecker.check(file);
        assertTrue(result.hasErrors(), "Missing closing brace should be detected");
    }

    @Test
    void testUnsupportedLanguage_returnsEmpty() throws IOException {
        Path file = tempDir.resolve("readme.txt");
        Files.writeString(file, "This is a plain text file with random { [ stuff");

        SyntaxChecker.SyntaxCheckResult result = SyntaxChecker.check(file);
        assertFalse(result.hasErrors(), "Unsupported language should return empty result");
        assertEquals(0, result.errorCount());
    }

    @Test
    void testErrorLimit_maxFiveErrors() throws IOException {
        Path file = tempDir.resolve("ManyErrors.java");
        // Generate code with many syntax errors
        StringBuilder sb = new StringBuilder();
        sb.append("package test;\npublic class ManyErrors {\n");
        for (int i = 0; i < 15; i++) {
            sb.append("    int x").append(i).append(" = \n"); // missing value and semicolon
        }
        sb.append("}\n");
        Files.writeString(file, sb.toString());

        SyntaxChecker.SyntaxCheckResult result = SyntaxChecker.check(file);
        assertTrue(result.hasErrors());
        assertTrue(result.errorCount() <= 5,
                "Should return at most 5 errors, got: " + result.errorCount());
    }

    @Test
    void testCheckContent_worksWithVirtualContent() {
        Path fakePath = tempDir.resolve("Virtual.java");
        String validCode = """
                package test;
                public class Virtual {
                    public void ok() {}
                }
                """;

        SyntaxChecker.SyntaxCheckResult validResult = SyntaxChecker.checkContent(fakePath, validCode);
        assertFalse(validResult.hasErrors());

        String brokenCode = """
                package test;
                public class Virtual {
                    public void broken( {
                    }
                }
                """;

        SyntaxChecker.SyntaxCheckResult brokenResult = SyntaxChecker.checkContent(fakePath, brokenCode);
        assertTrue(brokenResult.hasErrors(), "Broken virtual content should have errors");
    }

    @Test
    void testErrorContainsContext() throws IOException {
        Path file = tempDir.resolve("Context.java");
        Files.writeString(file, """
                package test;

                public class Context {
                    int bad = ;
                }
                """);

        SyntaxChecker.SyntaxCheckResult result = SyntaxChecker.check(file);
        assertTrue(result.hasErrors());

        SyntaxChecker.SyntaxError error = result.errors().getFirst();
        assertNotNull(error.context(), "Error should include code context");
        assertFalse(error.context().isBlank(), "Context should not be blank");
    }

    @Test
    void testNonExistentFile_returnsEmpty() {
        Path nonExistent = tempDir.resolve("DoesNotExist.java");

        SyntaxChecker.SyntaxCheckResult result = SyntaxChecker.check(nonExistent);
        assertFalse(result.hasErrors(), "Non-existent file should return empty result");
    }
}
