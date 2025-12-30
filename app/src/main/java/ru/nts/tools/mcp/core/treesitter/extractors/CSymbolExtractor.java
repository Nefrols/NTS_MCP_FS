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

        // Извлекаем параметры и возвращаемый тип
        List<ParameterInfo> params = extractCParameters(declarator, content);
        String returnType = extractCReturnType(node, content);
        String signature = buildCSignature(name, params, returnType);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, returnType, signature, params, doc, location, parentName));
    }

    /**
     * Извлекает параметры C функции.
     * Формат: type name, type *name, type name[]
     */
    protected List<ParameterInfo> extractCParameters(TSNode declarator, String content) {
        List<ParameterInfo> params = new ArrayList<>();

        TSNode paramList = findChildByType(declarator, "parameter_list");
        if (paramList == null) return params;

        int childCount = paramList.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramList.getChild(i);
            if (child == null || child.isNull()) continue;

            if (child.getType().equals("parameter_declaration")) {
                extractCParameter(child, content, params);
            } else if (child.getType().equals("variadic_parameter")) {
                params.add(new ParameterInfo("...", "...", true));
            }
        }

        return params;
    }

    private void extractCParameter(TSNode paramNode, String content, List<ParameterInfo> params) {
        String type = "int";
        String name = null;

        int childCount = paramNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            // Типы
            if (childType.equals("primitive_type") || childType.equals("type_identifier") ||
                childType.equals("struct_specifier") || childType.equals("enum_specifier") ||
                childType.equals("sized_type_specifier")) {
                type = getNodeText(child, content);
            }
            // Декларатор с именем
            else if (childType.equals("identifier")) {
                name = getNodeText(child, content);
            } else if (childType.equals("pointer_declarator")) {
                TSNode id = findChildByType(child, "identifier");
                if (id != null) {
                    name = getNodeText(id, content);
                    type = type + "*";
                }
            } else if (childType.equals("array_declarator")) {
                TSNode id = findChildByType(child, "identifier");
                if (id != null) {
                    name = getNodeText(id, content);
                    type = type + "[]";
                }
            }
        }

        if (name != null) {
            params.add(new ParameterInfo(name, type, false));
        }
    }

    /**
     * Извлекает возвращаемый тип C функции.
     */
    protected String extractCReturnType(TSNode node, String content) {
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();
            if (childType.equals("primitive_type") || childType.equals("type_identifier") ||
                childType.equals("struct_specifier") || childType.equals("enum_specifier") ||
                childType.equals("sized_type_specifier")) {
                return getNodeText(child, content);
            }
        }
        return "void";
    }

    /**
     * Строит сигнатуру C функции.
     */
    protected String buildCSignature(String name, List<ParameterInfo> params, String returnType) {
        StringBuilder sb = new StringBuilder();
        sb.append(returnType).append(" ").append(name).append("(");

        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            ParameterInfo param = params.get(i);
            if (param.isVarargs()) {
                sb.append("...");
            } else {
                sb.append(param.type()).append(" ").append(param.name());
            }
        }

        sb.append(")");
        return sb.toString();
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
