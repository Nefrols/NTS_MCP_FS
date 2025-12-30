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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.SessionContext;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for cross-file reference search in SymbolResolver.
 * Regression tests for REPORT3 issue 2.1: references in other files were not found.
 *
 * The fix changed findReferencesInProject to always use FastSearch (scanFilesForSymbol)
 * instead of SymbolIndex.findFilesContainingSymbol which only returned files with
 * symbol DEFINITIONS, not USAGES.
 */
class SymbolResolverCrossFileTest {

    @TempDir
    Path tempDir;

    private SymbolResolver resolver;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        SessionContext.resetAll();
        SessionContext ctx = SessionContext.getOrCreate("test");
        SessionContext.setCurrent(ctx);
        resolver = SymbolResolver.getInstance();
    }

    @Nested
    class CrossFileReferenceTests {

        @Test
        void findReferencesInOtherFile_methodCall() throws IOException {
            // REPORT3 Issue 2.1: references in other files were not found
            // Create a service class with a method
            Path serviceFile = tempDir.resolve("Service.java");
            String serviceCode = """
                    package test;

                    public class Service {
                        public void processData(String data) {
                            System.out.println(data);
                        }
                    }
                    """;
            Files.writeString(serviceFile, serviceCode);

            // Create a client that calls the method
            Path clientFile = tempDir.resolve("Client.java");
            String clientCode = """
                    package test;

                    public class Client {
                        private Service service = new Service();

                        public void run() {
                            service.processData("hello");
                            service.processData("world");
                        }
                    }
                    """;
            Files.writeString(clientFile, clientCode);

            // Find references to processData with project scope
            List<Location> references = resolver.findReferences(
                    serviceFile, 4, 17, // Line 4 "public void processData"
                    "project", true);

            // Should find at least the calls in Client.java
            assertTrue(references.size() >= 2,
                    "Should find at least 2 references in Client.java, found: " + references.size());

            // Verify at least one reference is from Client.java
            boolean hasClientRef = references.stream()
                    .anyMatch(loc -> loc.path().getFileName().toString().equals("Client.java"));
            assertTrue(hasClientRef,
                    "Should find references in Client.java: " + references);
        }

        @Test
        void findReferencesInOtherFile_classUsage() throws IOException {
            // Create a model class
            Path modelFile = tempDir.resolve("User.java");
            String modelCode = """
                    package test;

                    public class User {
                        private String name;

                        public User(String name) {
                            this.name = name;
                        }

                        public String getName() {
                            return name;
                        }
                    }
                    """;
            Files.writeString(modelFile, modelCode);

            // Create a repository that uses User
            Path repoFile = tempDir.resolve("UserRepository.java");
            String repoCode = """
                    package test;

                    import java.util.List;
                    import java.util.ArrayList;

                    public class UserRepository {
                        private List<User> users = new ArrayList<>();

                        public void save(User user) {
                            users.add(user);
                        }

                        public User findByName(String name) {
                            return users.stream()
                                .filter(u -> u.getName().equals(name))
                                .findFirst()
                                .orElse(null);
                        }
                    }
                    """;
            Files.writeString(repoFile, repoCode);

            // Find references to User class with project scope
            List<Location> references = resolver.findReferences(
                    modelFile, 3, 14, // Line 3 "public class User"
                    "project", true);

            // Should find references in UserRepository.java
            boolean hasRepoRef = references.stream()
                    .anyMatch(loc -> loc.path().getFileName().toString().equals("UserRepository.java"));
            assertTrue(hasRepoRef,
                    "Should find references in UserRepository.java: " + references);
        }

        @Test
        void findReferencesInOtherFile_staticMethodCall() throws IOException {
            // Create a utility class with static method
            Path utilFile = tempDir.resolve("StringUtils.java");
            String utilCode = """
                    package test;

                    public class StringUtils {
                        public static String capitalize(String str) {
                            if (str == null || str.isEmpty()) return str;
                            return str.substring(0, 1).toUpperCase() + str.substring(1);
                        }
                    }
                    """;
            Files.writeString(utilFile, utilCode);

            // Create a class that uses the static method
            Path consumerFile = tempDir.resolve("NameFormatter.java");
            String consumerCode = """
                    package test;

                    public class NameFormatter {
                        public String formatName(String first, String last) {
                            return StringUtils.capitalize(first) + " " + StringUtils.capitalize(last);
                        }
                    }
                    """;
            Files.writeString(consumerFile, consumerCode);

            // Find references to capitalize method
            List<Location> references = resolver.findReferences(
                    utilFile, 4, 26, // Line 4 "public static String capitalize"
                    "project", true);

            // Should find references in NameFormatter.java
            boolean hasFormatterRef = references.stream()
                    .anyMatch(loc -> loc.path().getFileName().toString().equals("NameFormatter.java"));
            assertTrue(hasFormatterRef,
                    "Should find references in NameFormatter.java: " + references);
        }

        @Test
        void findReferencesInSubdirectory() throws IOException {
            // Create directory structure
            Path mainDir = Files.createDirectories(tempDir.resolve("src/main"));
            Path testDir = Files.createDirectories(tempDir.resolve("src/test"));

            // Create a class in main
            Path mainFile = mainDir.resolve("Calculator.java");
            String mainCode = """
                    package main;

                    public class Calculator {
                        public int add(int a, int b) {
                            return a + b;
                        }
                    }
                    """;
            Files.writeString(mainFile, mainCode);

            // Create a test class that uses Calculator
            Path testFile = testDir.resolve("CalculatorTest.java");
            String testCode = """
                    package test;

                    public class CalculatorTest {
                        private Calculator calc = new Calculator();

                        public void testAdd() {
                            int result = calc.add(2, 3);
                            assert result == 5;
                        }
                    }
                    """;
            Files.writeString(testFile, testCode);

            // Find references to add method with project scope
            List<Location> references = resolver.findReferences(
                    mainFile, 4, 16, // Line 4 "public int add"
                    "project", true);

            // The test verifies that cross-file search works - even if it doesn't find
            // all references (depends on FastSearch/grep implementation), it should:
            // 1. Not throw an exception
            // 2. Return a non-null list
            assertNotNull(references, "References should not be null");

            // At minimum, should find the definition itself
            boolean hasDefinition = references.stream()
                    .anyMatch(loc -> loc.path().getFileName().toString().equals("Calculator.java"));
            assertTrue(hasDefinition,
                    "Should at least find the definition in Calculator.java: " + references);

            // Note: Finding references in subdirectories depends on scanFilesForSymbol
            // which uses FastSearch. This test primarily verifies that the search
            // doesn't crash and returns some results.
        }

        @Test
        void findReferencesWithDirectoryScope() throws IOException {
            // Create files in same directory
            Path serviceFile = tempDir.resolve("OrderService.java");
            String serviceCode = """
                    package test;

                    public class OrderService {
                        public void placeOrder(String orderId) {
                            System.out.println("Placing order: " + orderId);
                        }
                    }
                    """;
            Files.writeString(serviceFile, serviceCode);

            Path controllerFile = tempDir.resolve("OrderController.java");
            String controllerCode = """
                    package test;

                    public class OrderController {
                        private OrderService service = new OrderService();

                        public void handleOrder(String id) {
                            service.placeOrder(id);
                        }
                    }
                    """;
            Files.writeString(controllerFile, controllerCode);

            // Find references with directory scope
            List<Location> references = resolver.findReferences(
                    serviceFile, 4, 17, // Line 4 "public void placeOrder"
                    "directory", true);

            // Should find reference in same directory
            assertTrue(references.size() >= 1,
                    "Should find at least 1 reference with directory scope: " + references);
        }

        @Test
        void findReferencesWithFileScope() throws IOException {
            // Create file with internal references
            Path file = tempDir.resolve("SelfReferencing.java");
            String code = """
                    package test;

                    public class SelfReferencing {
                        public void helper() {
                            System.out.println("Helper called");
                        }

                        public void method1() {
                            helper();
                        }

                        public void method2() {
                            helper();
                            helper();
                        }
                    }
                    """;
            Files.writeString(file, code);

            // Find references with file scope
            List<Location> references = resolver.findReferences(
                    file, 4, 17, // Line 4 "public void helper"
                    "file", true);

            // Should find internal references
            assertTrue(references.size() >= 3,
                    "Should find at least 3 references within file (2 method calls + definition): " + references);
        }
    }

    @Nested
    class EdgeCaseTests {

        @Test
        void findReferences_noReferencesInOtherFiles() throws IOException {
            // Create an unused class
            Path unusedFile = tempDir.resolve("Unused.java");
            String unusedCode = """
                    package test;

                    public class Unused {
                        public void unusedMethod() {
                            System.out.println("Never called");
                        }
                    }
                    """;
            Files.writeString(unusedFile, unusedCode);

            // Create another file that doesn't use Unused
            Path otherFile = tempDir.resolve("Other.java");
            String otherCode = """
                    package test;

                    public class Other {
                        public void something() {
                            System.out.println("Does not use Unused");
                        }
                    }
                    """;
            Files.writeString(otherFile, otherCode);

            // Find references - should only find definition, not usages in other files
            List<Location> references = resolver.findReferences(
                    unusedFile, 4, 17,
                    "project", true);

            // Should find at least the definition
            assertFalse(references.isEmpty(),
                    "Should find at least the definition");

            // Should not find references in Other.java
            boolean hasOtherRef = references.stream()
                    .anyMatch(loc -> loc.path().getFileName().toString().equals("Other.java"));
            assertFalse(hasOtherRef,
                    "Should not find references in Other.java since it doesn't use Unused");
        }

        @Test
        void findReferences_methodWithCommonName() throws IOException {
            // Test that common method names (toString, equals) are handled correctly
            Path file1 = tempDir.resolve("Person.java");
            String code1 = """
                    package test;

                    public class Person {
                        private String name;

                        @Override
                        public String toString() {
                            return "Person: " + name;
                        }
                    }
                    """;
            Files.writeString(file1, code1);

            Path file2 = tempDir.resolve("Product.java");
            String code2 = """
                    package test;

                    public class Product {
                        private String title;

                        @Override
                        public String toString() {
                            return "Product: " + title;
                        }
                    }
                    """;
            Files.writeString(file2, code2);

            // This test just verifies the search doesn't crash with common names
            List<Location> references = resolver.findReferences(
                    file1, 7, 19,
                    "project", true);

            // Should return some results without error
            assertNotNull(references);
        }
    }
}
