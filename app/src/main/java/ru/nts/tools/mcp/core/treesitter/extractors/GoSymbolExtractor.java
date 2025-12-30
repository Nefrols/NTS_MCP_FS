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

        // Извлекаем параметры и возвращаемый тип
        List<ParameterInfo> params = extractGoParameters(node, content, false);
        String returnType = extractGoReturnType(node, content);
        String signature = buildGoSignature(name, params, returnType);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, returnType, signature, params, doc, location, parentName));
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

        // Извлекаем параметры (пропускаем receiver)
        List<ParameterInfo> params = extractGoParameters(node, content, true);
        String returnType = extractGoReturnType(node, content);
        String signature = buildGoSignature(name, params, returnType);

        return Optional.of(new SymbolInfo(name, SymbolKind.METHOD, returnType, signature, params, doc, location,
                receiverName != null ? receiverName : parentName));
    }

    /**
     * Извлекает параметры Go функции/метода.
     * Формат: func(a, b int, c string) или func(a int, b int)
     */
    private List<ParameterInfo> extractGoParameters(TSNode node, String content, boolean skipReceiver) {
        List<ParameterInfo> params = new ArrayList<>();

        int paramListCount = 0;
        int childCount = node.getChildCount();

        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child == null || child.isNull()) continue;

            if (child.getType().equals("parameter_list")) {
                paramListCount++;
                // Для методов первый parameter_list это receiver, пропускаем
                if (skipReceiver && paramListCount == 1) continue;

                extractParamsFromList(child, content, params);
                break; // Берём только первый (или второй для методов) parameter_list
            }
        }

        return params;
    }

    private void extractParamsFromList(TSNode paramList, String content, List<ParameterInfo> params) {
        int childCount = paramList.getChildCount();

        for (int i = 0; i < childCount; i++) {
            TSNode child = paramList.getChild(i);
            if (child == null || child.isNull()) continue;

            if (child.getType().equals("parameter_declaration")) {
                extractGoParamDeclaration(child, content, params);
            }
        }
    }

    private void extractGoParamDeclaration(TSNode paramDecl, String content, List<ParameterInfo> params) {
        // В Go параметры могут быть: name Type, или a, b Type
        List<String> names = new ArrayList<>();
        String type = "interface{}";
        boolean isVariadic = false;

        int childCount = paramDecl.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramDecl.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            if (childType.equals("identifier")) {
                names.add(getNodeText(child, content));
            } else if (childType.equals("variadic_parameter_declaration")) {
                isVariadic = true;
                TSNode varId = findChildByType(child, "identifier");
                if (varId != null) {
                    names.add(getNodeText(varId, content));
                }
                // Тип вариадика - ищем тип в child
                TSNode varType = findTypeInNode(child);
                if (varType != null) {
                    type = "..." + getNodeText(varType, content);
                }
            } else if (isTypeNode(childType)) {
                type = getNodeText(child, content);
            }
        }

        // Создаём параметры для всех имён
        for (String name : names) {
            params.add(new ParameterInfo(name, type, isVariadic));
        }
    }

    private boolean isTypeNode(String nodeType) {
        return nodeType.equals("type_identifier") ||
               nodeType.equals("pointer_type") ||
               nodeType.equals("array_type") ||
               nodeType.equals("slice_type") ||
               nodeType.equals("map_type") ||
               nodeType.equals("channel_type") ||
               nodeType.equals("function_type") ||
               nodeType.equals("interface_type") ||
               nodeType.equals("struct_type") ||
               nodeType.equals("qualified_type");
    }

    /**
     * Находит первый узел типа в дочерних узлах.
     */
    private TSNode findTypeInNode(TSNode node) {
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull() && isTypeNode(child.getType())) {
                return child;
            }
        }
        return null;
    }

    /**
     * Извлекает тип возврата Go функции.
     */
    private String extractGoReturnType(TSNode node, String content) {
        // Ищем result после parameter_list
        int childCount = node.getChildCount();
        boolean afterParams = false;

        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            if (childType.equals("parameter_list")) {
                afterParams = true;
                continue;
            }

            if (afterParams && (childType.equals("parameter_list") || isTypeNode(childType))) {
                return getNodeText(child, content);
            }
        }

        return "";
    }

    /**
     * Строит сигнатуру функции Go.
     */
    private String buildGoSignature(String name, List<ParameterInfo> params, String returnType) {
        StringBuilder sb = new StringBuilder();
        sb.append("func ").append(name).append("(");

        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            ParameterInfo param = params.get(i);
            sb.append(param.name()).append(" ").append(param.type());
        }

        sb.append(")");
        if (!returnType.isEmpty()) {
            sb.append(" ").append(returnType);
        }
        return sb.toString();
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
