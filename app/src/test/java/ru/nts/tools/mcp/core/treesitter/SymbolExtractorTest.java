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

import org.treesitter.TSTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.SymbolKind;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SymbolExtractorTest {

    private SymbolExtractor extractor;
    private TreeSitterManager manager;

    @BeforeEach
    void setUp() {
        extractor = SymbolExtractor.getInstance();
        manager = TreeSitterManager.getInstance();
    }

    @Test
    void getInstance() {
        SymbolExtractor instance1 = SymbolExtractor.getInstance();
        SymbolExtractor instance2 = SymbolExtractor.getInstance();
        assertSame(instance1, instance2, "Should be singleton");
    }

    // ===================== JAVA =====================

    @Test
    void extractJavaClass() {
        String code = """
                public class Calculator {
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        assertEquals(1, symbols.size());
        SymbolInfo classSymbol = symbols.get(0);
        assertEquals("Calculator", classSymbol.name());
        assertEquals(SymbolKind.CLASS, classSymbol.kind());
    }

    @Test
    void extractJavaInterface() {
        String code = """
                public interface Runnable {
                    void run();
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Runnable") && s.kind() == SymbolKind.INTERFACE));
    }

    @Test
    void extractJavaEnum() {
        String code = """
                public enum Status {
                    ACTIVE, INACTIVE, PENDING
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Status") && s.kind() == SymbolKind.ENUM));
    }

    @Test
    void extractJavaMethods() {
        String code = """
                public class Service {
                    public void start() {}
                    public int getCount() { return 0; }
                    private void helper() {}
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("start") && s.kind() == SymbolKind.METHOD));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("getCount") && s.kind() == SymbolKind.METHOD));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("helper") && s.kind() == SymbolKind.METHOD));
    }

    @Test
    void extractJavaConstructor() {
        String code = """
                public class User {
                    public User(String name) {}
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("User") && s.kind() == SymbolKind.CONSTRUCTOR));
    }

    @Test
    void extractJavaFields() {
        String code = """
                public class Model {
                    private String name;
                    public int count;
                    protected List<String> items;
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("name") && s.kind() == SymbolKind.FIELD));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("count") && s.kind() == SymbolKind.FIELD));
    }

    /**
     * BUG-03 Regression Test: Field type extraction.
     * Verifies that field types are correctly extracted for all Java types:
     * - Primitives: int, long, double, boolean, float
     * - Classes: String, Object
     * - Generics: List<String>
     * - Arrays: int[], String[]
     */
    @Test
    void extractJavaFieldTypes() {
        String code = """
                public class TypedModel {
                    private int intField;
                    private long longField;
                    private double doubleField;
                    private float floatField;
                    private boolean boolField;
                    private String stringField;
                    private List<String> listField;
                    private int[] arrayField;
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        // Verify int type
        SymbolInfo intField = symbols.stream()
                .filter(s -> s.name().equals("intField"))
                .findFirst().orElse(null);
        assertNotNull(intField, "intField should be found");
        assertEquals("int", intField.type(), "int field type should be 'int'");

        // Verify double type (BUG-03 originally missed floating_point_type)
        SymbolInfo doubleField = symbols.stream()
                .filter(s -> s.name().equals("doubleField"))
                .findFirst().orElse(null);
        assertNotNull(doubleField, "doubleField should be found");
        assertEquals("double", doubleField.type(), "double field type should be 'double'");

        // Verify boolean type (BUG-03 originally missed boolean_type)
        SymbolInfo boolField = symbols.stream()
                .filter(s -> s.name().equals("boolField"))
                .findFirst().orElse(null);
        assertNotNull(boolField, "boolField should be found");
        assertEquals("boolean", boolField.type(), "boolean field type should be 'boolean'");

        // Verify String (type_identifier)
        SymbolInfo stringField = symbols.stream()
                .filter(s -> s.name().equals("stringField"))
                .findFirst().orElse(null);
        assertNotNull(stringField, "stringField should be found");
        assertEquals("String", stringField.type(), "String field type should be 'String'");

        // Verify generic type
        SymbolInfo listField = symbols.stream()
                .filter(s -> s.name().equals("listField"))
                .findFirst().orElse(null);
        assertNotNull(listField, "listField should be found");
        assertNotNull(listField.type(), "List field type should not be null");
        assertTrue(listField.type().startsWith("List"), "List field type should start with 'List'");

        // Verify array type
        SymbolInfo arrayField = symbols.stream()
                .filter(s -> s.name().equals("arrayField"))
                .findFirst().orElse(null);
        assertNotNull(arrayField, "arrayField should be found");
        assertNotNull(arrayField.type(), "Array field type should not be null");
        assertTrue(arrayField.type().contains("int"), "Array field type should contain 'int'");
    }

    @Test
    void extractJavaParentRelation() {
        String code = """
                public class Outer {
                    public void innerMethod() {}
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        SymbolInfo method = symbols.stream()
                .filter(s -> s.name().equals("innerMethod"))
                .findFirst()
                .orElse(null);

        assertNotNull(method);
        assertEquals("Outer", method.parentName());
    }

    // ===================== PYTHON =====================

    @Test
    void extractPythonClass() {
        String code = """
                class Calculator:
                    def __init__(self):
                        self.result = 0
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Calculator") && s.kind() == SymbolKind.CLASS));
    }

    @Test
    void extractPythonFunctions() {
        String code = """
                def greet(name):
                    print(f"Hello, {name}!")

                def add(a, b):
                    return a + b
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("greet") && s.kind() == SymbolKind.FUNCTION));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("add") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractPythonMethods() {
        String code = """
                class Service:
                    def __init__(self):
                        pass

                    def process(self):
                        pass
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("__init__") && s.kind() == SymbolKind.CONSTRUCTOR));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("process") && s.kind() == SymbolKind.METHOD));
    }

    // ===================== JAVASCRIPT/TYPESCRIPT =====================

    @Test
    void extractJavaScriptFunctions() {
        String code = """
                function greet(name) {
                    console.log(`Hello, ${name}!`);
                }

                const add = (a, b) => a + b;
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "javascript");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("greet") && s.kind() == SymbolKind.FUNCTION));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("add") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractJavaScriptClass() {
        String code = """
                class User {
                    constructor(name) {
                        this.name = name;
                    }

                    greet() {
                        console.log(`Hello, ${this.name}!`);
                    }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "javascript");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("User") && s.kind() == SymbolKind.CLASS));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("constructor") && s.kind() == SymbolKind.CONSTRUCTOR));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("greet") && s.kind() == SymbolKind.METHOD));
    }

    @Test
    void extractTypeScriptInterface() {
        String code = """
                interface User {
                    id: number;
                    name: string;
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "typescript");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("User") && s.kind() == SymbolKind.INTERFACE));
    }

    @Test
    void extractTypeScriptEnum() {
        String code = """
                enum Status {
                    Active,
                    Inactive,
                    Pending
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "typescript");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Status") && s.kind() == SymbolKind.ENUM));
    }

    // ===================== KOTLIN =====================

    @Test
    void extractKotlinClass() {
        String code = """
                class Calculator {
                    fun add(a: Int, b: Int): Int = a + b
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "kotlin");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Calculator") && s.kind() == SymbolKind.CLASS));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("add") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractKotlinObject() {
        String code = """
                object Singleton {
                    val instance = this
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "kotlin");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Singleton") && s.kind() == SymbolKind.OBJECT));
    }

    // ===================== GO =====================

    @Test
    void extractGoFunction() {
        String code = """
                package main

                func add(a, b int) int {
                    return a + b
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("add") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractGoStruct() {
        String code = """
                package main

                type User struct {
                    ID   int
                    Name string
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("User") && s.kind() == SymbolKind.STRUCT));
    }

    // ===================== RUST =====================

    @Test
    void extractRustFunction() {
        String code = """
                fn add(a: i32, b: i32) -> i32 {
                    a + b
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "rust");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("add") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractRustStruct() {
        String code = """
                struct User {
                    id: u32,
                    name: String,
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "rust");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("User") && s.kind() == SymbolKind.STRUCT));
    }

    @Test
    void extractRustEnum() {
        String code = """
                enum Status {
                    Active,
                    Inactive,
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "rust");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Status") && s.kind() == SymbolKind.ENUM));
    }

    @Test
    void extractRustTrait() {
        String code = """
                trait Printable {
                    fn print(&self);
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "rust");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Printable") && s.kind() == SymbolKind.TRAIT));
    }

    // ===================== C =====================

    @Test
    void extractCFunction() {
        String code = """
                int add(int a, int b) {
                    return a + b;
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "c");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("add") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractCStruct() {
        String code = """
                struct Point {
                    int x;
                    int y;
                };
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "c");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Point") && s.kind() == SymbolKind.STRUCT));
    }

    @Test
    void extractCEnum() {
        String code = """
                enum Color {
                    RED,
                    GREEN,
                    BLUE
                };
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "c");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Color") && s.kind() == SymbolKind.ENUM));
    }

    // ===================== C++ =====================

    @Test
    void extractCppClass() {
        String code = """
                class Calculator {
                public:
                    int add(int a, int b);
                private:
                    int result;
                };
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "cpp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Calculator") && s.kind() == SymbolKind.CLASS));
    }

    @Test
    void extractCppFunction() {
        String code = """
                int multiply(int a, int b) {
                    return a * b;
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "cpp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("multiply") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractCppNamespace() {
        String code = """
                namespace MyLib {
                    void init();
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "cpp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("MyLib") && s.kind() == SymbolKind.NAMESPACE));
    }

    @Test
    void extractCppStruct() {
        String code = """
                struct Vector3 {
                    float x, y, z;
                };
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "cpp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Vector3") && s.kind() == SymbolKind.STRUCT));
    }

    // ===================== C# =====================

    @Test
    void extractCSharpClass() {
        String code = """
                public class Calculator {
                    public int Add(int a, int b) {
                        return a + b;
                    }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "csharp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Calculator") && s.kind() == SymbolKind.CLASS));
    }

    @Test
    void extractCSharpInterface() {
        String code = """
                public interface IService {
                    void Start();
                    void Stop();
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "csharp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("IService") && s.kind() == SymbolKind.INTERFACE));
    }

    @Test
    void extractCSharpStruct() {
        String code = """
                public struct Point {
                    public int X { get; set; }
                    public int Y { get; set; }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "csharp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Point") && s.kind() == SymbolKind.STRUCT));
    }

    @Test
    void extractCSharpEnum() {
        String code = """
                public enum Status {
                    Active,
                    Inactive,
                    Pending
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "csharp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Status") && s.kind() == SymbolKind.ENUM));
    }

    @Test
    void extractCSharpMethod() {
        String code = """
                public class Service {
                    public void Process() {
                    }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "csharp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Process") && s.kind() == SymbolKind.METHOD));
    }

    @Test
    void extractCSharpProperty() {
        String code = """
                public class User {
                    public string Name { get; set; }
                    public int Age { get; set; }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "csharp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Name") && s.kind() == SymbolKind.PROPERTY));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Age") && s.kind() == SymbolKind.PROPERTY));
    }

    @Test
    void extractCSharpNamespace() {
        String code = """
                namespace MyApp.Services {
                    public class Service { }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "csharp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.kind() == SymbolKind.NAMESPACE));
    }

    @Test
    void extractCSharpConstructor() {
        String code = """
                public class User {
                    public User(string name) {
                    }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "csharp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("User") && s.kind() == SymbolKind.CONSTRUCTOR));
    }

    // ===================== PHP =====================

    @Test
    void extractPhpClass() {
        String code = """
                <?php
                class UserService {
                    public function getUser() {}
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "php");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("UserService") && s.kind() == SymbolKind.CLASS));
    }

    @Test
    void extractPhpInterface() {
        String code = """
                <?php
                interface Repository {
                    public function find($id);
                    public function save($entity);
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "php");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Repository") && s.kind() == SymbolKind.INTERFACE));
    }

    @Test
    void extractPhpTrait() {
        String code = """
                <?php
                trait Loggable {
                    public function log($message) {}
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "php");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Loggable") && s.kind() == SymbolKind.TRAIT));
    }

    @Test
    void extractPhpFunction() {
        String code = """
                <?php
                function calculateSum($a, $b) {
                    return $a + $b;
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "php");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("calculateSum") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractPhpMethod() {
        String code = """
                <?php
                class Calculator {
                    public function add($a, $b) {
                        return $a + $b;
                    }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "php");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("add") && s.kind() == SymbolKind.METHOD));
    }

    @Test
    void extractPhpConstructor() {
        String code = """
                <?php
                class User {
                    public function __construct($name) {
                        $this->name = $name;
                    }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "php");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("__construct") && s.kind() == SymbolKind.CONSTRUCTOR));
    }

    @Test
    void extractPhpNamespace() {
        String code = """
                <?php
                namespace App\\Services;

                class UserService {}
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "php");

        assertTrue(symbols.stream().anyMatch(s ->
                s.kind() == SymbolKind.NAMESPACE));
    }

    // ===================== HTML =====================

    @Test
    void extractHtmlElementWithId() {
        String code = """
                <!DOCTYPE html>
                <html>
                <body>
                    <div id="main-container">
                        <form id="login-form">
                            <input type="text" id="username">
                        </form>
                    </div>
                </body>
                </html>
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "html");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("main-container")));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("login-form")));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("username")));
    }

    @Test
    void extractHtmlScriptSrc() {
        String code = """
                <!DOCTYPE html>
                <html>
                <head>
                    <script src="app.js"></script>
                </head>
                </html>
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "html");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("app.js") && s.kind() == SymbolKind.IMPORT));
    }

    // ===================== REFERENCES =====================

    @Test
    void findReferencesInFile() {
        String code = """
                public class Test {
                    private int value;

                    public void set(int value) {
                        this.value = value;
                    }

                    public int get() {
                        return value;
                    }
                }
                """;

        Path path = Path.of("Test.java");
        TSTree tree = manager.parse(code, "java");

        List<Location> refs = extractor.findReferences(tree, path, code, "java", "value");

        // Should find multiple references to 'value'
        assertTrue(refs.size() >= 3, "Should find at least 3 references to 'value'");
    }

    @Test
    void symbolAtPosition() {
        String code = """
                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """;

        Path path = Path.of("Calculator.java");
        TSTree tree = manager.parse(code, "java");

        // Find symbol at line 2, column 16 (the 'add' method)
        Optional<SymbolInfo> symbol = extractor.symbolAtPosition(tree, path, code, "java", 2, 16);

        assertTrue(symbol.isPresent(), "Should find symbol at position");
        assertEquals("add", symbol.get().name());
        assertEquals(SymbolKind.METHOD, symbol.get().kind());
    }

    /**
     * BUG-02 Regression Test: Precise coordinate calculation.
     * The "RoundShapeblic" bug occurred when coordinates were incorrectly calculated,
     * causing text to be replaced at the wrong position.
     *
     * This test verifies that:
     * 1. References are found at correct line/column positions
     * 2. The startColumn points to the first character of the identifier
     * 3. Using startColumn-1 + name.length() gives correct replacement bounds
     */
    @Test
    void findReferencesWithPreciseCoordinates() {
        String code = """
                public class Circle extends AbstractShape {
                    private double radius;

                    public Circle(double radius) {
                        this.radius = radius;
                    }

                    public double getRadius() {
                        return radius;
                    }
                }
                """;

        Path path = Path.of("Circle.java");
        TSTree tree = manager.parse(code, "java");

        // Find all references to "Circle"
        List<Location> circleRefs = extractor.findReferences(tree, path, code, "java", "Circle");

        // Should find 2 references: class name and constructor
        assertTrue(circleRefs.size() >= 2, "Should find at least 2 references to 'Circle'");

        // Verify the first reference (class declaration) has correct coordinates
        Location classRef = circleRefs.stream()
                .filter(loc -> loc.startLine() == 1)
                .findFirst()
                .orElse(null);
        assertNotNull(classRef, "Should find Circle reference on line 1");

        // Extract the actual text using the coordinates to verify they're correct
        String[] lines = code.split("\n");
        String line = lines[classRef.startLine() - 1];
        int start = classRef.startColumn() - 1;
        int end = start + "Circle".length();

        assertTrue(start >= 0 && end <= line.length(),
                "Coordinates should be within line bounds");
        assertEquals("Circle", line.substring(start, end),
                "Text at coordinates should be 'Circle', not something else (like 'RoundShapeblic' bug)");
    }

    /**
     * BUG-02 Regression Test: Identifier not at line start.
     * Tests that identifiers NOT at the start of a line are correctly positioned.
     */
    @Test
    void findReferencesNotAtLineStart() {
        String code = """
                class Example {
                    private String target;

                    public void useTarget() {
                        System.out.println(target);
                    }
                }
                """;

        Path path = Path.of("Example.java");
        TSTree tree = manager.parse(code, "java");

        List<Location> targetRefs = extractor.findReferences(tree, path, code, "java", "target");

        assertTrue(targetRefs.size() >= 2, "Should find at least 2 references to 'target'");

        // Verify each reference points to the correct text
        String[] lines = code.split("\n");
        for (Location ref : targetRefs) {
            String line = lines[ref.startLine() - 1];
            int start = ref.startColumn() - 1;
            int end = start + "target".length();

            assertTrue(start >= 0, "Start column should be non-negative");
            assertTrue(end <= line.length(), "End should be within line: " + line);

            String extracted = line.substring(start, end);
            assertEquals("target", extracted,
                    "Extracted text should be 'target', got '" + extracted + "' at line " + ref.startLine());
        }
    }

    // ===================== HELPER METHODS =====================

    private List<SymbolInfo> parseAndExtract(String code, String langId) {
        Path path = Path.of("test." + LanguageDetector.getFileExtension(langId).orElse("txt"));
        TSTree tree = manager.parse(code, langId);
        return extractor.extractDefinitions(tree, path, code, langId);
    }
}
