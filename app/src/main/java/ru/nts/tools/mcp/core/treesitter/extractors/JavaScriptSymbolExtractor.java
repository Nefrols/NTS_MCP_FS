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

public class JavaScriptSymbolExtractor implements LanguageSymbolExtractor {

    @Override
    public Optional<SymbolInfo> extractSymbol(TSNode node, String nodeType, Path path, String content, String parentName) {
        return switch (nodeType) {
            case "function_declaration" -> extractFunctionDeclaration(node, path, content, parentName);
            case "class_declaration" -> extractClassDeclaration(node, path, content, parentName);
            case "method_definition" -> extractMethodDefinition(node, path, content, parentName);
            case "lexical_declaration", "variable_declaration" ->
                    extractVariableDeclaration(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractFunctionDeclaration(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        // Извлекаем параметры из AST
        List<ParameterInfo> params = extractJsParameters(node, content);
        String signature = buildJsSignature(name, params);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, null, signature, params, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractClassDeclaration(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.CLASS, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractMethodDefinition(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "property_identifier");
        if (nameNode == null) nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        SymbolKind kind = name.equals("constructor") ? SymbolKind.CONSTRUCTOR : SymbolKind.METHOD;

        // Извлекаем параметры из AST
        List<ParameterInfo> params = extractJsParameters(node, content);
        String signature = buildJsSignature(name, params);

        return Optional.of(new SymbolInfo(name, kind, null, signature, params, doc, location, parentName));
    }

    /**
     * Извлекает параметры JavaScript функции/метода.
     * Форматы: param, param = default, ...rest
     */
    private List<ParameterInfo> extractJsParameters(TSNode node, String content) {
        List<ParameterInfo> params = new ArrayList<>();

        TSNode paramList = findChildByType(node, "formal_parameters");
        if (paramList == null) return params;

        int childCount = paramList.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramList.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();
            ParameterInfo param = null;

            switch (childType) {
                case "identifier" -> {
                    param = new ParameterInfo(getNodeText(child, content), "any", false);
                }
                case "assignment_pattern" -> {
                    // param = default
                    TSNode leftNode = findChildByType(child, "identifier");
                    if (leftNode != null) {
                        param = new ParameterInfo(getNodeText(leftNode, content), "any", false);
                    }
                }
                case "rest_pattern" -> {
                    // ...rest
                    TSNode restId = findChildByType(child, "identifier");
                    if (restId != null) {
                        param = new ParameterInfo(getNodeText(restId, content), "any[]", true);
                    }
                }
                case "object_pattern", "array_pattern" -> {
                    // Destructuring - получаем весь паттерн как имя
                    String patternText = getNodeText(child, content);
                    if (patternText.length() > 20) {
                        patternText = patternText.substring(0, 17) + "...";
                    }
                    param = new ParameterInfo(patternText, "object", false);
                }
            }

            if (param != null) {
                params.add(param);
            }
        }

        return params;
    }

    /**
     * Строит сигнатуру функции JavaScript.
     */
    private String buildJsSignature(String name, List<ParameterInfo> params) {
        StringBuilder sb = new StringBuilder();
        sb.append("function ").append(name).append("(");

        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            ParameterInfo param = params.get(i);
            if (param.isVarargs()) {
                sb.append("...").append(param.name());
            } else {
                sb.append(param.name());
            }
        }

        sb.append(")");
        return sb.toString();
    }

    private Optional<SymbolInfo> extractVariableDeclaration(TSNode node, Path path, String content, String parentName) {
        TSNode declarator = findChildByType(node, "variable_declarator");
        if (declarator == null) return Optional.empty();

        TSNode nameNode = findChildByType(declarator, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(nameNode, path);

        TSNode value = findChildByType(declarator, "arrow_function");
        if (value == null) value = findChildByType(declarator, "function_expression");

        SymbolKind kind = value != null ? SymbolKind.FUNCTION : SymbolKind.VARIABLE;

        return Optional.of(new SymbolInfo(name, kind, null, null, null, location, parentName));
    }
}
