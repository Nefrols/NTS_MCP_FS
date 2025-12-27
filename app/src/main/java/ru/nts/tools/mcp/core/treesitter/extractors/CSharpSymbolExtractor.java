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

public class CSharpSymbolExtractor implements LanguageSymbolExtractor {

    @Override
    public Optional<SymbolInfo> extractSymbol(TSNode node, String nodeType, Path path, String content, String parentName) {
        return switch (nodeType) {
            case "class_declaration" -> extractClass(node, path, content, parentName, SymbolKind.CLASS);
            case "interface_declaration" -> extractClass(node, path, content, parentName, SymbolKind.INTERFACE);
            case "struct_declaration" -> extractClass(node, path, content, parentName, SymbolKind.STRUCT);
            case "enum_declaration" -> extractEnum(node, path, content, parentName);
            case "record_declaration" -> extractClass(node, path, content, parentName, SymbolKind.CLASS);
            case "method_declaration" -> extractMethod(node, path, content, parentName);
            case "constructor_declaration" -> extractConstructor(node, path, content, parentName);
            case "property_declaration" -> extractProperty(node, path, content, parentName);
            case "field_declaration" -> extractField(node, path, content, parentName);
            case "namespace_declaration" -> extractNamespace(node, path, content, parentName);
            case "delegate_declaration" -> extractDelegate(node, path, content, parentName);
            case "event_declaration" -> extractEvent(node, path, content, parentName);
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

    private Optional<SymbolInfo> extractEnum(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.ENUM, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractMethod(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);
        String signature = extractMethodSignature(node, content);

        // Get return type
        TSNode returnType = findChildByType(node, "predefined_type");
        if (returnType == null) returnType = findChildByType(node, "identifier");
        if (returnType == null) returnType = findChildByType(node, "generic_name");
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

    private Optional<SymbolInfo> extractProperty(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        TSNode typeNode = findChildByType(node, "predefined_type");
        if (typeNode == null) typeNode = findChildByType(node, "identifier");
        if (typeNode == null) typeNode = findChildByType(node, "generic_name");
        String type = typeNode != null ? getNodeText(typeNode, content) : null;

        return Optional.of(new SymbolInfo(name, SymbolKind.PROPERTY, type, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractField(TSNode node, Path path, String content, String parentName) {
        TSNode declaration = findChildByType(node, "variable_declaration");
        if (declaration == null) return Optional.empty();

        TSNode declarator = findChildByType(declaration, "variable_declarator");
        if (declarator == null) return Optional.empty();

        TSNode nameNode = findChildByType(declarator, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(nameNode, path);

        TSNode typeNode = findChildByType(declaration, "predefined_type");
        if (typeNode == null) typeNode = findChildByType(declaration, "identifier");
        if (typeNode == null) typeNode = findChildByType(declaration, "generic_name");
        String type = typeNode != null ? getNodeText(typeNode, content) : null;

        return Optional.of(new SymbolInfo(name, SymbolKind.FIELD, type, null, null, location, parentName));
    }

    private Optional<SymbolInfo> extractNamespace(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) nameNode = findChildByType(node, "qualified_name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.NAMESPACE, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractDelegate(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractEvent(TSNode node, Path path, String content, String parentName) {
        TSNode declaration = findChildByType(node, "variable_declaration");
        if (declaration == null) return Optional.empty();

        TSNode declarator = findChildByType(declaration, "variable_declarator");
        if (declarator == null) return Optional.empty();

        TSNode nameNode = findChildByType(declarator, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(nameNode, path);

        return Optional.of(new SymbolInfo(name, SymbolKind.EVENT, null, null, null, location, parentName));
    }
}
