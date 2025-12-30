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

import org.junit.jupiter.api.*;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для SymbolIndex.
 */
class SymbolIndexTest {

    private static Path tempDir;
    private SymbolIndex index;

    @BeforeAll
    static void setUpClass() throws IOException {
        tempDir = Files.createTempDirectory("symbol-index-test");
    }

    @AfterAll
    static void tearDownClass() throws IOException {
        // Очищаем временную директорию
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            // Игнорируем
                        }
                    });
        }
    }

    @BeforeEach
    void setUp() {
        index = SymbolIndex.getInstance();
        index.clear();
    }

    @AfterEach
    void tearDown() {
        index.clear();
    }

    @Test
    @DisplayName("getInstance() возвращает синглтон")
    void testSingleton() {
        SymbolIndex index1 = SymbolIndex.getInstance();
        SymbolIndex index2 = SymbolIndex.getInstance();
        assertSame(index1, index2, "Должен возвращаться один и тот же экземпляр");
    }

    @Test
    @DisplayName("isIndexed() возвращает false до индексации")
    void testIsIndexedBeforeIndexing() {
        assertFalse(index.isIndexed(), "Должен быть не проиндексирован до вызова indexProject");
    }

    @Test
    @DisplayName("indexProjectAsync() индексирует Java файлы")
    void testIndexProjectAsync() throws Exception {
        // Создаём тестовый Java файл
        Path javaFile = tempDir.resolve("TestClass.java");
        Files.writeString(javaFile, """
                public class TestClass {
                    private String field;

                    public void testMethod() {
                        System.out.println("test");
                    }
                }
                """);

        // Индексируем
        var result = index.indexProjectAsync(tempDir).get(30, TimeUnit.SECONDS);

        assertTrue(result.success(), "Индексация должна быть успешной");
        assertTrue(result.filesIndexed() >= 1, "Должен быть проиндексирован хотя бы 1 файл");
        assertTrue(result.symbolsIndexed() >= 1, "Должен быть найден хотя бы 1 символ");
        assertTrue(index.isIndexed(), "Индекс должен быть готов");
    }

    @Test
    @DisplayName("findDefinitions() находит символы по имени")
    void testFindDefinitions() throws Exception {
        // Создаём тестовый файл
        Path javaFile = tempDir.resolve("MyClass.java");
        Files.writeString(javaFile, """
                public class MyClass {
                    public void myMethod() {}
                }
                """);

        // Индексируем
        index.indexProjectAsync(tempDir).get(30, TimeUnit.SECONDS);

        // Ищем
        List<Location> definitions = index.findDefinitions("MyClass");

        assertFalse(definitions.isEmpty(), "Должен найти класс MyClass");
        assertEquals(javaFile.toAbsolutePath().normalize(),
                definitions.get(0).path().toAbsolutePath().normalize(),
                "Должен указывать на правильный файл");
    }

    @Test
    @DisplayName("findFirstDefinition() возвращает первое определение")
    void testFindFirstDefinition() throws Exception {
        Path javaFile = tempDir.resolve("AnotherClass.java");
        Files.writeString(javaFile, """
                public class AnotherClass {
                    public static final String CONSTANT = "value";
                }
                """);

        index.indexProjectAsync(tempDir).get(30, TimeUnit.SECONDS);

        Optional<Location> definition = index.findFirstDefinition("AnotherClass");

        assertTrue(definition.isPresent(), "Должен найти класс");
        assertEquals(1, definition.get().startLine(), "Класс должен быть на первой строке");
    }

    @Test
    @DisplayName("findFilesContainingSymbol() находит файлы с символом")
    void testFindFilesContainingSymbol() throws Exception {
        Path file1 = tempDir.resolve("File1.java");
        Path file2 = tempDir.resolve("File2.java");

        Files.writeString(file1, """
                public class File1 {
                    public void commonMethod() {}
                }
                """);

        Files.writeString(file2, """
                public class File2 {
                    public void anotherMethod() {}
                }
                """);

        index.indexProjectAsync(tempDir).get(30, TimeUnit.SECONDS);

        Set<Path> files = index.findFilesContainingSymbol("File1");

        assertFalse(files.isEmpty(), "Должен найти файл с символом");
        assertTrue(files.stream()
                        .anyMatch(p -> p.toString().endsWith("File1.java")),
                "Должен содержать File1.java");
    }

    @Test
    @DisplayName("invalidateFile() удаляет символы файла из индекса")
    void testInvalidateFile() throws Exception {
        Path javaFile = tempDir.resolve("ToInvalidate.java");
        Files.writeString(javaFile, """
                public class ToInvalidate {
                    public void method() {}
                }
                """);

        index.indexProjectAsync(tempDir).get(30, TimeUnit.SECONDS);

        // Проверяем что символ есть
        assertFalse(index.findDefinitions("ToInvalidate").isEmpty(),
                "Символ должен быть в индексе");

        // Удаляем файл и инвалидируем
        Files.delete(javaFile);
        index.invalidateFile(javaFile);

        // Проверяем что символ удалён
        assertTrue(index.findDefinitions("ToInvalidate").isEmpty(),
                "Символ должен быть удалён из индекса");
    }

    @Test
    @DisplayName("clear() очищает весь индекс")
    void testClear() throws Exception {
        Path javaFile = tempDir.resolve("ToClear.java");
        Files.writeString(javaFile, """
                public class ToClear {}
                """);

        index.indexProjectAsync(tempDir).get(30, TimeUnit.SECONDS);
        assertTrue(index.getSymbolCount() > 0, "Должны быть символы");

        index.clear();

        assertEquals(0, index.getSymbolCount(), "Символов не должно быть");
        assertEquals(0, index.getFileCount(), "Файлов не должно быть");
        assertFalse(index.isIndexed(), "Индекс должен быть не готов");
    }

    @Test
    @DisplayName("getIndexingProgress() возвращает прогресс")
    void testGetIndexingProgress() {
        // После очистки (в setUp) прогресс 0
        index.clear();
        assertEquals(0.0, index.getIndexingProgress(), 0.001,
                "Прогресс должен быть 0 после очистки");
    }

    @Test
    @DisplayName("Игнорирует директории node_modules, build, target")
    void testSkipsIgnoredDirectories() throws Exception {
        // Создаём структуру с игнорируемыми директориями
        Path nodeModules = tempDir.resolve("node_modules");
        Files.createDirectories(nodeModules);
        Files.writeString(nodeModules.resolve("Ignored.java"), """
                public class Ignored {}
                """);

        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("NotIgnored.java"), """
                public class NotIgnored {}
                """);

        index.indexProjectAsync(tempDir).get(30, TimeUnit.SECONDS);

        // Должен найти NotIgnored, но не Ignored
        assertFalse(index.findDefinitions("NotIgnored").isEmpty(),
                "NotIgnored должен быть проиндексирован");
        assertTrue(index.findDefinitions("Ignored").isEmpty(),
                "Ignored из node_modules не должен быть проиндексирован");
    }
}
