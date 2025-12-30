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

public class PythonSymbolExtractor implements LanguageSymbolExtractor {

    @Override
    public Optional<SymbolInfo> extractSymbol(TSNode node, String nodeType, Path path, String content, String parentName) {
        return switch (nodeType) {
            case "function_definition" -> extractFunction(node, path, content, parentName);
            case "class_definition" -> extractClass(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractFunction(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPythonDocstring(node, content);

        SymbolKind kind = name.equals("__init__") ? SymbolKind.CONSTRUCTOR :
                (parentName != null ? SymbolKind.METHOD : SymbolKind.FUNCTION);

        // Извлекаем параметры из AST
        List<ParameterInfo> params = extractPythonParameters(node, content, parentName != null);

        // Извлекаем тип возврата
        String returnType = extractPythonReturnType(node, content);

        // Строим сигнатуру
        String signature = buildPythonSignature(name, params, returnType);

        return Optional.of(new SymbolInfo(name, kind, returnType, signature, params, doc, location, parentName));
    }

    /**
     * Извлекает параметры функции Python из AST.
     * Форматы: param, param: type, param=default, *args, **kwargs
     */
    private List<ParameterInfo> extractPythonParameters(TSNode node, String content, boolean isMethod) {
        List<ParameterInfo> params = new ArrayList<>();

        TSNode paramList = findChildByType(node, "parameters");
        if (paramList == null) return params;

        int childCount = paramList.getChildCount();
        boolean firstParam = true;

        for (int i = 0; i < childCount; i++) {
            TSNode child = paramList.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            // Пропускаем self/cls для методов (первый параметр)
            if (firstParam && isMethod) {
                if (childType.equals("identifier")) {
                    String paramName = getNodeText(child, content);
                    if (paramName.equals("self") || paramName.equals("cls")) {
                        firstParam = false;
                        continue;
                    }
                }
            }

            ParameterInfo param = null;

            switch (childType) {
                case "identifier" -> {
                    // Простой параметр без типа
                    param = new ParameterInfo(getNodeText(child, content), "Any", false);
                }
                case "typed_parameter" -> {
                    param = extractTypedParameter(child, content);
                }
                case "default_parameter" -> {
                    param = extractDefaultParameter(child, content);
                }
                case "typed_default_parameter" -> {
                    param = extractTypedDefaultParameter(child, content);
                }
                case "list_splat_pattern" -> {
                    // *args
                    TSNode argName = findChildByType(child, "identifier");
                    if (argName != null) {
                        param = new ParameterInfo(getNodeText(argName, content), "tuple", true);
                    }
                }
                case "dictionary_splat_pattern" -> {
                    // **kwargs
                    TSNode kwargName = findChildByType(child, "identifier");
                    if (kwargName != null) {
                        param = new ParameterInfo(getNodeText(kwargName, content), "dict", true);
                    }
                }
            }

            if (param != null) {
                params.add(param);
                firstParam = false;
            }
        }

        return params;
    }

    private ParameterInfo extractTypedParameter(TSNode node, String content) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return null;

        String name = getNodeText(nameNode, content);
        String type = "Any";

        TSNode typeNode = findChildByType(node, "type");
        if (typeNode != null) {
            type = getNodeText(typeNode, content);
        }

        return new ParameterInfo(name, type, false);
    }

    private ParameterInfo extractDefaultParameter(TSNode node, String content) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return null;

        String name = getNodeText(nameNode, content);
        return new ParameterInfo(name, "Any", false);
    }

    private ParameterInfo extractTypedDefaultParameter(TSNode node, String content) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return null;

        String name = getNodeText(nameNode, content);
        String type = "Any";

        TSNode typeNode = findChildByType(node, "type");
        if (typeNode != null) {
            type = getNodeText(typeNode, content);
        }

        return new ParameterInfo(name, type, false);
    }

    /**
     * Извлекает тип возврата функции Python.
     */
    private String extractPythonReturnType(TSNode node, String content) {
        TSNode returnType = findChildByType(node, "type");
        if (returnType != null) {
            return getNodeText(returnType, content);
        }
        return "None";
    }

    /**
     * Строит сигнатуру функции Python.
     */
    private String buildPythonSignature(String name, List<ParameterInfo> params, String returnType) {
        StringBuilder sb = new StringBuilder();
        sb.append("def ").append(name).append("(");

        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            ParameterInfo param = params.get(i);
            if (param.isVarargs()) {
                sb.append("*").append(param.name());
            } else {
                sb.append(param.name());
                if (!"Any".equals(param.type())) {
                    sb.append(": ").append(param.type());
                }
            }
        }

        sb.append(")");
        if (!"None".equals(returnType)) {
            sb.append(" -> ").append(returnType);
        }
        return sb.toString();
    }

    private Optional<SymbolInfo> extractClass(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPythonDocstring(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.CLASS, null, null, doc, location, parentName));
    }

    private String extractPythonDocstring(TSNode node, String content) {
        TSNode body = findChildByType(node, "block");
        if (body == null) return null;

        if (body.getChildCount() > 0) {
            TSNode firstStmt = body.getChild(0);
            if (firstStmt != null && firstStmt.getType().equals("expression_statement")) {
                if (firstStmt.getChildCount() > 0) {
                    TSNode expr = firstStmt.getChild(0);
                    if (expr != null && expr.getType().equals("string")) {
                        String docstring = getNodeText(expr, content);
                        if (docstring.startsWith("\"\"\"") || docstring.startsWith("'''")) {
                            return docstring.substring(3, docstring.length() - 3).trim();
                        }
                    }
                }
            }
        }
        return null;
    }
}
