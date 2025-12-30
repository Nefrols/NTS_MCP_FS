/*
 * Copyright 2025 Aristo
 * Test file for AST-based parameter extraction
 */
package ru.nts.tools.mcp.core.treesitter;

import org.treesitter.TSNode;
import org.treesitter.TSTree;
import org.junit.jupiter.api.Test;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.ParameterInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils.*;

/**
 * Тесты для извлечения параметров методов из AST.
 */
class ParameterExtractionTest {

    @Test
    void extractSimpleParameter() {
        TreeSitterManager manager = TreeSitterManager.getInstance();
        String code = "public class Test { public void simple(String name) {} }";
        TSTree tree = manager.parse(code, "java");

        TSNode methodNode = findMethodDeclaration(tree.getRootNode());
        assertNotNull(methodNode, "Should find method_declaration");

        List<ParameterInfo> params = extractParameters(methodNode, code);
        assertEquals(1, params.size(), "Should have 1 parameter");
        assertEquals("name", params.get(0).name());
        assertEquals("String", params.get(0).type());
        assertFalse(params.get(0).isVarargs());
    }

    @Test
    void extractMultipleParameters() {
        TreeSitterManager manager = TreeSitterManager.getInstance();
        String code = "public class Test { public void multi(String name, int count) {} }";
        TSTree tree = manager.parse(code, "java");

        TSNode methodNode = findMethodDeclaration(tree.getRootNode());
        List<ParameterInfo> params = extractParameters(methodNode, code);

        assertEquals(2, params.size(), "Should have 2 parameters");
        assertEquals("name", params.get(0).name());
        assertEquals("String", params.get(0).type());
        assertEquals("count", params.get(1).name());
        assertEquals("int", params.get(1).type());
    }

    @Test
    void extractGenericParameter() {
        TreeSitterManager manager = TreeSitterManager.getInstance();
        String code = "public class Test { public void generic(Map<String, List<Integer>> data) {} }";
        TSTree tree = manager.parse(code, "java");

        TSNode methodNode = findMethodDeclaration(tree.getRootNode());
        List<ParameterInfo> params = extractParameters(methodNode, code);

        assertEquals(1, params.size(), "Should have 1 parameter");
        assertEquals("data", params.get(0).name());
        assertEquals("Map<String, List<Integer>>", params.get(0).type());
    }

    @Test
    void extractVarargsParameter() {
        TreeSitterManager manager = TreeSitterManager.getInstance();
        String code = "public class Test { public void varargs(String... args) {} }";
        TSTree tree = manager.parse(code, "java");

        TSNode methodNode = findMethodDeclaration(tree.getRootNode());
        List<ParameterInfo> params = extractParameters(methodNode, code);

        assertEquals(1, params.size(), "Should have 1 parameter");
        assertEquals("args", params.get(0).name());
        assertEquals("String[]", params.get(0).type());
        assertTrue(params.get(0).isVarargs());
    }

    @Test
    void symbolInfoMatchesSignature() {
        TreeSitterManager manager = TreeSitterManager.getInstance();
        String code = "public class Test { public void process(String name, int count) {} }";
        TSTree tree = manager.parse(code, "java");

        TSNode methodNode = findMethodDeclaration(tree.getRootNode());
        List<ParameterInfo> params = extractParameters(methodNode, code);

        // Create SymbolInfo with parameters
        SymbolInfo symbol = new SymbolInfo("process", SymbolInfo.SymbolKind.METHOD,
                SymbolInfo.Location.point(Path.of("Test.java"), 1, 1))
                .withParameters(params);

        // Test signature matching
        assertTrue(symbol.matchesParameterSignature("(String, int)"));
        assertFalse(symbol.matchesParameterSignature("(String)"));
        assertFalse(symbol.matchesParameterSignature("(int, String)"));
        assertTrue(symbol.matchesParameterSignature("")); // Empty = no filter
    }

    @Test
    void normalizedParameterSignature() {
        List<ParameterInfo> params = List.of(
            new ParameterInfo("data", "Map<String, List<Integer>>"),
            new ParameterInfo("count", "int")
        );

        SymbolInfo symbol = new SymbolInfo("process", SymbolInfo.SymbolKind.METHOD,
                SymbolInfo.Location.point(Path.of("Test.java"), 1, 1))
                .withParameters(params);

        // Generic types should be normalized (generics removed)
        assertEquals("(Map, int)", symbol.normalizedParameterSignature());
    }

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
}
