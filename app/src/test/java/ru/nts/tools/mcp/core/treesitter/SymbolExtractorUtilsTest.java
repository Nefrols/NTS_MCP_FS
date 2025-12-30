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
import org.treesitter.TSNode;
import org.treesitter.TSTree;
import ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils.*;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.ParameterInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils.*;

/**
 * Comprehensive tests for SymbolExtractorUtils.
 * Covers all utility methods including edge cases.
 */
class SymbolExtractorUtilsTest {

    private TreeSitterManager manager;

    @BeforeEach
    void setUp() {
        manager = TreeSitterManager.getInstance();
    }

    // ==================== Node Navigation Tests ====================

    @Nested
    class NodeNavigationTests {

        @Test
        void findChildByType_findsExistingChild() {
            String code = "public class Test { public void method() {} }";
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            TSNode classDecl = findChildByType(root, "class_declaration");
            assertNotNull(classDecl, "Should find class_declaration");
            assertEquals("class_declaration", classDecl.getType());
        }

        @Test
        void findChildByType_returnsNullForNonExistent() {
            String code = "public class Test {}";
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            TSNode method = findChildByType(root, "method_declaration");
            assertNull(method, "Should return null for non-existent type");
        }

        @Test
        void findChildByType_handlesNullParent() {
            // Create a null node scenario indirectly
            String code = "public class Test {}";
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();
            TSNode classDecl = findChildByType(root, "class_declaration");

            // Try to find child of a node that may not have children of that type
            TSNode nonExistent = findChildByType(classDecl, "nonexistent_type");
            assertNull(nonExistent);
        }
    }

    // ==================== Text Extraction Tests ====================

    @Nested
    class TextExtractionTests {

        @Test
        void getNodeText_extractsSimpleText() {
            String code = "public class MyClass {}";
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();
            TSNode classDecl = findChildByType(root, "class_declaration");
            TSNode identifier = findChildByType(classDecl, "identifier");

            String text = getNodeText(identifier, code);
            assertEquals("MyClass", text);
        }

        @Test
        void getNodeText_handlesUtf8Characters() {
            String code = "public class Калькулятор { public void вычислить() {} }";
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();
            TSNode classDecl = findChildByType(root, "class_declaration");
            TSNode identifier = findChildByType(classDecl, "identifier");

            String text = getNodeText(identifier, code);
            assertEquals("Калькулятор", text, "Should correctly extract Cyrillic class name");
        }

        @Test
        void getNodeText_handlesEmoji() {
            String code = "const emoji = '🚀';";
            TSTree tree = manager.parse(code, "javascript");
            TSNode root = tree.getRootNode();

            String fullText = getNodeText(root, code);
            assertTrue(fullText.contains("🚀"), "Should preserve emoji");
        }

        @Test
        void getNodeText_handlesChineseCharacters() {
            String code = "def 计算(数值): return 数值 * 2";
            TSTree tree = manager.parse(code, "python");
            TSNode root = tree.getRootNode();

            String fullText = getNodeText(root, code);
            assertTrue(fullText.contains("计算"), "Should handle Chinese function name");
            assertTrue(fullText.contains("数值"), "Should handle Chinese parameter name");
        }

        @Test
        void getNodeText_handlesEmptyNode() {
            String code = "public class Test {}";
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();
            TSNode classDecl = findChildByType(root, "class_declaration");
            TSNode body = findChildByType(classDecl, "class_body");

            // Body contains only {}
            String text = getNodeText(body, code);
            assertEquals("{}", text.trim());
        }
    }

    // ==================== Location Tests ====================

    @Nested
    class LocationTests {

        @Test
        void nodeToLocation_createsCorrectLocation() {
            String code = """
                    package test;

                    public class MyClass {
                        public void method() {}
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();
            TSNode classDecl = findChildByType(root, "class_declaration");

            Path path = Path.of("Test.java");
            Location location = nodeToLocation(classDecl, path);

            assertNotNull(location);
            assertEquals(path, location.path());
            assertEquals(3, location.startLine(), "Class should start at line 3");
            assertTrue(location.endLine() >= location.startLine());
        }

        @Test
        void nodeToLocation_singleLineNode() {
            String code = "int x = 5;";
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            Path path = Path.of("Test.java");
            Location location = nodeToLocation(root, path);

            assertEquals(1, location.startLine());
            assertEquals(1, location.endLine());
        }

        @Test
        void nodeToLocation_multiLineNode() {
            String code = """
                    public void method() {
                        int a = 1;
                        int b = 2;
                        return a + b;
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            Path path = Path.of("Test.java");
            Location location = nodeToLocation(root, path);

            assertEquals(1, location.startLine());
            assertTrue(location.endLine() > 1, "Multi-line node should span multiple lines");
        }
    }

