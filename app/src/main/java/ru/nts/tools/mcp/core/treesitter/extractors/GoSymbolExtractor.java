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

public class GoSymbolExtractor implements LanguageSymbolExtractor {

    @Override
    public Optional<SymbolInfo> extractSymbol(TSNode node, String nodeType, Path path, String content, String parentName) {
        return switch (nodeType) {
            case "function_declaration" -> extractFunction(node, path, content, parentName);
            case "method_declaration" -> extractMethod(node, path, content, parentName);
            case "type_declaration" -> extractTypeDeclaration(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractFunction(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractMethod(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "field_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        TSNode receiver = findChildByType(node, "parameter_list");
        String receiverName = null;
        if (receiver != null) {
            TSNode typeId = findChildByType(receiver, "type_identifier");
            if (typeId != null) receiverName = getNodeText(typeId, content);
        }

        return Optional.of(new SymbolInfo(name, SymbolKind.METHOD, null, null, doc, location,
                receiverName != null ? receiverName : parentName));
    }

    private Optional<SymbolInfo> extractTypeDeclaration(TSNode node, Path path, String content, String parentName) {
        TSNode spec = findChildByType(node, "type_spec");
        if (spec == null) return Optional.empty();

        TSNode nameNode = findChildByType(spec, "type_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        SymbolKind kind = SymbolKind.CLASS;
        if (spec.getChildCount() > 1) {
            TSNode typeNode = spec.getChild(1);
            if (typeNode != null) {
                String typeType = typeNode.getType();
                if (typeType.equals("struct_type")) kind = SymbolKind.STRUCT;
                else if (typeType.equals("interface_type")) kind = SymbolKind.INTERFACE;
            }
        }

        return Optional.of(new SymbolInfo(name, kind, null, null, doc, location, parentName));
    }
}
