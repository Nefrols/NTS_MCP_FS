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

public class JavaScriptSymbolExtractor implements LanguageSymbolExtractor {

    @Override
    public Optional<SymbolInfo> extractSymbol(TSNode node, String nodeType, Path path, String content, String parentName) {
        return switch (nodeType) {
            case "function_declaration" -> extractFunctionDeclaration(node, path, content, parentName);
            case "class_declaration" -> extractClassDeclaration(node, path, content, parentName);
            case "method_definition" -> extractMethodDefinition(node, path, content, parentName);
            case "lexical_declaration", "variable_declaration" ->
                    extractVariableDeclaration(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractFunctionDeclaration(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractClassDeclaration(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.CLASS, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractMethodDefinition(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "property_identifier");
        if (nameNode == null) nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        SymbolKind kind = name.equals("constructor") ? SymbolKind.CONSTRUCTOR : SymbolKind.METHOD;

        return Optional.of(new SymbolInfo(name, kind, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractVariableDeclaration(TSNode node, Path path, String content, String parentName) {
        TSNode declarator = findChildByType(node, "variable_declarator");
        if (declarator == null) return Optional.empty();

        TSNode nameNode = findChildByType(declarator, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(nameNode, path);

        TSNode value = findChildByType(declarator, "arrow_function");
        if (value == null) value = findChildByType(declarator, "function_expression");

        SymbolKind kind = value != null ? SymbolKind.FUNCTION : SymbolKind.VARIABLE;

        return Optional.of(new SymbolInfo(name, kind, null, null, null, location, parentName));
    }
}