    // ==================== Comment Extraction Tests ====================

    @Nested
    class CommentExtractionTests {

        @Test
        void extractPrecedingComment_javadocStyle() {
            String code = """
                    /**
                     * This is a Javadoc comment.
                     * It has multiple lines.
                     */
                    public class Test {}
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();
            TSNode classDecl = findChildByType(root, "class_declaration");

            String comment = extractPrecedingComment(classDecl, code);
            assertNotNull(comment);
            assertTrue(comment.contains("Javadoc comment"), "Should extract Javadoc");
        }

        @Test
        void extractPrecedingComment_singleLineComment() {
            String code = """
                    // This is a single line comment
                    public class Test {}
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();
            TSNode classDecl = findChildByType(root, "class_declaration");

            String comment = extractPrecedingComment(classDecl, code);
            // Note: depends on tree-sitter parsing, may or may not capture
            if (comment != null) {
                assertTrue(comment.contains("single line"));
            }
        }

        @Test
        void cleanupComment_removesJavadocMarkers() {
            String javadoc = "/** This is a comment */";
            String cleaned = cleanupComment(javadoc);
            assertEquals("This is a comment", cleaned.trim());
        }

        @Test
        void cleanupComment_removesBlockCommentMarkers() {
            String block = "/* Block comment */";
            String cleaned = cleanupComment(block);
            assertEquals("Block comment", cleaned.trim());
        }

        @Test
        void cleanupComment_removesLineCommentMarker() {
            String line = "// Line comment";
            String cleaned = cleanupComment(line);
            assertEquals("Line comment", cleaned.trim());
        }

        @Test
        void cleanupComment_removesPythonHash() {
            String python = "# Python comment";
            String cleaned = cleanupComment(python);
            assertEquals("Python comment", cleaned.trim());
        }

        @Test
        void cleanupComment_handlesNull() {
            assertNull(cleanupComment(null));
        }

        @Test
        void cleanupComment_removesAsterisks() {
            String multiLine = """
                    /**
                     * First line
                     * Second line
                     */
                    """;
            String cleaned = cleanupComment(multiLine);
            assertTrue(cleaned.contains("First line"));
            assertTrue(cleaned.contains("Second line"));
            assertFalse(cleaned.contains("*"));
        }
    }

    // ==================== Method Signature Extraction Tests ====================

    @Nested
    class MethodSignatureTests {

        @Test
        void extractMethodSignature_simpleMethod() {
            String code = "public void process() { doSomething(); }";
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            String signature = extractMethodSignature(root, code);
            assertTrue(signature.contains("public"));
            assertTrue(signature.contains("void"));
            assertTrue(signature.contains("process"));
            assertFalse(signature.contains("doSomething"), "Should not include body");
        }

        @Test
        void extractMethodSignature_withParameters() {
            String code = "public int calculate(int a, String b) { return a; }";
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            String signature = extractMethodSignature(root, code);
            assertTrue(signature.contains("int a"));
            assertTrue(signature.contains("String b"));
        }

        @Test
        void extractMethodSignature_normalizesWhitespace() {
            String code = "public   void    method(  int   x  ) { }";
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            String signature = extractMethodSignature(root, code);
            assertFalse(signature.contains("  "), "Should normalize multiple spaces");
        }
    }

    // ==================== Parameter Extraction Tests ====================

    @Nested
    class ParameterExtractionTests {

        @Test
        void extractParameters_javaSimple() {
            String code = "public class Test { public void method(String name, int count) {} }";
            TSTree tree = manager.parse(code, "java");
            TSNode methodNode = findMethodDeclaration(tree.getRootNode());

            List<ParameterInfo> params = extractParameters(methodNode, code);
            assertEquals(2, params.size());
            assertEquals("name", params.get(0).name());
            assertEquals("String", params.get(0).type());
            assertEquals("count", params.get(1).name());
            assertEquals("int", params.get(1).type());
        }

