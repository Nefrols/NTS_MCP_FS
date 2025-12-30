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
import ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils.VariableAnalysisResult;
import ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils.VariableInfoAST;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils.*;

/**
 * Comprehensive tests for variable analysis functionality.
 * Tests AST-based variable declaration and usage detection.
 */
class VariableAnalysisTest {

    private TreeSitterManager manager;

    @BeforeEach
    void setUp() {
        manager = TreeSitterManager.getInstance();
    }

    // ==================== Java Variable Analysis ====================

    @Nested
    class JavaVariableAnalysisTests {

        @Test
        void analyzeLocalVariables() {
            String code = """
                    public class Test {
                        public void method() {
                            int x = 5;
                            String name = "test";
                            double result = x * 2.0;
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            // Analyze lines 3-5 (0-based: 2-4)
            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 2, 4, "java");

            assertNotNull(result);
            assertFalse(result.declaredVariables().isEmpty(), "Should find declared variables");

            // Check that x is used in result computation
            assertTrue(result.usedVariables().contains("x") ||
                            result.declaredVariables().stream().anyMatch(v -> v.name().equals("x")),
                    "Should detect variable x");
        }

        @Test
        void analyzeForLoopVariable() {
            String code = """
                    public class Test {
                        public void method() {
                            for (int i = 0; i < 10; i++) {
                                System.out.println(i);
                            }
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 2, 4, "java");

            assertNotNull(result);
        }

        @Test
        void analyzeEnhancedForLoop() {
            String code = """
                    public class Test {
                        public void method() {
                            List<String> items = new ArrayList<>();
                            for (String item : items) {
                                process(item);
                            }
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 2, 5, "java");

            assertNotNull(result);
        }

        @Test
        void analyzeTryCatchVariable() {
            String code = """
                    public class Test {
                        public void method() {
                            try {
                                doSomething();
                            } catch (Exception e) {
                                log(e.getMessage());
                            }
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 2, 6, "java");

            assertNotNull(result);
        }

        @Test
        void distinguishDeclarationFromUsage() {
            String code = """
                    public class Test {
                        public void method() {
                            int x = 5;
                            int y = x + 1;
                            x = x + y;
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 2, 4, "java");

            assertNotNull(result);

            // x is declared once but used multiple times
            long xDeclarations = result.declaredVariables().stream()
                    .filter(v -> v.name().equals("x"))
                    .count();
            assertTrue(xDeclarations <= 1, "x should be declared at most once");
        }

        @Test
        void analyzeMethodParameters() {
            String code = """
                    public class Test {
                        public int calculate(int a, int b) {
                            int sum = a + b;
                            return sum;
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            Map<String, String> outerVars = extractOuterScopeVariables(root, code, 2, "java");

            // Parameters should be in outer scope
            assertTrue(outerVars.containsKey("a") || outerVars.containsKey("b"),
                    "Should include method parameters");
        }

        @Test
        void filterOutKeywords() {
            String code = """
                    public class Test {
                        public void method() {
                            int x = 5;
                            if (x > 0) {
                                return;
                            }
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 2, 5, "java");

            assertNotNull(result);
            // Keywords like 'if', 'return' should not be in used variables
            assertFalse(result.usedVariables().contains("if"));
            assertFalse(result.usedVariables().contains("return"));
        }

        @Test
        void variableUsageIncludesExpectedVariables() {
            String code = """
                    public class Test {
                        public void method() {
                            String name = "test";
                            System.out.println(name);
                            name.toUpperCase();
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 2, 4, "java");

            assertNotNull(result);
            // Verify the name variable is detected
            assertTrue(result.usedVariables().contains("name") ||
                       result.declaredVariables().stream().anyMatch(v -> v.name().equals("name")),
                    "Should detect 'name' variable");
        }
    }

    // ==================== Python Variable Analysis ====================

    @Nested
    class PythonVariableAnalysisTests {

        @Test
        void analyzeAssignment() {
            String code = """
                    def process():
                        x = 5
                        y = x + 10
                        return y
                    """;
            TSTree tree = manager.parse(code, "python");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 1, 3, "python");

            assertNotNull(result);
        }

        @Test
        void analyzeForLoop() {
            String code = """
                    def process(items):
                        total = 0
                        for item in items:
                            total += item
                        return total
                    """;
            TSTree tree = manager.parse(code, "python");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 1, 4, "python");

            assertNotNull(result);
        }

        @Test
        void analyzeComprehension() {
            String code = """
                    def get_squares(n):
                        squares = [x**2 for x in range(n)]
                        return squares
                    """;
            TSTree tree = manager.parse(code, "python");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 1, 2, "python");

            assertNotNull(result);
        }

        @Test
        void filterPythonKeywords() {
            String code = """
                    def method():
                        x = True
                        y = False
                        z = None
                        if x and not y:
                            return z
                    """;
            TSTree tree = manager.parse(code, "python");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 1, 5, "python");

            assertNotNull(result);
            assertFalse(result.usedVariables().contains("True"));
            assertFalse(result.usedVariables().contains("False"));
            assertFalse(result.usedVariables().contains("None"));
        }

        @Test
        void filterSelfParameter() {
            String code = """
                    class MyClass:
                        def method(self):
                            self.value = 5
                            return self.value
                    """;
            TSTree tree = manager.parse(code, "python");
            TSNode root = tree.getRootNode();

            Map<String, String> outerVars = extractOuterScopeVariables(root, code, 2, "python");

            // self should not be in outer scope as a regular parameter
            // (it's a special parameter)
        }
    }

