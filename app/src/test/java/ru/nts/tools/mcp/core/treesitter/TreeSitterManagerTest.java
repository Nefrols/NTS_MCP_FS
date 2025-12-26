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

import org.treesitter.TSLanguage;
import org.treesitter.TSTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TreeSitterManagerTest {

    private TreeSitterManager manager;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        manager = TreeSitterManager.getInstance();
        manager.clearCache();
    }

    @Test
    void getInstance() {
        TreeSitterManager instance1 = TreeSitterManager.getInstance();
        TreeSitterManager instance2 = TreeSitterManager.getInstance();
        assertSame(instance1, instance2, "Should be singleton");
    }

    @Test
    void getLanguageJava() {
        TSLanguage lang = manager.getLanguage("java");
        assertNotNull(lang, "Java language should be loaded");
    }

    @Test
    void getLanguageKotlin() {
        TSLanguage lang = manager.getLanguage("kotlin");
        assertNotNull(lang, "Kotlin language should be loaded");
    }

    @Test
    void getLanguagePython() {
        TSLanguage lang = manager.getLanguage("python");
        assertNotNull(lang, "Python language should be loaded");
    }

    @Test
    void getLanguageUnsupported() {
        assertThrows(IllegalArgumentException.class, () -> {
            manager.getLanguage("ruby");
        });
    }

    @Test
    void parseJavaContent() {
        String code = """
                public class HelloWorld {
                    public static void main(String[] args) {
                        System.out.println("Hello, World!");
                    }
                }
                """;

        TSTree tree = manager.parse(code, "java");
        assertNotNull(tree, "Should parse Java code");
        assertNotNull(tree.getRootNode(), "Should have root node");
        assertEquals("program", tree.getRootNode().getType(), "Root should be 'program'");
    }

    @Test
    void parsePythonContent() {
        String code = """
                def hello():
                    print("Hello, World!")

                if __name__ == "__main__":
                    hello()
                """;

        TSTree tree = manager.parse(code, "python");
        assertNotNull(tree, "Should parse Python code");
        assertEquals("module", tree.getRootNode().getType(), "Root should be 'module'");
    }

    @Test
    void parseJavaScriptContent() {
        String code = """
                function greet(name) {
                    console.log(`Hello, ${name}!`);
                }

                greet("World");
                """;

        TSTree tree = manager.parse(code, "javascript");
        assertNotNull(tree, "Should parse JavaScript code");
        assertEquals("program", tree.getRootNode().getType());
    }

    @Test
    void parseFile(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("Test.java");
        Files.writeString(javaFile, """
                public class Test {
                    private int value;

                    public int getValue() {
                        return value;
                    }
                }
                """);

        TSTree tree = manager.parse(javaFile, null);
        assertNotNull(tree);
        assertEquals("program", tree.getRootNode().getType());
    }

    @Test
    void getCachedOrParse(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("Cached.java");
        Files.writeString(javaFile, "public class Cached {}");

        // First parse - not cached
        assertEquals(0, manager.getCacheSize());
        TSTree tree1 = manager.getCachedOrParse(javaFile);
        assertNotNull(tree1);
        assertEquals(1, manager.getCacheSize());

        // Second parse - should be cached
        TSTree tree2 = manager.getCachedOrParse(javaFile);
        assertNotNull(tree2);
        assertEquals(1, manager.getCacheSize());
    }

    @Test
    void cacheInvalidationOnChange(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("Changing.java");
        Files.writeString(javaFile, "public class Changing {}");

        TSTree tree1 = manager.getCachedOrParse(javaFile);
        assertTrue(manager.isCached(javaFile));

        // Modify file
        Files.writeString(javaFile, "public class Changing { int x; }");

        // Cache should be invalid (different CRC)
        assertFalse(manager.isCached(javaFile));
    }

    @Test
    void invalidateCache(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("ToInvalidate.java");
        Files.writeString(javaFile, "public class ToInvalidate {}");

        manager.getCachedOrParse(javaFile);
        assertEquals(1, manager.getCacheSize());

        manager.invalidateCache(javaFile);
        assertEquals(0, manager.getCacheSize());
    }

    @Test
    void clearCache(@TempDir Path tempDir) throws IOException {
        Path file1 = tempDir.resolve("File1.java");
        Path file2 = tempDir.resolve("File2.java");
        Files.writeString(file1, "class File1 {}");
        Files.writeString(file2, "class File2 {}");

        manager.getCachedOrParse(file1);
        manager.getCachedOrParse(file2);
        assertEquals(2, manager.getCacheSize());

        manager.clearCache();
        assertEquals(0, manager.getCacheSize());
    }

    @Test
    void getCachedOrParseWithContent(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("WithContent.java");
        Files.writeString(javaFile, "public class WithContent { int x = 42; }");

        TreeSitterManager.ParseResult result = manager.getCachedOrParseWithContent(javaFile);

        assertNotNull(result);
        assertNotNull(result.tree());
        assertNotNull(result.content());
        assertEquals("java", result.langId());
        assertTrue(result.crc32c() > 0);
        assertTrue(result.content().contains("WithContent"));
    }

    @Test
    void parseInvalidSyntax() {
        // Even invalid syntax should parse (tree-sitter is error-tolerant)
        String invalidCode = "public class { broken syntax }}}";
        TSTree tree = manager.parse(invalidCode, "java");
        assertNotNull(tree, "Should parse even invalid code");
        // Tree will contain ERROR nodes
    }
}