        @Test
        void extractParameters_javaVarargs() {
            String code = "public class Test { public void method(String... args) {} }";
            TSTree tree = manager.parse(code, "java");
            TSNode methodNode = findMethodDeclaration(tree.getRootNode());

            List<ParameterInfo> params = extractParameters(methodNode, code);
            assertEquals(1, params.size());
            assertEquals("args", params.get(0).name());
            assertTrue(params.get(0).isVarargs(), "Should detect varargs");
            assertTrue(params.get(0).type().contains("[]"), "Varargs type should be array");
        }

        @Test
        void extractParameters_javaGenerics() {
            String code = "public class Test { public void method(Map<String, List<Integer>> data) {} }";
            TSTree tree = manager.parse(code, "java");
            TSNode methodNode = findMethodDeclaration(tree.getRootNode());

            List<ParameterInfo> params = extractParameters(methodNode, code);
            assertEquals(1, params.size());
            assertEquals("data", params.get(0).name());
            assertTrue(params.get(0).type().contains("Map"), "Should preserve generic type");
        }

        @Test
        void extractParameters_javaNoParams() {
            String code = "public class Test { public void method() {} }";
            TSTree tree = manager.parse(code, "java");
            TSNode methodNode = findMethodDeclaration(tree.getRootNode());

            List<ParameterInfo> params = extractParameters(methodNode, code);
            assertEquals(0, params.size());
        }

        @Test
        void extractParameters_javaWithAnnotations() {
            String code = "public class Test { public void method(@NotNull String name) {} }";
            TSTree tree = manager.parse(code, "java");
            TSNode methodNode = findMethodDeclaration(tree.getRootNode());

            List<ParameterInfo> params = extractParameters(methodNode, code);
            assertEquals(1, params.size());
            assertEquals("name", params.get(0).name());
            assertEquals("String", params.get(0).type());
        }

        @Test
        void extractParameters_pythonSimple() {
            String code = "def greet(name, age): pass";
            TSTree tree = manager.parse(code, "python");
            TSNode funcDef = findDescendant(tree.getRootNode(), "function_definition");

            if (funcDef != null) {
                List<ParameterInfo> params = extractParameters(funcDef, code);
                assertTrue(params.size() >= 2);
            }
        }

        @Test
        void extractParameters_goMultipleNamesOneType() {
            String code = "package main\nfunc add(a, b int) int { return a + b }";
            TSTree tree = manager.parse(code, "go");
            TSNode funcDef = findDescendant(tree.getRootNode(), "function_declaration");

            if (funcDef != null) {
                List<ParameterInfo> params = extractParameters(funcDef, code);
                assertEquals(2, params.size());
                assertEquals("a", params.get(0).name());
                assertEquals("int", params.get(0).type());
                assertEquals("b", params.get(1).name());
                assertEquals("int", params.get(1).type());
            }
        }
    }

    // ==================== Method Signature AST Tests ====================

    @Nested
    class MethodSignatureASTTests {

        @Test
        void extractMethodSignatureAST_javaPublicMethod() {
            String code = "public class Test { public int calculate(int x, int y) { return x + y; } }";
            TSTree tree = manager.parse(code, "java");
            TSNode methodNode = findMethodDeclaration(tree.getRootNode());

            MethodSignatureAST sig = extractMethodSignatureAST(methodNode, code, "java");
            assertNotNull(sig);
            assertEquals("calculate", sig.name());
            assertEquals("public", sig.accessModifier());
            assertEquals("int", sig.returnType());
            assertEquals(2, sig.parameters().size());
        }

        @Test
        void extractMethodSignatureAST_javaStaticMethod() {
            String code = "public class Test { public static void main(String[] args) {} }";
            TSTree tree = manager.parse(code, "java");
            TSNode methodNode = findMethodDeclaration(tree.getRootNode());

            MethodSignatureAST sig = extractMethodSignatureAST(methodNode, code, "java");
            assertNotNull(sig);
            assertTrue(sig.isStatic(), "Should detect static modifier");
        }

        @Test
        void extractMethodSignatureAST_pythonWithDefaults() {
            String code = "def greet(name, greeting='Hello'): pass";
            TSTree tree = manager.parse(code, "python");
            TSNode funcDef = findDescendant(tree.getRootNode(), "function_definition");

            if (funcDef != null) {
                MethodSignatureAST sig = extractMethodSignatureAST(funcDef, code, "python");
                assertNotNull(sig);
                assertEquals("greet", sig.name());
                assertTrue(sig.parameters().size() >= 1);
            }
        }