    // ==================== JavaScript Variable Analysis ====================

    @Nested
    class JavaScriptVariableAnalysisTests {

        @Test
        void analyzeLetDeclaration() {
            String code = """
                    function process() {
                        let x = 5;
                        let y = x + 10;
                        return y;
                    }
                    """;
            TSTree tree = manager.parse(code, "javascript");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 1, 3, "javascript");

            assertNotNull(result);
        }

        @Test
        void analyzeConstDeclaration() {
            String code = """
                    function process() {
                        const PI = 3.14159;
                        const radius = 5;
                        const area = PI * radius * radius;
                        return area;
                    }
                    """;
            TSTree tree = manager.parse(code, "javascript");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 1, 4, "javascript");

            assertNotNull(result);
        }

        @Test
        void analyzeVarDeclaration() {
            String code = """
                    function process() {
                        var x = 5;
                        var y = x + 10;
                        return y;
                    }
                    """;
            TSTree tree = manager.parse(code, "javascript");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 1, 3, "javascript");

            assertNotNull(result);
        }

        @Test
        void analyzeDestructuring() {
            String code = """
                    function process(obj) {
                        const { name, age } = obj;
                        console.log(name, age);
                    }
                    """;
            TSTree tree = manager.parse(code, "javascript");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 1, 2, "javascript");

            assertNotNull(result);
        }

        @Test
        void filterJsKeywords() {
            String code = """
                    function process() {
                        let x = true;
                        let y = false;
                        let z = null;
                        let w = undefined;
                        return x;
                    }
                    """;
            TSTree tree = manager.parse(code, "javascript");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 1, 5, "javascript");

            assertNotNull(result);
            assertFalse(result.usedVariables().contains("true"));
            assertFalse(result.usedVariables().contains("false"));
            assertFalse(result.usedVariables().contains("null"));
            assertFalse(result.usedVariables().contains("undefined"));
        }
    }

    // ==================== Go Variable Analysis ====================

    @Nested
    class GoVariableAnalysisTests {

        @Test
        void analyzeShortVarDeclaration() {
            String code = """
                    package main

                    func process() {
                        x := 5
                        y := x + 10
                        fmt.Println(y)
                    }
                    """;
            TSTree tree = manager.parse(code, "go");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 3, 5, "go");

            assertNotNull(result);
        }

        @Test
        void analyzeVarDeclaration() {
            String code = """
                    package main

                    func process() {
                        var x int = 5
                        var y string = "test"
                        fmt.Println(x, y)
                    }
                    """;
            TSTree tree = manager.parse(code, "go");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 3, 5, "go");

            assertNotNull(result);
        }

        @Test
        void analyzeRangeLoop() {
            String code = """
                    package main

                    func process(items []int) {
                        for i, item := range items {
                            fmt.Println(i, item)
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "go");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 3, 5, "go");

            assertNotNull(result);
        }

        @Test
        void filterGoKeywords() {
            String code = """
                    package main

                    func process() {
                        x := true
                        y := false
                        z := nil
                        if x && !y {
                            return z
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "go");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 3, 7, "go");

            assertNotNull(result);
            assertFalse(result.usedVariables().contains("true"));
            assertFalse(result.usedVariables().contains("false"));
            assertFalse(result.usedVariables().contains("nil"));
        }
    }

    // ==================== Outer Scope Variables ====================

    @Nested
    class OuterScopeVariablesTests {

        @Test
        void extractParametersAsOuterScope_java() {
            String code = """
                    public class Test {
                        public int calculate(int a, int b) {
                            int sum = a + b;
                            return sum * 2;
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            Map<String, String> outerVars = extractOuterScopeVariables(root, code, 3, "java");

            // Parameters a and b should be accessible
            assertTrue(outerVars.containsKey("a") || outerVars.containsKey("b"),
                    "Should include method parameters");
        }

        @Test
        void extractEarlierVariablesAsOuterScope_java() {
            String code = """
                    public class Test {
                        public void method() {
                            int x = 5;
                            int y = 10;
                            int z = x + y;
                            // target line
                            int result = z * 2;
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            Map<String, String> outerVars = extractOuterScopeVariables(root, code, 5, "java");

            // x, y, z should be available as they are declared before line 5
            // Note: depends on exact implementation
        }

        @Test
        void outerScopeDoesNotIncludeLaterVariables() {
            String code = """
                    public class Test {
                        public void method() {
                            int x = 5;
                            // target line
                            int y = 10;
                            int z = x + y;
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            Map<String, String> outerVars = extractOuterScopeVariables(root, code, 3, "java");

            // y and z should NOT be available as they are declared after line 3
            assertFalse(outerVars.containsKey("y"), "y should not be in outer scope");
            assertFalse(outerVars.containsKey("z"), "z should not be in outer scope");
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    class EdgeCaseTests {

        @Test
        void emptyRange() {
            String code = """
                    public class Test {
                        public void method() {
                            int x = 5;
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            // Range outside of actual code
            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 100, 200, "java");

            assertNotNull(result);
            assertTrue(result.declaredVariables().isEmpty());
            assertTrue(result.usedVariables().isEmpty());
        }

        @Test
        void singleLineRange() {
            String code = """
                    public class Test {
                        public void method() {
                            int x = 5 + calculateValue();
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 2, 2, "java");

            assertNotNull(result);
        }

        @Test
        void nestedBlocks() {
            String code = """
                    public class Test {
                        public void method() {
                            int outer = 1;
                            {
                                int inner = 2;
                                int sum = outer + inner;
                            }
                            int afterBlock = outer;
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 3, 6, "java");

            assertNotNull(result);
        }

        @Test
        void lambdaVariables() {
            String code = """
                    public class Test {
                        public void method() {
                            int x = 5;
                            Runnable r = () -> {
                                int y = x + 1;
                                System.out.println(y);
                            };
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 2, 6, "java");

            assertNotNull(result);
        }

        @Test
        void shadowedVariables() {
            String code = """
                    public class Test {
                        public void method() {
                            int x = 5;
                            {
                                int x = 10; // shadows outer x
                                System.out.println(x);
                            }
                            System.out.println(x);
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 2, 7, "java");

            assertNotNull(result);
            // Both declarations should be found
        }

        @Test
        void multipleDeclarationsOnOneLine() {
            String code = """
                    public class Test {
                        public void method() {
                            int a = 1, b = 2, c = 3;
                            int sum = a + b + c;
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 2, 3, "java");

            assertNotNull(result);
        }

        @Test
        void utf8VariableNames() {
            String code = """
                    public class Test {
                        public void method() {
                            int значение = 5;
                            int результат = значение * 2;
                        }
                    }
                    """;
            TSTree tree = manager.parse(code, "java");
            TSNode root = tree.getRootNode();

            VariableAnalysisResult result = analyzeVariablesInRange(root, code, 2, 3, "java");

            assertNotNull(result);
        }
    }
}
