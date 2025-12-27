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

import org.treesitter.TSNode;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.SymbolKind;

import java.nio.file.Path;
import java.util.Optional;

import static ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils.*;

public class JavaSymbolExtractor implements LanguageSymbolExtractor {

    @Override
    public Optional<SymbolInfo> extractSymbol(TSNode node, String nodeType, Path path, String content, String parentName) {
        return switch (nodeType) {
            case "class_declaration" -> extractClass(node, path, content, parentName, SymbolKind.CLASS);
            case "interface_declaration" -> extractClass(node, path, content, parentName, SymbolKind.INTERFACE);
            case "enum_declaration" -> extractClass(node, path, content, parentName, SymbolKind.ENUM);
            case "record_declaration" -> extractClass(node, path, content, parentName, SymbolKind.CLASS);
            case "method_declaration" -> extractMethod(node, path, content, parentName);
            case "constructor_declaration" -> extractConstructor(node, path, content, parentName);
            case "field_declaration" -> extractField(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractClass(TSNode node, Path path, String content,
                                                   String parentName, SymbolKind kind) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, kind, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractMethod(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);
        String signature = extractMethodSignature(node, content);

        TSNode returnType = findChildByType(node, "type_identifier");
        if (returnType == null) returnType = findChildByType(node, "void_type");
        if (returnType == null) returnType = findChildByType(node, "generic_type");
        String type = returnType != null ? getNodeText(returnType, content) : null;

        return Optional.of(new SymbolInfo(name, SymbolKind.METHOD, type, signature, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractConstructor(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);
        String signature = extractMethodSignature(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.CONSTRUCTOR, null, signature, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractField(TSNode node, Path path, String content, String parentName) {
        TSNode declarator = findChildByType(node, "variable_declarator");
        if (declarator == null) return Optional.empty();

        TSNode nameNode = findChildByType(declarator, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(nameNode, path);
        String type = extractJavaTypeFromFieldDeclaration(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.FIELD, type, null, null, location, parentName));
    }

    /**
     * Extracts the type from a Java field_declaration node.
     * Handles all Java type forms: primitives, classes, generics, arrays.
     */
    private String extractJavaTypeFromFieldDeclaration(TSNode node, String content) {
        // All possible type node types in tree-sitter-java
        String[] typeNodeTypes = {
            "type_identifier",       // String, MyClass
            "generic_type",          // List<String>, Map<K,V>
            "array_type",            // int[], String[][]
            "integral_type",         // int, long, short, byte, char
            "floating_point_type",   // float, double
            "boolean_type",          // boolean
            "scoped_type_identifier", // java.util.List
            "void_type"              // void (rare for fields but possible)
        };

        for (String typeName : typeNodeTypes) {
            TSNode typeNode = findChildByType(node, typeName);
            if (typeNode != null) {
                return getNodeText(typeNode, content);
            }
        }

        // Fallback: try to find any node before the variable_declarator that looks like a type
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                String childType = child.getType();
                // Skip modifiers, annotations, and the declarator itself
                if (!childType.equals("modifiers") &&
                    !childType.equals("variable_declarator") &&
                    !childType.equals(";") &&
                    !childType.startsWith("marker_annotation") &&
                    !childType.startsWith("annotation")) {
                    // This is likely the type node
                    return getNodeText(child, content);
                }
            }
        }

        return null;
    }
}
