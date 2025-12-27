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

public class KotlinSymbolExtractor implements LanguageSymbolExtractor {

    @Override
    public Optional<SymbolInfo> extractSymbol(TSNode node, String nodeType, Path path, String content, String parentName) {
        return switch (nodeType) {
            case "class_declaration" -> extractClass(node, path, content, parentName);
            case "object_declaration" -> extractObject(node, path, content, parentName);
            case "function_declaration" -> extractFunction(node, path, content, parentName);
            case "property_declaration" -> extractProperty(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractClass(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "simple_identifier");
        if (nameNode == null) nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        SymbolKind kind = SymbolKind.CLASS;
        String nodeText = getNodeText(node, content);
        if (nodeText.contains("interface ")) kind = SymbolKind.INTERFACE;
        else if (nodeText.contains("enum class")) kind = SymbolKind.ENUM;

        return Optional.of(new SymbolInfo(name, kind, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractObject(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) nameNode = findChildByType(node, "simple_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.OBJECT, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractFunction(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "simple_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractProperty(TSNode node, Path path, String content, String parentName) {
        TSNode declarator = findChildByType(node, "variable_declaration");
        if (declarator == null) return Optional.empty();

        TSNode nameNode = findChildByType(declarator, "simple_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(nameNode, path);

        return Optional.of(new SymbolInfo(name, SymbolKind.PROPERTY, null, null, null, location, parentName));
    }
}
