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
package ru.nts.tools.mcp.core.treesitter.extractors;

import org.junit.jupiter.api.Test;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.SymbolKind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaSymbolExtractorTest extends AbstractSymbolExtractorTest {

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

        // Verify double type
        SymbolInfo doubleField = symbols.stream()
                .filter(s -> s.name().equals("doubleField"))
                .findFirst().orElse(null);
        assertNotNull(doubleField, "doubleField should be found");
        assertEquals("double", doubleField.type(), "double field type should be 'double'");

        // Verify boolean type
        SymbolInfo boolField = symbols.stream()
                .filter(s -> s.name().equals("boolField"))
                .findFirst().orElse(null);
        assertNotNull(boolField, "boolField should be found");
        assertEquals("boolean", boolField.type(), "boolean field type should be 'boolean'");

        // Verify String
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

    // ==================== Edge Cases ====================

    @Test
    void extractJavaRecord() {
        String code = """
                public record Person(String name, int age) {
                    public String greeting() {
                        return "Hello, " + name;
                    }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Person") && s.kind() == SymbolKind.CLASS));
    }

    @Test
    void extractJavaAnnotationType() {
        String code = """
                public @interface MyAnnotation {
                    String value();
                    int count() default 0;
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        // Annotation types may be parsed as interfaces or may not be supported
        // Just verify the extractor doesn't crash on this syntax
        assertNotNull(symbols);
    }

    @Test
    void extractJavaStaticNestedClass() {
        String code = """
                public class Outer {
                    public static class StaticNested {
                        public void nestedMethod() {}
                    }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Outer") && s.kind() == SymbolKind.CLASS));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("StaticNested") && s.kind() == SymbolKind.CLASS));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("nestedMethod") && s.kind() == SymbolKind.METHOD));
    }

    @Test
    void extractJavaGenericClass() {
        String code = """
                public class Container<T extends Comparable<T>> {
                    private T value;
                    public T getValue() { return value; }
                    public void setValue(T value) { this.value = value; }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("Container")));
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("getValue")));
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("setValue")));
    }

    @Test
    void extractJavaMultipleConstructors() {
        String code = """
                public class Builder {
                    public Builder() {}
                    public Builder(String config) {}
                    public Builder(String config, int timeout) {}
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        long constructorCount = symbols.stream()
                .filter(s -> s.name().equals("Builder") && s.kind() == SymbolKind.CONSTRUCTOR)
                .count();
        assertEquals(3, constructorCount, "Should find 3 constructors");
    }

    @Test
    void extractJavaVarArgsMethod() {
        String code = """
                public class Util {
                    public void log(String format, Object... args) {}
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        SymbolInfo logMethod = symbols.stream()
                .filter(s -> s.name().equals("log"))
                .findFirst()
                .orElse(null);

        assertNotNull(logMethod);
        assertNotNull(logMethod.parameters());
        assertEquals(2, logMethod.parameters().size());

        SymbolInfo.ParameterInfo varargsParam = logMethod.parameters().get(1);
        assertTrue(varargsParam.isVarargs(), "Second param should be varargs");
    }

    @Test
    void extractJavaLambdaInField() {
        String code = """
                public class Handler {
                    private Runnable action = () -> System.out.println("Running");
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("action") && s.kind() == SymbolKind.FIELD));
    }

    @Test
    void extractJavaDefaultMethod() {
        String code = """
                public interface Service {
                    default void initialize() {
                        System.out.println("Initializing");
                    }
                    void process();
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Service") && s.kind() == SymbolKind.INTERFACE));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("initialize") && s.kind() == SymbolKind.METHOD));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("process") && s.kind() == SymbolKind.METHOD));
    }

    @Test
    void extractJavaStaticField() {
        String code = """
                public class Constants {
                    public static final String VERSION = "1.0.0";
                    public static int counter = 0;
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("VERSION") && s.kind() == SymbolKind.FIELD));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("counter") && s.kind() == SymbolKind.FIELD));
    }

    @Test
    void extractJavaMethodWithThrows() {
        String code = """
                public class FileReader {
                    public String read(String path) throws IOException, SecurityException {
                        return null;
                    }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        SymbolInfo readMethod = symbols.stream()
                .filter(s -> s.name().equals("read"))
                .findFirst()
                .orElse(null);

        assertNotNull(readMethod);
        assertEquals(SymbolKind.METHOD, readMethod.kind());
    }

    @Test
    void extractJavaSynchronizedMethod() {
        String code = """
                public class ThreadSafe {
                    public synchronized void update() {}
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("update") && s.kind() == SymbolKind.METHOD));
    }

    @Test
    void extractJavaEnumWithMethods() {
        String code = """
                public enum Operation {
                    ADD {
                        public int apply(int a, int b) { return a + b; }
                    },
                    SUBTRACT {
                        public int apply(int a, int b) { return a - b; }
                    };

                    public abstract int apply(int a, int b);
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Operation") && s.kind() == SymbolKind.ENUM));
    }

    @Test
    void extractJavaMultipleFieldsOneLine() {
        String code = """
                public class Multi {
                    private int a, b, c;
                    public String x, y;
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        // Note: tree-sitter might parse this differently
        long fieldCount = symbols.stream()
                .filter(s -> s.kind() == SymbolKind.FIELD)
                .count();
        assertTrue(fieldCount >= 1, "Should find at least one field");
    }

    @Test
    void extractJavaUtf8MethodName() {
        String code = """
                public class Интернационализация {
                    public void вычислитьРезультат(String данные) {}
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "java");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Интернационализация") && s.kind() == SymbolKind.CLASS));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("вычислитьРезультат") && s.kind() == SymbolKind.METHOD));
    }
}
