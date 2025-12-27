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
}
