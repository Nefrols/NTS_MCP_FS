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

public class PhpSymbolExtractor implements LanguageSymbolExtractor {

    @Override
    public Optional<SymbolInfo> extractSymbol(TSNode node, String nodeType, Path path, String content, String parentName) {
        return switch (nodeType) {
            case "class_declaration" -> extractClass(node, path, content, parentName);
            case "interface_declaration" -> extractInterface(node, path, content, parentName);
            case "trait_declaration" -> extractTrait(node, path, content, parentName);
            case "enum_declaration" -> extractEnum(node, path, content, parentName);
            case "function_definition" -> extractFunction(node, path, content, parentName);
            case "method_declaration" -> extractMethod(node, path, content, parentName);
            case "property_declaration" -> extractProperty(node, path, content, parentName);
            case "const_declaration" -> extractConst(node, path, content, parentName);
            case "namespace_definition" -> extractNamespace(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractClass(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.CLASS, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractInterface(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.INTERFACE, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractTrait(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.TRAIT, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractEnum(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.ENUM, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractFunction(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        // Извлекаем параметры и возвращаемый тип
        List<ParameterInfo> params = extractPhpParameters(node, content);
        String returnType = extractPhpReturnType(node, content);
        String signature = buildPhpSignature(name, params, returnType);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, returnType, signature, params, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractMethod(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        SymbolKind kind = name.equals("__construct") ? SymbolKind.CONSTRUCTOR : SymbolKind.METHOD;

        // Извлекаем параметры и возвращаемый тип
        List<ParameterInfo> params = extractPhpParameters(node, content);
        String returnType = extractPhpReturnType(node, content);
        String signature = buildPhpSignature(name, params, returnType);

        return Optional.of(new SymbolInfo(name, kind, returnType, signature, params, doc, location, parentName));
    }

    /**
     * Извлекает параметры PHP функции/метода.
     * Формат: Type $name, ?Type $name = default, ...$name
     */
    private List<ParameterInfo> extractPhpParameters(TSNode node, String content) {
        List<ParameterInfo> params = new ArrayList<>();

        TSNode paramList = findChildByType(node, "formal_parameters");
        if (paramList == null) return params;

        int childCount = paramList.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramList.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();
            if (childType.equals("simple_parameter") || childType.equals("variadic_parameter") ||
                childType.equals("property_promotion_parameter")) {
                extractPhpParameter(child, content, params, childType.equals("variadic_parameter"));
            }
        }

        return params;
    }

    private void extractPhpParameter(TSNode paramNode, String content, List<ParameterInfo> params, boolean isVariadic) {
        String type = "mixed";
        String name = null;

        int childCount = paramNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            // Типы
            if (childType.equals("named_type") || childType.equals("primitive_type") ||
                childType.equals("optional_type") || childType.equals("union_type") ||
                childType.equals("intersection_type")) {
                type = getNodeText(child, content);
            }
            // Имя переменной
            else if (childType.equals("variable_name")) {
                name = getNodeText(child, content);
                // Remove $ prefix
                if (name.startsWith("$")) {
                    name = name.substring(1);
                }
            }
        }

        if (name != null) {
            params.add(new ParameterInfo(name, type, isVariadic));
        }
    }

    /**
     * Извлекает возвращаемый тип PHP функции.
     */
    private String extractPhpReturnType(TSNode node, String content) {
        int childCount = node.getChildCount();
        boolean afterColon = false;

        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();
            String text = getNodeText(child, content);

            // Ищем : и тип после него
            if (text.equals(":")) {
                afterColon = true;
                continue;
            }

            if (afterColon && (childType.equals("named_type") || childType.equals("primitive_type") ||
                               childType.equals("optional_type") || childType.equals("union_type"))) {
                return text;
            }
        }

        return "";
    }

    /**
     * Строит сигнатуру PHP функции.
     */
    private String buildPhpSignature(String name, List<ParameterInfo> params, String returnType) {
        StringBuilder sb = new StringBuilder();
        sb.append("function ").append(name).append("(");

        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            ParameterInfo param = params.get(i);
            if (!param.type().equals("mixed")) {
                sb.append(param.type()).append(" ");
            }
            if (param.isVarargs()) {
                sb.append("...");
            }
            sb.append("$").append(param.name());
        }

        sb.append(")");
        if (!returnType.isEmpty()) {
            sb.append(": ").append(returnType);
        }
        return sb.toString();
    }

    private Optional<SymbolInfo> extractProperty(TSNode node, Path path, String content, String parentName) {
        TSNode propElement = findChildByType(node, "property_element");
        if (propElement == null) return Optional.empty();

        TSNode varNode = findChildByType(propElement, "variable_name");
        if (varNode == null) return Optional.empty();

        String name = getNodeText(varNode, content);
        // Remove $ prefix
        if (name.startsWith("$")) {
            name = name.substring(1);
        }
        Location location = nodeToLocation(varNode, path);

        return Optional.of(new SymbolInfo(name, SymbolKind.PROPERTY, null, null, null, location, parentName));
    }

    private Optional<SymbolInfo> extractConst(TSNode node, Path path, String content, String parentName) {
        TSNode constElement = findChildByType(node, "const_element");
        if (constElement == null) return Optional.empty();

        TSNode nameNode = findChildByType(constElement, "name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(nameNode, path);

        return Optional.of(new SymbolInfo(name, SymbolKind.CONSTANT, null, null, null, location, parentName));
    }

    private Optional<SymbolInfo> extractNamespace(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "namespace_name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);

        return Optional.of(new SymbolInfo(name, SymbolKind.NAMESPACE, null, null, null, location, parentName));
    }
}
