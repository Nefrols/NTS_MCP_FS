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

public class CSymbolExtractor implements LanguageSymbolExtractor {

    @Override
    public Optional<SymbolInfo> extractSymbol(TSNode node, String nodeType, Path path, String content, String parentName) {
        return switch (nodeType) {
            case "function_definition" -> extractFunction(node, path, content, parentName);
            case "declaration" -> extractDeclaration(node, path, content, parentName);
            case "struct_specifier" -> extractStruct(node, path, content, parentName);
            case "enum_specifier" -> extractEnum(node, path, content, parentName);
            case "type_definition" -> extractTypedef(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractFunction(TSNode node, Path path, String content, String parentName) {
        TSNode declarator = findChildByType(node, "function_declarator");
        if (declarator == null) return Optional.empty();

        TSNode nameNode = findChildByType(declarator, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);
        String signature = extractMethodSignature(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, null, signature, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractDeclaration(TSNode node, Path path, String content, String parentName) {
        // Check if it's a function declaration (prototype)
        TSNode declarator = findChildByType(node, "function_declarator");
        if (declarator != null) {
            TSNode nameNode = findChildByType(declarator, "identifier");
            if (nameNode != null) {
                String name = getNodeText(nameNode, content);
                Location location = nodeToLocation(node, path);
                return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, null, null, null, location, parentName));
            }
        }

        // Check for variable declarations (global or struct members)
        TSNode initDeclarator = findChildByType(node, "init_declarator");
        if (initDeclarator != null) {
            TSNode nameNode = findChildByType(initDeclarator, "identifier");
            if (nameNode != null) {
                String name = getNodeText(nameNode, content);
                Location location = nodeToLocation(nameNode, path);
                return Optional.of(new SymbolInfo(name, SymbolKind.VARIABLE, null, null, null, location, parentName));
            }
        }

        return Optional.empty();
    }

    private Optional<SymbolInfo> extractStruct(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.STRUCT, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractEnum(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.ENUM, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractTypedef(TSNode node, Path path, String content, String parentName) {
        TSNode declarator = findChildByType(node, "type_declarator");
        if (declarator == null) return Optional.empty();

        TSNode nameNode = findChildByType(declarator, "type_identifier");
        if (nameNode == null) nameNode = findChildByType(declarator, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);

        return Optional.of(new SymbolInfo(name, SymbolKind.TYPE_PARAMETER, null, null, null, location, parentName));
    }
}
