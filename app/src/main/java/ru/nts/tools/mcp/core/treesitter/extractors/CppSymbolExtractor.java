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
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.ParameterInfo;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.SymbolKind;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils.*;

public class CppSymbolExtractor implements LanguageSymbolExtractor {

    private final CSymbolExtractor cExtractor = new CSymbolExtractor();

    @Override
    public Optional<SymbolInfo> extractSymbol(TSNode node, String nodeType, Path path, String content, String parentName) {
        // C++ extends C, so check C symbols first
        Optional<SymbolInfo> cSymbol = cExtractor.extractSymbol(node, nodeType, path, content, parentName);
        if (cSymbol.isPresent()) return cSymbol;

        return switch (nodeType) {
            case "class_specifier" -> extractClass(node, path, content, parentName);
            case "function_definition" -> extractFunction(node, path, content, parentName);
            case "field_declaration" -> extractField(node, path, content, parentName);
            case "namespace_definition" -> extractNamespace(node, path, content, parentName);
            case "template_declaration" -> extractTemplate(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractClass(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) nameNode = findChildByType(node, "name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        // Determine if it's a struct or class
        String fullText = getNodeText(node, content);
        SymbolKind kind = fullText.trim().startsWith("struct") ? SymbolKind.STRUCT : SymbolKind.CLASS;

        return Optional.of(new SymbolInfo(name, kind, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractFunction(TSNode node, Path path, String content, String parentName) {
        TSNode declarator = findChildByType(node, "function_declarator");
        if (declarator == null) return Optional.empty();

        // Check for qualified identifier (method with class prefix)
        TSNode qualId = findChildByType(declarator, "qualified_identifier");
        TSNode nameNode;
        if (qualId != null) {
            nameNode = findChildByType(qualId, "identifier");
            if (nameNode == null) nameNode = findChildByType(qualId, "destructor_name");
        } else {
            nameNode = findChildByType(declarator, "identifier");
            if (nameNode == null) nameNode = findChildByType(declarator, "destructor_name");
            if (nameNode == null) nameNode = findChildByType(declarator, "operator_name");
        }

        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        // Извлекаем параметры и возвращаемый тип используя методы из CSymbolExtractor
        List<ParameterInfo> params = cExtractor.extractCParameters(declarator, content);
        String returnType = cExtractor.extractCReturnType(node, content);
        String signature = cExtractor.buildCSignature(name, params, returnType);

        // Detect constructor/destructor
        SymbolKind kind = SymbolKind.FUNCTION;
        if (name.startsWith("~")) {
            kind = SymbolKind.METHOD; // Destructor
        } else if (parentName != null && name.equals(parentName)) {
            kind = SymbolKind.CONSTRUCTOR;
        } else if (parentName != null) {
            kind = SymbolKind.METHOD;
        }

        return Optional.of(new SymbolInfo(name, kind, returnType, signature, params, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractField(TSNode node, Path path, String content, String parentName) {
        if (parentName == null) return Optional.empty(); // Only extract fields inside classes

        TSNode declarator = findChildByType(node, "field_declarator");
        if (declarator == null) declarator = findChildByType(node, "declarator");
        if (declarator == null) return Optional.empty();

        TSNode nameNode = findChildByType(declarator, "field_identifier");
        if (nameNode == null) nameNode = findChildByType(declarator, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(nameNode, path);

        return Optional.of(new SymbolInfo(name, SymbolKind.FIELD, null, null, null, location, parentName));
    }

    private Optional<SymbolInfo> extractNamespace(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) nameNode = findChildByType(node, "namespace_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.NAMESPACE, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractTemplate(TSNode node, Path path, String content, String parentName) {
        // Extract the templated declaration
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (child != null) {
                String childType = child.getType();
                if (childType.equals("class_specifier") || childType.equals("function_definition")) {
                    return extractSymbol(child, childType, path, content, parentName);
                }
            }
        }
        return Optional.empty();
    }
}
