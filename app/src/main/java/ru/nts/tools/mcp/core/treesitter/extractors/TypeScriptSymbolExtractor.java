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

public class TypeScriptSymbolExtractor implements LanguageSymbolExtractor {

    private final JavaScriptSymbolExtractor jsExtractor = new JavaScriptSymbolExtractor();

    @Override
    public Optional<SymbolInfo> extractSymbol(TSNode node, String nodeType, Path path, String content, String parentName) {
        Optional<SymbolInfo> jsSymbol = jsExtractor.extractSymbol(node, nodeType, path, content, parentName);
        if (jsSymbol.isPresent()) return jsSymbol;

        return switch (nodeType) {
            case "interface_declaration" -> extractInterfaceDeclaration(node, path, content, parentName);
            case "type_alias_declaration" -> extractTypeAlias(node, path, content, parentName);
            case "enum_declaration" -> extractEnumDeclaration(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractInterfaceDeclaration(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.INTERFACE, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractTypeAlias(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);

        return Optional.of(new SymbolInfo(name, SymbolKind.TYPE_PARAMETER, null, null, null, location, parentName));
    }

    private Optional<SymbolInfo> extractEnumDeclaration(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.ENUM, null, null, doc, location, parentName));
    }
}