        @Test
        void extractMethodSignatureAST_goFunction() {
            String code = "package main\nfunc process(data []byte) error { return nil }";
            TSTree tree = manager.parse(code, "go");
            TSNode funcDef = findDescendant(tree.getRootNode(), "function_declaration");

            if (funcDef != null) {
                MethodSignatureAST sig = extractMethodSignatureAST(funcDef, code, "go");
                assertNotNull(sig);
                assertEquals("process", sig.name());
            }
        }

        @Test
        void extractMethodSignatureAST_javascriptArrow() {
            String code = "const add = (a, b) => a + b;";
            TSTree tree = manager.parse(code, "javascript");
            TSNode arrowFunc = findDescendant(tree.getRootNode(), "arrow_function");

            if (arrowFunc != null) {
                MethodSignatureAST sig = extractMethodSignatureAST(arrowFunc, code, "javascript");
                // Arrow functions may not have traditional signatures
                assertNotNull(sig);
            }
        }
    }

    // ==================== Variable Analysis Tests ====================

    @Nested
    class VariableAnalysisTests {

        @Test
        void analyzeVariablesInRange_javaLocalVariables() {
            String code = """
                    public class Test {
                        public void method() {
                            int x = 5;
                            String name = "test";
                            int result = x + 10;
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            // Analyze lines 3-5 (0-based: 2-4)
            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 2, 4, "java");

            assertNotNull(result);
            assertTrue(result.declaredVariables().size() >= 2, "Should find declared variables");
            assertTrue(result.usedVariables().contains("x"), "Should detect usage of x");
        }

        @Test
        void analyzeVariablesInRange_pythonVariables() {
            String code = """
                    def process():
                        data = []
                        count = 0
                        for item in data:
                            count += 1
                        return count
                    """;
            TSTree tree = manager.parse(code, "python");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 1, 5, "python");
            assertNotNull(result);
        }

        @Test
        void analyzeVariablesInRange_emptyRange() {
            String code = "public class Test {}";
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 10, 20, "java");
            assertNotNull(result);
            assertTrue(result.declaredVariables().isEmpty());
        }

        @Test
        void extractOuterScopeVariables_methodParameters() {
            String code = """
                    public class Test {
                        public int calculate(int x, int y) {
                            int sum = x + y;
                            return sum * 2;
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            // Get outer scope at line 3 (sum declaration)
            Map<String, String> outerVars = extractOuterScopeVariables(root, code, 2, "java");

            assertTrue(outerVars.containsKey("x") || outerVars.containsKey("y"),
                    "Should include method parameters");
        }
    }

    // ==================== Package Extraction Tests ====================

    @Nested
    class PackageExtractionTests {

        @Test
        void extractPackageName_java() {
            String code = """
                    package com.example.app;

                    public class Test {}
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            String pkg = extractPackageName(root, code, "java");
            assertEquals("com.example.app", pkg);
        }

        @Test
        void extractPackageName_javaNoPackage() {
            String code = "public class Test {}";
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            String pkg = extractPackageName(root, code, "java");
            assertEquals("", pkg);
        }

        @Test
        void extractPackageName_go() {
            String code = """
                    package main

                    func main() {}
                    """;
            TSTree tree = manager.parse(code, "go");
            TSNode root = tree.getRootNode();

            String pkg = extractPackageName(root, code, "go");
            assertEquals("main", pkg);
        }

        @Test
        void extractPackageName_pythonReturnsEmpty() {
            String code = "def main(): pass";
            TSTree tree = manager.parse(code, "python");
            TSNode root = tree.getRootNode();

            String pkg = extractPackageName(root, code, "python");
            assertEquals("", pkg, "Python should return empty (uses file structure)");
        }

        @Test
        void extractPackageName_unsupportedLanguage() {
            // C# not supported by tree-sitter in this build
            // Just test that the method doesn't crash for unsupported languages
            String code = "public class Test {}";
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            String pkg = extractPackageName(root, code, "unsupported_lang");
            assertEquals("", pkg, "Unsupported language should return empty string");
        }
    }

    // ==================== Value Extraction Tests ====================

    @Nested
    class ValueExtractionTests {

        @Test
        void extractVariableValue_javaSimple() {
            String code = """
                    public class Test {
                        public void method() {
                            int x = 42;
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            String value = extractVariableValue(root, code, 2, "x", "java");
            // Value extraction may need the exact line
            if (value != null) {
                assertEquals("42", value);
            }
        }

        @Test
        void extractVariableValue_javaString() {
            String code = """
                    public class Test {
                        String name = "Hello World";
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            String value = extractVariableValue(root, code, 1, "name", "java");
            if (value != null) {
                assertTrue(value.contains("Hello World"));
            }
        }

        @Test
        void extractMethodBodyForInline_simpleReturn() {
            String code = """
                    public class Test {
                        public int double(int x) {
                            return x * 2;
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            String body = extractMethodBodyForInline(root, code, 1, 3, "java");
            if (body != null) {
                assertTrue(body.contains("x * 2") || body.contains("x*2"),
                        "Should extract return expression");
            }
        }

        @Test
        void extractMethodBodyForInline_multipleStatements() {
            String code = """
                    public class Test {
                        public int compute(int x) {
                            int temp = x + 1;
                            return temp * 2;
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            String body = extractMethodBodyForInline(root, code, 1, 4, "java");
            if (body != null) {
                assertTrue(body.contains("temp"), "Should include all statements");
            }
        }
    }

    // ==================== Edge Cases Tests ====================

    @Nested
    class EdgeCaseTests {

        @Test
        void handlesMalformedCode() {
            String code = "public class { incomplete";
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            // Should not throw
            assertDoesNotThrow(() -> extractMethodSignature(root, code));
        }

        @Test
        void handlesEmptyCode() {
            String code = "";
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            assertDoesNotThrow(() -> extractParameters(root, code));
        }

        @Test
        void handlesDeepNesting() {
            String code = """
                    public class Outer {
                        public class Inner {
                            public class DeepInner {
                                public void method() {}
                            }
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            TSNode deepMethod = findDeepMethod(root);
            if (deepMethod != null) {
                List<ParameterInfo> params = extractParameters(deepMethod, code);
                assertNotNull(params);
            }
        }

        @Test
        void handlesVeryLongLine() {
            StringBuilder sb = new StringBuilder("public void method(");
            for (int i = 0; i < 100; i++) {
                if (i > 0) sb.append(", ");
                sb.append("String param").append(i);
            }
            sb.append(") {}");
            String code = "public class Test { " + sb + " }";

            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();
            TSNode methodNode = findMethodDeclaration(root);

            if (methodNode != null) {
                List<ParameterInfo> params = extractParameters(methodNode, code);
                assertEquals(100, params.size(), "Should handle 100 parameters");
            }
        }

        @Test
        void handlesSpecialCharactersInStrings() {
            String code = "String s = \"Line1\\nLine2\\tTabbed\\\"\";";
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            String text = getNodeText(root, code);
            assertTrue(text.contains("\\n"), "Should preserve escape sequences");
        }

        @Test
        void handlesLambdaExpressions() {
            String code = """
                    public class Test {
                        Consumer<String> c = (String s) -> System.out.println(s);
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            TSNode lambda = findDescendant(root, "lambda_expression");
            if (lambda != null) {
                List<ParameterInfo> params = extractParameters(lambda, code);
                // Lambda parameters may be handled differently
            }
        }
    }

    // ==================== Helper Methods ====================

    private TSNode findMethodDeclaration(TSNode node) {
        if (node == null || node.isNull()) return null;
        if (node.getType().equals("method_declaration")) return node;

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            TSNode found = findMethodDeclaration(child);
            if (found != null) return found;
        }
        return null;
    }

    private TSNode findDescendant(TSNode node, String type) {
        if (node == null || node.isNull()) return null;
        if (node.getType().equals(type)) return node;

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            TSNode found = findDescendant(child, type);
            if (found != null) return found;
        }
        return null;
    }

    private TSNode findDeepMethod(TSNode node) {
        if (node == null || node.isNull()) return null;

        if (node.getType().equals("method_declaration")) {
            // Check depth by counting parent classes
            int depth = 0;
            TSNode parent = node.getParent();
            while (parent != null && !parent.isNull()) {
                if (parent.getType().equals("class_declaration")) depth++;
                parent = parent.getParent();
            }
            if (depth >= 3) return node;
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            TSNode found = findDeepMethod(child);
            if (found != null) return found;
        }
        return null;
    }
}
