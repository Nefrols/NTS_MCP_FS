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

        // Извлекаем параметры функции из AST
        List<ParameterInfo> params = extractKotlinParameters(node, content);

        // Извлекаем тип возврата
        String returnType = extractKotlinReturnType(node, content);

        // Строим сигнатуру
        String signature = buildKotlinSignature(name, params, returnType);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, returnType, signature, params, doc, location, parentName));
    }

    /**
     * Извлекает параметры функции из AST узла Kotlin.
     * Формат параметров: name: Type, name2: Type2 = defaultValue, vararg name3: Type3
     */
    private List<ParameterInfo> extractKotlinParameters(TSNode node, String content) {
        List<ParameterInfo> params = new ArrayList<>();

        TSNode paramList = findChildByType(node, "function_value_parameters");
        if (paramList == null) return params;

        int childCount = paramList.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramList.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();
            if (childType.equals("parameter")) {
                ParameterInfo param = extractKotlinParameter(child, content);
                if (param != null) {
                    params.add(param);
                }
            }
        }

        return params;
    }

    /**
     * Извлекает информацию об одном параметре Kotlin.
     */
    private ParameterInfo extractKotlinParameter(TSNode paramNode, String content) {
        // Проверяем vararg
        boolean isVararg = false;
        int childCount = paramNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramNode.getChild(i);
            if (child != null && "vararg".equals(child.getType())) {
                isVararg = true;
                break;
            }
        }

        // Находим simple_identifier (имя параметра)
        TSNode nameNode = findChildByType(paramNode, "simple_identifier");
        if (nameNode == null) return null;

        String name = getNodeText(nameNode, content);

        // Находим тип (user_type или simple_type или nullable_type)
        String type = "Any";
        TSNode typeNode = findChildByType(paramNode, "user_type");
        if (typeNode == null) typeNode = findChildByType(paramNode, "simple_type");
        if (typeNode == null) typeNode = findChildByType(paramNode, "nullable_type");
        if (typeNode == null) typeNode = findChildByType(paramNode, "function_type");

        if (typeNode != null) {
            type = getNodeText(typeNode, content);
        }

        return new ParameterInfo(name, type, isVararg);
    }

    /**
     * Извлекает тип возврата функции Kotlin.
     */
    private String extractKotlinReturnType(TSNode node, String content) {
        // В Kotlin возвращаемый тип после : после параметров
        TSNode returnTypeNode = findChildByType(node, "user_type");
        if (returnTypeNode == null) returnTypeNode = findChildByType(node, "simple_type");
        if (returnTypeNode == null) returnTypeNode = findChildByType(node, "nullable_type");

        // Проверяем что это не тип параметра (должен быть после function_value_parameters)
        TSNode funcParams = findChildByType(node, "function_value_parameters");
        if (funcParams != null && returnTypeNode != null) {
            if (returnTypeNode.getStartByte() > funcParams.getEndByte()) {
                return getNodeText(returnTypeNode, content);
            }
        }

        return "Unit";
    }

    /**
     * Строит сигнатуру функции Kotlin.
     */
    private String buildKotlinSignature(String name, List<ParameterInfo> params, String returnType) {
        StringBuilder sb = new StringBuilder();
        sb.append("fun ").append(name).append("(");

        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            ParameterInfo param = params.get(i);
            if (param.isVarargs()) sb.append("vararg ");
            sb.append(param.name()).append(": ").append(param.type());
        }

        sb.append("): ").append(returnType);
        return sb.toString();
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
