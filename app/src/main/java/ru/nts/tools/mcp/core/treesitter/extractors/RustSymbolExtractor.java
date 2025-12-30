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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils.*;

public class RustSymbolExtractor implements LanguageSymbolExtractor {

    @Override
    public Optional<SymbolInfo> extractSymbol(TSNode node, String nodeType, Path path, String content, String parentName) {
        return switch (nodeType) {
            case "function_item" -> extractFunction(node, path, content, parentName);
            case "struct_item" -> extractStruct(node, path, content, parentName);
            case "enum_item" -> extractEnum(node, path, content, parentName);
            case "trait_item" -> extractTrait(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractFunction(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        // Извлекаем параметры и возвращаемый тип
        List<ParameterInfo> params = extractRustParameters(node, content);
        String returnType = extractRustReturnType(node, content);
        String signature = buildRustSignature(name, params, returnType);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, returnType, signature, params, doc, location, parentName));
    }

    /**
     * Извлекает параметры Rust функции.
     * Формат: name: Type, name: &Type, name: &mut Type
     */
    private List<ParameterInfo> extractRustParameters(TSNode node, String content) {
        List<ParameterInfo> params = new ArrayList<>();

        TSNode paramList = findChildByType(node, "parameters");
        if (paramList == null) return params;

        int childCount = paramList.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramList.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();
            if (childType.equals("parameter")) {
                extractRustParameter(child, content, params);
            } else if (childType.equals("self_parameter")) {
                // self, &self, &mut self
                String selfText = getNodeText(child, content);
                params.add(new ParameterInfo("self", selfText, false));
            }
        }

        return params;
    }

    private void extractRustParameter(TSNode paramNode, String content, List<ParameterInfo> params) {
        TSNode nameNode = findChildByType(paramNode, "identifier");
        if (nameNode == null) return;

        String name = getNodeText(nameNode, content);
        String type = "unknown";

        // Ищем тип параметра
        int childCount = paramNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();
            if (isRustTypeNode(childType)) {
                type = getNodeText(child, content);
                break;
            }
        }

        params.add(new ParameterInfo(name, type, false));
    }

    private boolean isRustTypeNode(String nodeType) {
        return nodeType.equals("type_identifier") ||
               nodeType.equals("reference_type") ||
               nodeType.equals("generic_type") ||
               nodeType.equals("scoped_type_identifier") ||
               nodeType.equals("primitive_type") ||
               nodeType.equals("array_type") ||
               nodeType.equals("tuple_type") ||
               nodeType.equals("function_type") ||
               nodeType.equals("pointer_type") ||
               nodeType.equals("unit_type");
    }

    /**
     * Извлекает возвращаемый тип Rust функции.
     */
    private String extractRustReturnType(TSNode node, String content) {
        int childCount = node.getChildCount();
        boolean afterArrow = false;

        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            // Ищем -> и тип после него
            if (getNodeText(child, content).equals("->")) {
                afterArrow = true;
                continue;
            }

            if (afterArrow && isRustTypeNode(childType)) {
                return getNodeText(child, content);
            }
        }

        return "";
    }

    /**
     * Строит сигнатуру Rust функции.
     */
    private String buildRustSignature(String name, List<ParameterInfo> params, String returnType) {
        StringBuilder sb = new StringBuilder();
        sb.append("fn ").append(name).append("(");

        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            ParameterInfo param = params.get(i);
            if (param.name().equals("self")) {
                sb.append(param.type());
            } else {
                sb.append(param.name()).append(": ").append(param.type());
            }
        }

        sb.append(")");
        if (!returnType.isEmpty()) {
            sb.append(" -> ").append(returnType);
        }
        return sb.toString();
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

    private Optional<SymbolInfo> extractTrait(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.TRAIT, null, null, doc, location, parentName));
    }
}
