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
package ru.nts.tools.mcp.core.treesitter;

import org.treesitter.TSNode;
import org.treesitter.TSPoint;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.ParameterInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;



/**
 * Утилиты для извлечения информации из AST дерева tree-sitter.
 */
public final class SymbolExtractorUtils {

    private SymbolExtractorUtils() {}

    /**
     * Находит дочерний узел указанного типа.
     */
    public static TSNode findChildByType(TSNode parent, String type) {
        int childCount = parent.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = parent.getChild(i);
            if (child != null && !child.isNull() && child.getType().equals(type)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Извлекает текст узла из содержимого файла (используя байтовые смещения).
     * КРИТИЧНО: tree-sitter возвращает байтовые смещения, а не символьные!
     */
    public static String getNodeText(TSNode node, String content) {
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        return getNodeTextFromBytes(node, contentBytes);
    }

    /**
     * Извлекает текст узла из байтового массива (корректно для UTF-8).
     */
    public static String getNodeTextFromBytes(TSNode node, byte[] contentBytes) {
        int start = node.getStartByte();
        int end = node.getEndByte();
        if (start >= 0 && end <= contentBytes.length && start < end) {
            return new String(contentBytes, start, end - start, StandardCharsets.UTF_8);
        }
        return "";
    }

    /**
     * Преобразует узел в Location (1-based строки).
     */
    public static Location nodeToLocation(TSNode node, Path path) {
        TSPoint startPoint = node.getStartPoint();
        TSPoint endPoint = node.getEndPoint();
        return new Location(
                path,
                startPoint.getRow() + 1,
                startPoint.getColumn() + 1,
                endPoint.getRow() + 1,
                endPoint.getColumn() + 1
        );
    }
    
    /**
     * Преобразует узел в Location (1-based строки).
     * Перегрузка для совместимости, если где-то передается node, который нужно считать источником локации.
     */
    public static Location nodeToLocation(TSNode node, Path path, TSNode actualLocationNode) {
        // Логика та же, просто используем переданный узел
         return nodeToLocation(actualLocationNode != null ? actualLocationNode : node, path);
    }

    /**
     * Извлекает комментарий, предшествующий узлу.
     */
    public static String extractPrecedingComment(TSNode node, String content) {
        TSNode prev = node.getPrevSibling();

        if (prev != null && !prev.isNull()) {
            String prevType = prev.getType();
            if (prevType.equals("comment") ||
                    prevType.equals("line_comment") ||
                    prevType.equals("block_comment") ||
                    prevType.equals("documentation_comment")) {
                String comment = getNodeText(prev, content);
                return cleanupComment(comment);
            }
        }
        return null;
    }

    /**
     * Очищает комментарий от маркеров.
     */
    public static String cleanupComment(String comment) {
        if (comment == null) return null;

        if (comment.startsWith("/**")) comment = comment.substring(3);
        else if (comment.startsWith("/*")) comment = comment.substring(2);
        if (comment.endsWith("*/")) comment = comment.substring(0, comment.length() - 2);

        if (comment.startsWith("//")) comment = comment.substring(2);
        else if (comment.startsWith("#")) comment = comment.substring(1);

        comment = comment.lines()
                .map(line -> line.replaceFirst("^\\s*\\*\\s?", ""))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        return comment.trim();
    }

    /**
     * Извлекает сигнатуру метода.
     */
    public static String extractMethodSignature(TSNode node, String content) {
        String fullText = getNodeText(node, content);
        int braceIdx = fullText.indexOf('{');
        int semiIdx = fullText.indexOf(';');

        int endIdx = fullText.length();
        if (braceIdx > 0) endIdx = braceIdx;
        if (semiIdx > 0 && semiIdx < endIdx) endIdx = semiIdx;

        String signature = fullText.substring(0, endIdx).trim();
        return signature.replaceAll("\\s+", " ");
    }

    /**
     * Извлекает структурированные параметры метода из AST.
     * Работает с узлами method_declaration, function_definition и подобными.
     *
     * @param methodNode узел метода/функции
     * @param content содержимое файла
     * @return список параметров или пустой список
     */
    public static List<ParameterInfo> extractParameters(TSNode methodNode, String content) {
        List<ParameterInfo> params = new ArrayList<>();

        // Ищем узел formal_parameters (Java, C, C++, JavaScript)
        // или parameters (Python, Go)
        TSNode paramsNode = findChildByType(methodNode, "formal_parameters");
        if (paramsNode == null) {
            paramsNode = findChildByType(methodNode, "parameters");
        }
        if (paramsNode == null) {
            paramsNode = findChildByType(methodNode, "parameter_list");
        }
        if (paramsNode == null) {
            return params;
        }

        // Обходим дочерние узлы
        int childCount = paramsNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramsNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            // Java/C/C++/JavaScript: formal_parameter
            if (childType.equals("formal_parameter")) {
                ParameterInfo param = extractFormalParameter(child, content);
                if (param != null) {
                    params.add(param);
                }
            }
            // Java: spread_parameter (varargs)
            else if (childType.equals("spread_parameter")) {
                ParameterInfo param = extractSpreadParameter(child, content);
                if (param != null) {
                    params.add(param);
                }
            }
            // Python: default_parameter, typed_parameter, identifier
            else if (childType.equals("default_parameter") ||
                     childType.equals("typed_parameter") ||
                     childType.equals("typed_default_parameter")) {
                ParameterInfo param = extractPythonParameter(child, content);
                if (param != null) {
                    params.add(param);
                }
            }
            else if (childType.equals("identifier") && isInsidePythonParams(paramsNode)) {
                // Python: простой параметр без типа
                String name = getNodeText(child, content);
                params.add(new ParameterInfo(name, "Any", false));
            }
            // Go: parameter_declaration
            else if (childType.equals("parameter_declaration")) {
                List<ParameterInfo> goParams = extractGoParameters(child, content);
                params.addAll(goParams);
            }
        }

        return params;
    }

    /**
     * Извлекает обычный параметр (Java/C/C++/JavaScript).
     * Структура: [modifiers?] [type] [identifier]
     */
    private static ParameterInfo extractFormalParameter(TSNode paramNode, String content) {
        String type = null;
        String name = null;

        int childCount = paramNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            // Пропускаем модификаторы и аннотации
            if (childType.equals("modifiers") || childType.startsWith("annotation") ||
                childType.startsWith("marker_annotation")) {
                continue;
            }

            // Ищем тип
            if (type == null && isTypeNode(childType)) {
                type = getNodeText(child, content);
            }
            // Ищем имя (identifier)
            else if (childType.equals("identifier")) {
                name = getNodeText(child, content);
            }
        }

        if (name != null) {
            return new ParameterInfo(name, type != null ? type : "Object", false);
        }
        return null;
    }

    /**
     * Извлекает varargs параметр (Java).
     * Структура: [type] [...] [variable_declarator -> identifier]
     */
    private static ParameterInfo extractSpreadParameter(TSNode paramNode, String content) {
        String type = null;
        String name = null;

        int childCount = paramNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            if (isTypeNode(childType)) {
                type = getNodeText(child, content);
            }
            else if (childType.equals("variable_declarator")) {
                TSNode idNode = findChildByType(child, "identifier");
                if (idNode != null) {
                    name = getNodeText(idNode, content);
                }
            }
        }

        if (name != null) {
            // Для varargs добавляем [] к типу
            String varargType = (type != null ? type : "Object") + "[]";
            return new ParameterInfo(name, varargType, true);
        }
        return null;
    }

    /**
     * Извлекает Python параметр.
     */
    private static ParameterInfo extractPythonParameter(TSNode paramNode, String content) {
        String name = null;
        String type = "Any";

        // typed_parameter: name: Type
        // default_parameter: name = value
        // typed_default_parameter: name: Type = value

        TSNode nameNode = findChildByType(paramNode, "identifier");
        if (nameNode != null) {
            name = getNodeText(nameNode, content);
        }

        TSNode typeNode = findChildByType(paramNode, "type");
        if (typeNode != null) {
            type = getNodeText(typeNode, content);
        }

        if (name != null) {
            return new ParameterInfo(name, type, false);
        }
        return null;
    }

    /**
     * Извлекает Go параметры.
     * Go может иметь несколько имён с одним типом: (a, b int)
     */
    private static List<ParameterInfo> extractGoParameters(TSNode paramNode, String content) {
        List<ParameterInfo> params = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String type = null;

        int childCount = paramNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            if (childType.equals("identifier")) {
                names.add(getNodeText(child, content));
            }
            else if (isTypeNode(childType) || childType.equals("qualified_type") ||
                     childType.equals("pointer_type") || childType.equals("slice_type")) {
                type = getNodeText(child, content);
            }
        }

        // Если тип найден, создаём параметры для всех имён
        if (!names.isEmpty()) {
            String finalType = type != null ? type : "interface{}";
            for (String name : names) {
                params.add(new ParameterInfo(name, finalType, false));
            }
        }

        return params;
    }

    /**
     * Проверяет, является ли узел типом.
     */
    private static boolean isTypeNode(String nodeType) {
        return nodeType.equals("type_identifier") ||
               nodeType.equals("generic_type") ||
               nodeType.equals("array_type") ||
               nodeType.equals("integral_type") ||
               nodeType.equals("floating_point_type") ||
               nodeType.equals("boolean_type") ||
               nodeType.equals("void_type") ||
               nodeType.equals("scoped_type_identifier") ||
               nodeType.equals("primitive_type");
    }

    /**
     * Проверяет, находимся ли мы внутри Python параметров.
     */
    private static boolean isInsidePythonParams(TSNode paramsNode) {
        return paramsNode.getType().equals("parameters");
    }

    // ==================== Method Signature Extraction ====================

    /**
     * Полная информация о сигнатуре метода, извлечённая из AST.
     */
    public record MethodSignatureAST(
            String name,
            String accessModifier,
            String returnType,
            List<ParameterInfoAST> parameters,
            boolean isStatic,
            boolean isAsync
    ) {}

    /**
     * Информация о параметре метода с поддержкой значений по умолчанию.
     */
    public record ParameterInfoAST(
            String name,
            String type,
            String defaultValue,
            boolean isVarargs
    ) {
        public ParameterInfoAST(String name, String type) {
            this(name, type, null, false);
        }
    }

    /**
     * Извлекает полную сигнатуру метода из AST узла.
     * Работает для Java, Kotlin, Python, JavaScript, TypeScript.
     *
     * @param methodNode узел метода (method_declaration, function_definition и т.д.)
     * @param content содержимое файла
     * @param langId идентификатор языка
     * @return полная информация о сигнатуре
     */
    public static MethodSignatureAST extractMethodSignatureAST(TSNode methodNode, String content, String langId) {
        if (methodNode == null || methodNode.isNull()) return null;

        String nodeType = methodNode.getType();
        return switch (langId) {
            case "java", "kotlin" -> extractJavaKotlinSignature(methodNode, content, langId);
            case "python" -> extractPythonSignature(methodNode, content);
            case "javascript", "typescript", "tsx", "jsx" -> extractJstsSignature(methodNode, content, langId);
            case "go" -> extractGoSignature(methodNode, content);
            case "c", "cpp" -> extractCSignature(methodNode, content);
            default -> extractGenericSignature(methodNode, content);
        };
    }

    /**
     * Извлекает сигнатуру Java/Kotlin метода из AST.
     */
    private static MethodSignatureAST extractJavaKotlinSignature(TSNode node, String content, String langId) {
        String name = null;
        String accessModifier = "public";
        String returnType = "void";
        boolean isStatic = false;
        List<ParameterInfoAST> params = new ArrayList<>();

        // Ищем имя метода
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode != null) {
            name = getNodeText(nameNode, content);
        }

        // Ищем модификаторы
        TSNode modifiersNode = findChildByType(node, "modifiers");
        if (modifiersNode != null) {
            String modifiersText = getNodeText(modifiersNode, content);
            if (modifiersText.contains("private")) accessModifier = "private";
            else if (modifiersText.contains("protected")) accessModifier = "protected";
            else if (modifiersText.contains("internal")) accessModifier = "internal";
            isStatic = modifiersText.contains("static");
        }

        // Ищем тип возврата
        TSNode returnTypeNode = findFirstTypeNode(node);
        if (returnTypeNode != null) {
            returnType = getNodeText(returnTypeNode, content);
        }

        // Извлекаем параметры с default values
        TSNode paramsNode = findChildByType(node, "formal_parameters");
        if (paramsNode != null) {
            params = extractParametersWithDefaults(paramsNode, content, langId);
        }

        return new MethodSignatureAST(name, accessModifier, returnType, params, isStatic, false);
    }

    /**
     * Извлекает сигнатуру Python функции из AST.
     */
    private static MethodSignatureAST extractPythonSignature(TSNode node, String content) {
        String name = null;
        String accessModifier = "public";
        List<ParameterInfoAST> params = new ArrayList<>();
        boolean isAsync = false;

        // Проверяем async
        String nodeType = node.getType();
        if (nodeType.equals("decorated_definition")) {
            // Может быть async внутри
            TSNode innerFunc = findChildByType(node, "function_definition");
            if (innerFunc != null) {
                node = innerFunc;
            }
        }

        // Ищем имя
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) {
            nameNode = findChildByType(node, "name"); // Python использует "name" узел
        }
        if (nameNode != null) {
            name = getNodeText(nameNode, content);
            if (name.startsWith("_")) accessModifier = "private";
            if (name.startsWith("__") && !name.endsWith("__")) accessModifier = "private";
        }

        // Ищем параметры
        TSNode paramsNode = findChildByType(node, "parameters");
        if (paramsNode != null) {
            params = extractPythonParametersWithDefaults(paramsNode, content);
        }

        // Ищем return type annotation
        String returnType = "";
        TSNode returnAnnotation = findChildByType(node, "type");
        if (returnAnnotation != null) {
            returnType = getNodeText(returnAnnotation, content);
        }

        return new MethodSignatureAST(name, accessModifier, returnType, params, false, isAsync);
    }

    /**
     * Извлекает сигнатуру JavaScript/TypeScript функции из AST.
     */
    private static MethodSignatureAST extractJstsSignature(TSNode node, String content, String langId) {
        String name = null;
        String returnType = "";
        List<ParameterInfoAST> params = new ArrayList<>();
        boolean isAsync = false;

        String nodeText = getNodeText(node, content);
        isAsync = nodeText.startsWith("async ");

        // Ищем имя
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) {
            nameNode = findChildByType(node, "property_identifier");
        }
        if (nameNode != null) {
            name = getNodeText(nameNode, content);
        }

        // Ищем параметры
        TSNode paramsNode = findChildByType(node, "formal_parameters");
        if (paramsNode == null) {
            paramsNode = findChildByType(node, "parameters");
        }
        if (paramsNode != null) {
            params = extractJstsParametersWithDefaults(paramsNode, content, langId);
        }

        // TypeScript: ищем return type
        TSNode returnTypeNode = findChildByType(node, "type_annotation");
        if (returnTypeNode != null) {
            TSNode typeNode = findFirstTypeNode(returnTypeNode);
            if (typeNode != null) {
                returnType = getNodeText(typeNode, content);
            }
        }

        return new MethodSignatureAST(name, "public", returnType, params, false, isAsync);
    }

    /**
     * Извлекает сигнатуру Go функции из AST.
     */
    private static MethodSignatureAST extractGoSignature(TSNode node, String content) {
        String name = null;
        String returnType = "";
        List<ParameterInfoAST> params = new ArrayList<>();

        // Ищем имя
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode != null) {
            name = getNodeText(nameNode, content);
        }

        // Ищем параметры
        TSNode paramsNode = findChildByType(node, "parameter_list");
        if (paramsNode != null) {
            params = extractGoParametersWithDefaults(paramsNode, content);
        }

        // Ищем тип возврата
        TSNode resultNode = findChildByType(node, "result");
        if (resultNode == null) {
            // Go может иметь тип без "result" wrapper
            TSNode typeNode = null;
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                TSNode child = node.getChild(i);
                if (child != null && isTypeNode(child.getType())) {
                    typeNode = child;
                }
            }
            if (typeNode != null) {
                returnType = getNodeText(typeNode, content);
            }
        } else {
            returnType = getNodeText(resultNode, content);
        }

        return new MethodSignatureAST(name, "public", returnType, params, false, false);
    }

    /**
     * Извлекает сигнатуру C/C++ функции из AST.
     */
    private static MethodSignatureAST extractCSignature(TSNode node, String content) {
        String name = null;
        String returnType = "";
        List<ParameterInfoAST> params = new ArrayList<>();
        boolean isStatic = false;

        String fullText = getNodeText(node, content);
        isStatic = fullText.contains("static ");

        // C/C++ может иметь function_declarator вложенным
        TSNode declaratorNode = findChildByType(node, "function_declarator");
        if (declaratorNode == null) {
            declaratorNode = findChildByType(node, "declarator");
            if (declaratorNode != null) {
                TSNode funcDecl = findChildByType(declaratorNode, "function_declarator");
                if (funcDecl != null) declaratorNode = funcDecl;
            }
        }

        if (declaratorNode != null) {
            TSNode nameNode = findChildByType(declaratorNode, "identifier");
            if (nameNode != null) {
                name = getNodeText(nameNode, content);
            }

            TSNode paramsNode = findChildByType(declaratorNode, "parameter_list");
            if (paramsNode != null) {
                params = extractCParametersWithDefaults(paramsNode, content);
            }
        }

        // Ищем тип возврата
        TSNode typeNode = findFirstTypeNode(node);
        if (typeNode != null) {
            returnType = getNodeText(typeNode, content);
        }

        return new MethodSignatureAST(name, "public", returnType, params, isStatic, false);
    }

    /**
     * Извлекает сигнатуру для неизвестного языка (fallback).
     */
    private static MethodSignatureAST extractGenericSignature(TSNode node, String content) {
        String name = null;

        // Ищем identifier
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode != null) {
            name = getNodeText(nameNode, content);
        }

        List<ParameterInfo> basicParams = extractParameters(node, content);
        List<ParameterInfoAST> params = basicParams.stream()
                .map(p -> new ParameterInfoAST(p.name(), p.type(), null, p.isVarargs()))
                .toList();

        return new MethodSignatureAST(name, "public", "", params, false, false);
    }

    // ==================== Parameter extraction with defaults ====================

    /**
     * Извлекает Java/Kotlin параметры с поддержкой default values (Kotlin).
     */
    private static List<ParameterInfoAST> extractParametersWithDefaults(TSNode paramsNode, String content, String langId) {
        List<ParameterInfoAST> params = new ArrayList<>();

        int childCount = paramsNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramsNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            if (childType.equals("formal_parameter")) {
                ParameterInfoAST param = extractJavaParameter(child, content);
                if (param != null) params.add(param);
            } else if (childType.equals("spread_parameter")) {
                ParameterInfoAST param = extractJavaSpreadParameter(child, content);
                if (param != null) params.add(param);
            }
        }

        return params;
    }

    private static ParameterInfoAST extractJavaParameter(TSNode paramNode, String content) {
        String type = null;
        String name = null;
        String defaultValue = null;

        int childCount = paramNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            if (childType.equals("modifiers") || childType.startsWith("annotation")) {
                continue;
            }

            if (type == null && isTypeNode(childType)) {
                type = getNodeText(child, content);
            } else if (childType.equals("identifier")) {
                name = getNodeText(child, content);
            }
            // Kotlin default value: = expression
            else if (childType.equals("=")) {
                // Next sibling is the value
                if (i + 1 < childCount) {
                    TSNode valueNode = paramNode.getChild(i + 1);
                    if (valueNode != null) {
                        defaultValue = getNodeText(valueNode, content);
                    }
                }
            }
        }

        if (name != null) {
            return new ParameterInfoAST(name, type != null ? type : "Object", defaultValue, false);
        }
        return null;
    }

    private static ParameterInfoAST extractJavaSpreadParameter(TSNode paramNode, String content) {
        String type = null;
        String name = null;

        int childCount = paramNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            if (isTypeNode(childType)) {
                type = getNodeText(child, content);
            } else if (childType.equals("variable_declarator")) {
                TSNode idNode = findChildByType(child, "identifier");
                if (idNode != null) {
                    name = getNodeText(idNode, content);
                }
            }
        }

        if (name != null) {
            return new ParameterInfoAST(name, (type != null ? type : "Object") + "[]", null, true);
        }
        return null;
    }

    /**
     * Извлекает Python параметры с default values.
     */
    private static List<ParameterInfoAST> extractPythonParametersWithDefaults(TSNode paramsNode, String content) {
        List<ParameterInfoAST> params = new ArrayList<>();

        int childCount = paramsNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramsNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            if (childType.equals("identifier")) {
                String name = getNodeText(child, content);
                if (!name.equals("self") && !name.equals("cls")) {
                    params.add(new ParameterInfoAST(name, "Any", null, false));
                }
            } else if (childType.equals("typed_parameter")) {
                params.add(extractPythonTypedParameter(child, content));
            } else if (childType.equals("default_parameter")) {
                params.add(extractPythonDefaultParameter(child, content));
            } else if (childType.equals("typed_default_parameter")) {
                params.add(extractPythonTypedDefaultParameter(child, content));
            } else if (childType.equals("list_splat_pattern") || childType.equals("dictionary_splat_pattern")) {
                // *args, **kwargs
                TSNode idNode = findChildByType(child, "identifier");
                if (idNode != null) {
                    String name = getNodeText(idNode, content);
                    boolean isKwargs = childType.equals("dictionary_splat_pattern");
                    params.add(new ParameterInfoAST(name, isKwargs ? "dict" : "list", null, true));
                }
            }
        }

        return params;
    }

    private static ParameterInfoAST extractPythonTypedParameter(TSNode node, String content) {
        String name = null;
        String type = "Any";

        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode != null) {
            name = getNodeText(nameNode, content);
        }

        TSNode typeNode = findChildByType(node, "type");
        if (typeNode != null) {
            type = getNodeText(typeNode, content);
        }

        return new ParameterInfoAST(name, type, null, false);
    }

    private static ParameterInfoAST extractPythonDefaultParameter(TSNode node, String content) {
        String name = null;
        String defaultValue = null;

        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode != null) {
            name = getNodeText(nameNode, content);
        }

        // Default value - найти узел после "="
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && child.getType().equals("=")) {
                if (i + 1 < childCount) {
                    TSNode valueNode = node.getChild(i + 1);
                    if (valueNode != null) {
                        defaultValue = getNodeText(valueNode, content);
                    }
                }
            }
        }

        return new ParameterInfoAST(name, "Any", defaultValue, false);
    }

    private static ParameterInfoAST extractPythonTypedDefaultParameter(TSNode node, String content) {
        String name = null;
        String type = "Any";
        String defaultValue = null;

        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode != null) {
            name = getNodeText(nameNode, content);
        }

        TSNode typeNode = findChildByType(node, "type");
        if (typeNode != null) {
            type = getNodeText(typeNode, content);
        }

        // Find default value
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && child.getType().equals("=")) {
                if (i + 1 < childCount) {
                    TSNode valueNode = node.getChild(i + 1);
                    if (valueNode != null) {
                        defaultValue = getNodeText(valueNode, content);
                    }
                }
            }
        }

        return new ParameterInfoAST(name, type, defaultValue, false);
    }

    /**
     * Извлекает JavaScript/TypeScript параметры с default values.
     */
    private static List<ParameterInfoAST> extractJstsParametersWithDefaults(TSNode paramsNode, String content, String langId) {
        List<ParameterInfoAST> params = new ArrayList<>();

        int childCount = paramsNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramsNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            if (childType.equals("identifier")) {
                params.add(new ParameterInfoAST(getNodeText(child, content), "any", null, false));
            } else if (childType.equals("required_parameter") || childType.equals("optional_parameter")) {
                params.add(extractTsParameter(child, content));
            } else if (childType.equals("rest_pattern") || childType.equals("rest_parameter")) {
                TSNode idNode = findChildByType(child, "identifier");
                if (idNode != null) {
                    params.add(new ParameterInfoAST(getNodeText(idNode, content), "any[]", null, true));
                }
            } else if (childType.equals("assignment_pattern")) {
                // name = defaultValue
                params.add(extractJsAssignmentParameter(child, content));
            }
        }

        return params;
    }

    private static ParameterInfoAST extractTsParameter(TSNode node, String content) {
        String name = null;
        String type = "any";
        String defaultValue = null;

        TSNode idNode = findChildByType(node, "identifier");
        if (idNode != null) {
            name = getNodeText(idNode, content);
        }

        TSNode typeAnnotation = findChildByType(node, "type_annotation");
        if (typeAnnotation != null) {
            TSNode typeNode = findFirstTypeNode(typeAnnotation);
            if (typeNode != null) {
                type = getNodeText(typeNode, content);
            }
        }

        // Check for default value
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && child.getType().equals("=")) {
                if (i + 1 < childCount) {
                    defaultValue = getNodeText(node.getChild(i + 1), content);
                }
            }
        }

        return new ParameterInfoAST(name, type, defaultValue, false);
    }

    private static ParameterInfoAST extractJsAssignmentParameter(TSNode node, String content) {
        String name = null;
        String defaultValue = null;

        TSNode leftNode = node.getChild(0);
        if (leftNode != null && leftNode.getType().equals("identifier")) {
            name = getNodeText(leftNode, content);
        }

        // Default is after "="
        if (node.getChildCount() >= 3) {
            TSNode valueNode = node.getChild(2);
            if (valueNode != null) {
                defaultValue = getNodeText(valueNode, content);
            }
        }

        return new ParameterInfoAST(name, "any", defaultValue, false);
    }

    /**
     * Извлекает Go параметры.
     */
    private static List<ParameterInfoAST> extractGoParametersWithDefaults(TSNode paramsNode, String content) {
        List<ParameterInfoAST> params = new ArrayList<>();

        int childCount = paramsNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramsNode.getChild(i);
            if (child == null || child.isNull()) continue;

            if (child.getType().equals("parameter_declaration")) {
                params.addAll(extractGoParamDeclaration(child, content));
            }
        }

        return params;
    }

    private static List<ParameterInfoAST> extractGoParamDeclaration(TSNode node, String content) {
        List<ParameterInfoAST> params = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String type = "interface{}";
        boolean isVariadic = false;

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            if (childType.equals("identifier")) {
                names.add(getNodeText(child, content));
            } else if (childType.equals("variadic_parameter_declaration")) {
                isVariadic = true;
                TSNode idNode = findChildByType(child, "identifier");
                if (idNode != null) names.add(getNodeText(idNode, content));
                TSNode typeNode = findFirstTypeNode(child);
                if (typeNode != null) type = getNodeText(typeNode, content);
            } else if (isTypeNode(childType) || childType.equals("qualified_type") ||
                       childType.equals("pointer_type") || childType.equals("slice_type") ||
                       childType.equals("map_type") || childType.equals("channel_type")) {
                type = getNodeText(child, content);
            }
        }

        for (String name : names) {
            params.add(new ParameterInfoAST(name, type, null, isVariadic));
        }

        return params;
    }

    /**
     * Извлекает C/C++ параметры.
     */
    private static List<ParameterInfoAST> extractCParametersWithDefaults(TSNode paramsNode, String content) {
        List<ParameterInfoAST> params = new ArrayList<>();

        int childCount = paramsNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = paramsNode.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            if (childType.equals("parameter_declaration")) {
                ParameterInfoAST param = extractCParameter(child, content);
                if (param != null) params.add(param);
            } else if (childType.equals("variadic_parameter")) {
                params.add(new ParameterInfoAST("...", "...", null, true));
            }
        }

        return params;
    }

    private static ParameterInfoAST extractCParameter(TSNode node, String content) {
        String type = null;
        String name = null;
        String defaultValue = null;

        // C/C++ has complex declarator structure
        TSNode typeNode = findFirstTypeNode(node);
        if (typeNode != null) {
            type = getNodeText(typeNode, content);
        }

        TSNode declaratorNode = findChildByType(node, "declarator");
        if (declaratorNode == null) {
            declaratorNode = findChildByType(node, "identifier");
        }
        if (declaratorNode != null) {
            if (declaratorNode.getType().equals("identifier")) {
                name = getNodeText(declaratorNode, content);
            } else {
                TSNode idNode = findChildByType(declaratorNode, "identifier");
                if (idNode != null) name = getNodeText(idNode, content);
            }
        }

        // C++ default value
        TSNode defaultExpr = findChildByType(node, "default_value");
        if (defaultExpr != null) {
            defaultValue = getNodeText(defaultExpr, content);
        }

        if (name != null || type != null) {
            return new ParameterInfoAST(name != null ? name : "", type != null ? type : "int", defaultValue, false);
        }
        return null;
    }

    /**
     * Находит первый узел типа в дочерних узлах.
     */
    private static TSNode findFirstTypeNode(TSNode parent) {
        int childCount = parent.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = parent.getChild(i);
            if (child != null && !child.isNull()) {
                String childType = child.getType();
                if (isTypeNode(childType)) {
                    return child;
                }
            }
        }
        return null;
    }
    
    // ==================== Variable Analysis ====================

    /**
     * Информация о переменной, извлечённая из AST.
     */
    public record VariableInfoAST(
            String name,
            String type,
            boolean isDeclaration,
            int line,
            int column
    ) {}

    /**
     * Результат анализа переменных в блоке кода.
     */
    public record VariableAnalysisResult(
            List<VariableInfoAST> declaredVariables,
            List<String> usedVariables,
            Map<String, String> variableTypes
    ) {}

    /**
     * Анализирует переменные в указанном диапазоне строк.
     * Использует AST для точного определения объявлений и использований.
     *
     * @param root корневой узел AST
     * @param content содержимое файла
     * @param startLine начальная строка (0-based)
     * @param endLine конечная строка (0-based)
     * @param langId идентификатор языка
     * @return результат анализа переменных
     */
    public static VariableAnalysisResult analyzeVariablesInRange(
            TSNode root, String content, int startLine, int endLine, String langId) {

        List<VariableInfoAST> declared = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Map<String, String> types = new HashMap<>();

        collectVariablesInRange(root, content, startLine, endLine, langId, declared, used, types);

        return new VariableAnalysisResult(declared, new ArrayList<>(used), types);
    }

    /**
     * Рекурсивно собирает переменные в указанном диапазоне строк.
     */
    private static void collectVariablesInRange(
            TSNode node, String content, int startLine, int endLine, String langId,
            List<VariableInfoAST> declared, Set<String> used, Map<String, String> types) {

        if (node == null || node.isNull()) return;

        int nodeLine = node.getStartPoint().getRow();
        int nodeEndLine = node.getEndPoint().getRow();

        // Пропускаем узлы вне диапазона
        if (nodeEndLine < startLine || nodeLine > endLine) {
            return;
        }

        String nodeType = node.getType();

        // Обрабатываем объявления переменных
        if (isVariableDeclarationNode(nodeType, langId)) {
            VariableInfoAST varInfo = extractVariableDeclaration(node, content, langId);
            if (varInfo != null) {
                declared.add(varInfo);
                types.put(varInfo.name(), varInfo.type());
            }
        }

        // Обрабатываем использования переменных (identifier)
        if (nodeType.equals("identifier") && nodeLine >= startLine && nodeLine <= endLine) {
            // Проверяем, что это не объявление
            TSNode parent = node.getParent();
            if (parent != null && !isDeclarationContext(parent, node, langId)) {
                String varName = getNodeText(node, content);
                if (!isKeyword(varName, langId) && !isMethodCall(parent, node)) {
                    used.add(varName);
                }
            }
        }

        // Рекурсивно обходим дочерние узлы
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            collectVariablesInRange(child, content, startLine, endLine, langId, declared, used, types);
        }
    }

    /**
     * Проверяет, является ли узел объявлением переменной.
     */
    private static boolean isVariableDeclarationNode(String nodeType, String langId) {
        return switch (langId) {
            case "java", "kotlin" -> nodeType.equals("local_variable_declaration") ||
                    nodeType.equals("enhanced_for_statement") ||
                    nodeType.equals("formal_parameter") ||
                    nodeType.equals("catch_formal_parameter") ||
                    nodeType.equals("resource");
            case "python" -> nodeType.equals("assignment") ||
                    nodeType.equals("for_statement") ||
                    nodeType.equals("with_item");
            case "javascript", "typescript", "tsx", "jsx" -> nodeType.equals("variable_declaration") ||
                    nodeType.equals("lexical_declaration") ||
                    nodeType.equals("for_in_statement") ||
                    nodeType.equals("for_statement");
            case "go" -> nodeType.equals("short_var_declaration") ||
                    nodeType.equals("var_declaration") ||
                    nodeType.equals("range_clause");
            case "c", "cpp" -> nodeType.equals("declaration") ||
                    nodeType.equals("init_declarator");
            default -> false;
        };
    }

    /**
     * Извлекает информацию об объявлении переменной из AST узла.
     */
    private static VariableInfoAST extractVariableDeclaration(TSNode node, String content, String langId) {
        String nodeType = node.getType();

        return switch (langId) {
            case "java", "kotlin" -> extractJavaVariableDeclaration(node, content);
            case "python" -> extractPythonVariableDeclaration(node, content);
            case "javascript", "typescript", "tsx", "jsx" -> extractJsVariableDeclaration(node, content);
            case "go" -> extractGoVariableDeclaration(node, content);
            case "c", "cpp" -> extractCVariableDeclaration(node, content);
            default -> null;
        };
    }

    private static VariableInfoAST extractJavaVariableDeclaration(TSNode node, String content) {
        String nodeType = node.getType();

        if (nodeType.equals("local_variable_declaration")) {
            // Java: Type var = value;
            TSNode typeNode = findFirstTypeNode(node);
            String type = typeNode != null ? getNodeText(typeNode, content) : "Object";

            TSNode declarator = findChildByType(node, "variable_declarator");
            if (declarator != null) {
                TSNode nameNode = findChildByType(declarator, "identifier");
                if (nameNode != null) {
                    String name = getNodeText(nameNode, content);
                    return new VariableInfoAST(name, type, true,
                            nameNode.getStartPoint().getRow(), nameNode.getStartPoint().getColumn());
                }
            }
        } else if (nodeType.equals("enhanced_for_statement")) {
            // Java: for (Type var : collection)
            TSNode typeNode = findFirstTypeNode(node);
            String type = typeNode != null ? getNodeText(typeNode, content) : "Object";

            TSNode nameNode = findChildByType(node, "identifier");
            if (nameNode != null) {
                String name = getNodeText(nameNode, content);
                return new VariableInfoAST(name, type, true,
                        nameNode.getStartPoint().getRow(), nameNode.getStartPoint().getColumn());
            }
        } else if (nodeType.equals("formal_parameter") || nodeType.equals("catch_formal_parameter")) {
            // Параметр или catch переменная
            TSNode typeNode = findFirstTypeNode(node);
            String type = typeNode != null ? getNodeText(typeNode, content) : "Object";

            TSNode nameNode = findChildByType(node, "identifier");
            if (nameNode != null) {
                String name = getNodeText(nameNode, content);
                return new VariableInfoAST(name, type, true,
                        nameNode.getStartPoint().getRow(), nameNode.getStartPoint().getColumn());
            }
        }

        return null;
    }

    private static VariableInfoAST extractPythonVariableDeclaration(TSNode node, String content) {
        String nodeType = node.getType();

        if (nodeType.equals("assignment")) {
            // Python: var = value
            TSNode leftNode = node.getChild(0);
            if (leftNode != null && leftNode.getType().equals("identifier")) {
                String name = getNodeText(leftNode, content);
                return new VariableInfoAST(name, "Any", true,
                        leftNode.getStartPoint().getRow(), leftNode.getStartPoint().getColumn());
            }
        } else if (nodeType.equals("for_statement")) {
            // Python: for var in iterable
            TSNode leftNode = findChildByType(node, "identifier");
            if (leftNode != null) {
                String name = getNodeText(leftNode, content);
                return new VariableInfoAST(name, "Any", true,
                        leftNode.getStartPoint().getRow(), leftNode.getStartPoint().getColumn());
            }
        }

        return null;
    }

    private static VariableInfoAST extractJsVariableDeclaration(TSNode node, String content) {
        String nodeType = node.getType();

        if (nodeType.equals("variable_declaration") || nodeType.equals("lexical_declaration")) {
            TSNode declarator = findChildByType(node, "variable_declarator");
            if (declarator != null) {
                TSNode nameNode = findChildByType(declarator, "identifier");
                if (nameNode != null) {
                    String name = getNodeText(nameNode, content);

                    // Пытаемся найти тип из TypeScript annotation
                    String type = "any";
                    TSNode typeAnnotation = findChildByType(declarator, "type_annotation");
                    if (typeAnnotation != null) {
                        TSNode typeNode = findFirstTypeNode(typeAnnotation);
                        if (typeNode != null) {
                            type = getNodeText(typeNode, content);
                        }
                    }

                    return new VariableInfoAST(name, type, true,
                            nameNode.getStartPoint().getRow(), nameNode.getStartPoint().getColumn());
                }
            }
        }

        return null;
    }

    private static VariableInfoAST extractGoVariableDeclaration(TSNode node, String content) {
        String nodeType = node.getType();

        if (nodeType.equals("short_var_declaration")) {
            // Go: var := value
            TSNode leftNode = node.getChild(0);
            if (leftNode != null) {
                TSNode idNode = leftNode.getType().equals("identifier") ? leftNode :
                        findChildByType(leftNode, "identifier");
                if (idNode != null) {
                    String name = getNodeText(idNode, content);
                    return new VariableInfoAST(name, "interface{}", true,
                            idNode.getStartPoint().getRow(), idNode.getStartPoint().getColumn());
                }
            }
        } else if (nodeType.equals("var_declaration")) {
            TSNode spec = findChildByType(node, "var_spec");
            if (spec != null) {
                TSNode nameNode = findChildByType(spec, "identifier");
                if (nameNode != null) {
                    String name = getNodeText(nameNode, content);

                    String type = "interface{}";
                    TSNode typeNode = findFirstTypeNode(spec);
                    if (typeNode != null) {
                        type = getNodeText(typeNode, content);
                    }

                    return new VariableInfoAST(name, type, true,
                            nameNode.getStartPoint().getRow(), nameNode.getStartPoint().getColumn());
                }
            }
        }

        return null;
    }

    private static VariableInfoAST extractCVariableDeclaration(TSNode node, String content) {
        String nodeType = node.getType();

        if (nodeType.equals("declaration")) {
            TSNode typeNode = findFirstTypeNode(node);
            String type = typeNode != null ? getNodeText(typeNode, content) : "int";

            TSNode declarator = findChildByType(node, "init_declarator");
            if (declarator == null) {
                declarator = findChildByType(node, "declarator");
            }
            if (declarator != null) {
                TSNode nameNode = findChildByType(declarator, "identifier");
                if (nameNode == null && declarator.getType().equals("identifier")) {
                    nameNode = declarator;
                }
                if (nameNode != null) {
                    String name = getNodeText(nameNode, content);
                    return new VariableInfoAST(name, type, true,
                            nameNode.getStartPoint().getRow(), nameNode.getStartPoint().getColumn());
                }
            }
        }

        return null;
    }

    /**
     * Проверяет, является ли родительский контекст объявлением.
     */
    private static boolean isDeclarationContext(TSNode parent, TSNode identifierNode, String langId) {
        String parentType = parent.getType();

        return switch (langId) {
            case "java", "kotlin" -> parentType.equals("variable_declarator") ||
                    parentType.equals("formal_parameter") ||
                    parentType.equals("catch_formal_parameter") ||
                    parentType.equals("type_identifier") ||
                    parentType.equals("method_declaration") ||
                    parentType.equals("class_declaration");
            case "python" -> {
                // В Python первый child assignment - это объявление
                if (parentType.equals("assignment")) {
                    yield parent.getChild(0) == identifierNode;
                }
                yield parentType.equals("function_definition") ||
                        parentType.equals("class_definition");
            }
            case "javascript", "typescript", "tsx", "jsx" -> parentType.equals("variable_declarator") ||
                    parentType.equals("function_declaration") ||
                    parentType.equals("class_declaration") ||
                    parentType.equals("property_identifier");
            case "go" -> parentType.equals("short_var_declaration") ||
                    parentType.equals("var_spec") ||
                    parentType.equals("function_declaration");
            case "c", "cpp" -> parentType.equals("init_declarator") ||
                    parentType.equals("declarator") ||
                    parentType.equals("function_definition");
            default -> false;
        };
    }

    /**
     * Проверяет, является ли identifier вызовом метода.
     */
    private static boolean isMethodCall(TSNode parent, TSNode identifierNode) {
        String parentType = parent.getType();
        if (parentType.equals("call_expression") || parentType.equals("method_invocation")) {
            // Имя функции - первый дочерний узел
            return parent.getChild(0) == identifierNode;
        }
        return false;
    }

    /**
     * Проверяет, является ли строка ключевым словом языка.
     */
    private static boolean isKeyword(String name, String langId) {
        Set<String> keywords = switch (langId) {
            case "java" -> Set.of(
                    "this", "super", "true", "false", "null", "new", "return", "if", "else",
                    "for", "while", "do", "switch", "case", "default", "break", "continue",
                    "try", "catch", "finally", "throw", "throws", "class", "interface", "enum",
                    "extends", "implements", "import", "package", "public", "private", "protected",
                    "static", "final", "abstract", "synchronized", "volatile", "transient",
                    "native", "strictfp", "instanceof", "void", "int", "long", "short", "byte",
                    "char", "boolean", "double", "float", "var"
            );
            case "python" -> Set.of(
                    "self", "cls", "True", "False", "None", "and", "or", "not", "is", "in",
                    "if", "else", "elif", "for", "while", "try", "except", "finally", "with",
                    "as", "def", "class", "return", "yield", "raise", "import", "from", "pass",
                    "break", "continue", "lambda", "global", "nonlocal", "assert", "del"
            );
            case "javascript", "typescript", "tsx", "jsx" -> Set.of(
                    "this", "super", "true", "false", "null", "undefined", "new", "return",
                    "if", "else", "for", "while", "do", "switch", "case", "default", "break",
                    "continue", "try", "catch", "finally", "throw", "class", "extends",
                    "import", "export", "function", "const", "let", "var", "typeof", "instanceof",
                    "async", "await", "yield"
            );
            case "go" -> Set.of(
                    "true", "false", "nil", "iota", "if", "else", "for", "range", "switch",
                    "case", "default", "break", "continue", "return", "go", "defer", "select",
                    "chan", "func", "type", "struct", "interface", "map", "package", "import",
                    "const", "var"
            );
            case "c", "cpp" -> Set.of(
                    "true", "false", "NULL", "nullptr", "if", "else", "for", "while", "do",
                    "switch", "case", "default", "break", "continue", "return", "goto",
                    "struct", "class", "enum", "union", "typedef", "sizeof", "static",
                    "extern", "const", "volatile", "void", "int", "long", "short", "char",
                    "float", "double", "signed", "unsigned", "auto", "register"
            );
            default -> Set.of();
        };
        return keywords.contains(name);
    }

    /**
     * Извлекает переменные доступные во внешнем scope перед указанной строкой.
     * Включает параметры методов и локальные переменные.
     *
     * @param root корневой узел AST
     * @param content содержимое файла
     * @param beforeLine строка до которой искать (0-based)
     * @param langId идентификатор языка
     * @return карта: имя переменной -> тип
     */
    public static Map<String, String> extractOuterScopeVariables(
            TSNode root, String content, int beforeLine, String langId) {

        Map<String, String> vars = new LinkedHashMap<>();

        // Находим содержащий метод/функцию
        TSNode containingMethod = findContainingFunction(root, beforeLine);
        if (containingMethod == null) {
            return vars;
        }

        // Извлекаем параметры функции
        TSNode paramsNode = findChildByType(containingMethod, "formal_parameters");
        if (paramsNode == null) {
            paramsNode = findChildByType(containingMethod, "parameters");
        }
        if (paramsNode == null) {
            paramsNode = findChildByType(containingMethod, "parameter_list");
        }

        if (paramsNode != null) {
            List<ParameterInfo> params = extractParameters(containingMethod, content);
            for (ParameterInfo param : params) {
                vars.put(param.name(), param.type());
            }
        }

        // Собираем локальные переменные до указанной строки
        int methodStartLine = containingMethod.getStartPoint().getRow();
        collectVariablesBeforeLine(containingMethod, content, beforeLine, langId, vars);

        return vars;
    }

    /**
     * Находит функцию/метод содержащую указанную строку.
     */
    private static TSNode findContainingFunction(TSNode node, int line) {
        if (node == null || node.isNull()) return null;

        String nodeType = node.getType();

        // Проверяем является ли это функцией/методом
        if (nodeType.equals("method_declaration") || nodeType.equals("function_declaration") ||
                nodeType.equals("function_definition") || nodeType.equals("constructor_declaration") ||
                nodeType.equals("function_item") || nodeType.equals("method_definition")) {
            int startRow = node.getStartPoint().getRow();
            int endRow = node.getEndPoint().getRow();
            if (startRow <= line && endRow >= line) {
                return node;
            }
        }

        // Рекурсивно ищем в дочерних
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            TSNode found = findContainingFunction(child, line);
            if (found != null) return found;
        }

        return null;
    }

    /**
     * Собирает переменные объявленные до указанной строки.
     */
    private static void collectVariablesBeforeLine(
            TSNode node, String content, int beforeLine, String langId, Map<String, String> vars) {

        if (node == null || node.isNull()) return;

        int nodeLine = node.getStartPoint().getRow();

        // Пропускаем узлы после целевой строки
        if (nodeLine >= beforeLine) return;

        String nodeType = node.getType();

        if (isVariableDeclarationNode(nodeType, langId)) {
            VariableInfoAST varInfo = extractVariableDeclaration(node, content, langId);
            if (varInfo != null && varInfo.line() < beforeLine) {
                vars.put(varInfo.name(), varInfo.type());
            }
        }

        // Рекурсивно обходим дочерние узлы
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            collectVariablesBeforeLine(child, content, beforeLine, langId, vars);
        }
    }

    // ==================== Package Extraction for Move ====================

    /**
     * Извлекает имя пакета/модуля из AST.
     *
     * @param root корневой узел AST
     * @param content содержимое файла
     * @param langId идентификатор языка
     * @return имя пакета или пустая строка
     */
    public static String extractPackageName(TSNode root, String content, String langId) {
        if (root == null || root.isNull()) return "";

        return switch (langId) {
            case "java" -> extractJavaPackage(root, content);
            case "kotlin" -> extractKotlinPackage(root, content);
            case "go" -> extractGoPackage(root, content);
            case "python" -> ""; // Python использует файловую структуру
            case "csharp" -> extractCSharpNamespace(root, content);
            default -> "";
        };
    }

    private static String extractJavaPackage(TSNode root, String content) {
        // Java: package_declaration -> scoped_identifier / identifier
        TSNode packageNode = findDescendantByType(root, "package_declaration");
        if (packageNode == null) return "";

        // Ищем scoped_identifier или identifier
        TSNode nameNode = findChildByType(packageNode, "scoped_identifier");
        if (nameNode == null) {
            nameNode = findChildByType(packageNode, "identifier");
        }

        if (nameNode != null) {
            return getNodeText(nameNode, content);
        }

        return "";
    }

    private static String extractKotlinPackage(TSNode root, String content) {
        // Kotlin: package_header -> identifier
        TSNode packageNode = findDescendantByType(root, "package_header");
        if (packageNode == null) return "";

        TSNode nameNode = findChildByType(packageNode, "identifier");
        if (nameNode != null) {
            return getNodeText(nameNode, content);
        }

        return "";
    }

    private static String extractGoPackage(TSNode root, String content) {
        // Go: package_clause -> package_identifier
        TSNode packageNode = findDescendantByType(root, "package_clause");
        if (packageNode == null) return "";

        TSNode nameNode = findChildByType(packageNode, "package_identifier");
        if (nameNode != null) {
            return getNodeText(nameNode, content);
        }

        return "";
    }

    private static String extractCSharpNamespace(TSNode root, String content) {
        // C#: namespace_declaration -> qualified_name / identifier
        TSNode namespaceNode = findDescendantByType(root, "namespace_declaration");
        if (namespaceNode == null) return "";

        TSNode nameNode = findChildByType(namespaceNode, "qualified_name");
        if (nameNode == null) {
            nameNode = findChildByType(namespaceNode, "identifier");
        }

        if (nameNode != null) {
            return getNodeText(nameNode, content);
        }

        return "";
    }

    /**
     * Находит потомка с указанным типом (BFS).
     */
    private static TSNode findDescendantByType(TSNode node, String type) {
        if (node == null || node.isNull()) return null;

        if (node.getType().equals(type)) {
            return node;
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            TSNode found = findDescendantByType(child, type);
            if (found != null) return found;
        }

        return null;
    }

    /**
     * Находит позицию для вставки нового члена класса.
     *
     * @param root корневой узел AST
     * @param className имя целевого класса
     * @param langId идентификатор языка
     * @return номер строки (0-based) для вставки, или -1 если класс не найден
     */
    public static int findClassInsertPosition(TSNode root, String className, String langId) {
        TSNode classNode = findClassByName(root, className, langId);
        if (classNode == null) return -1;

        // Ищем тело класса
        TSNode body = findChildByType(classNode, "class_body");
        if (body == null) body = findChildByType(classNode, "body");
        if (body == null) body = findChildByType(classNode, "declaration_list");

        if (body != null) {
            // Возвращаем строку перед закрывающей скобкой
            return body.getEndPoint().getRow();
        }

        return classNode.getEndPoint().getRow();
    }

    /**
     * Находит класс по имени в AST.
     */
    private static TSNode findClassByName(TSNode node, String className, String langId) {
        if (node == null || node.isNull()) return null;

        String nodeType = node.getType();

        // Проверяем, является ли это объявлением класса
        if (nodeType.equals("class_declaration") ||
                nodeType.equals("class_definition") ||
                nodeType.equals("struct_item") ||
                nodeType.equals("interface_declaration")) {

            TSNode nameNode = findChildByType(node, "identifier");
            if (nameNode == null) nameNode = findChildByType(node, "type_identifier");
            if (nameNode == null) nameNode = findChildByType(node, "simple_identifier");

            if (nameNode != null && getNodeText(nameNode, "").equals(className)) {
                return node;
            }
        }

        // Рекурсивно ищем
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            TSNode found = findClassByName(child, className, langId);
            if (found != null) return found;
        }

        return null;
    }

    // ==================== Value Extraction for Inline ====================

    /**
     * Извлекает значение переменной/константы из AST.
     * Ищет узел присваивания и возвращает правую часть.
     *
     * @param root корневой узел AST
     * @param content содержимое файла
     * @param line строка объявления (0-based)
     * @param symbolName имя переменной
     * @param langId идентификатор языка
     * @return строковое значение или null
     */
    public static String extractVariableValue(TSNode root, String content, int line,
                                               String symbolName, String langId) {
        TSNode varNode = findVariableDeclarationAtLine(root, line, symbolName, langId);
        if (varNode == null) return null;

        return extractValueFromDeclaration(varNode, content, langId);
    }

    /**
     * Находит узел объявления переменной на указанной строке.
     */
    private static TSNode findVariableDeclarationAtLine(TSNode node, int line, String symbolName, String langId) {
        if (node == null || node.isNull()) return null;

        String nodeType = node.getType();
        int nodeLine = node.getStartPoint().getRow();

        // Проверяем, является ли это объявлением нужной переменной
        if (nodeLine == line && isVariableDeclarationWithName(node, symbolName, langId)) {
            return node;
        }

        // Рекурсивно ищем в дочерних
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                int childStartLine = child.getStartPoint().getRow();
                int childEndLine = child.getEndPoint().getRow();

                if (line >= childStartLine && line <= childEndLine) {
                    TSNode found = findVariableDeclarationAtLine(child, line, symbolName, langId);
                    if (found != null) return found;
                }
            }
        }

        return null;
    }

    /**
     * Проверяет, является ли узел объявлением указанной переменной.
     */
    private static boolean isVariableDeclarationWithName(TSNode node, String symbolName, String langId) {
        String nodeType = node.getType();

        boolean isDecl = switch (langId) {
            case "java", "kotlin" -> nodeType.equals("local_variable_declaration") ||
                    nodeType.equals("field_declaration") ||
                    nodeType.equals("variable_declarator");
            case "python" -> nodeType.equals("assignment") ||
                    nodeType.equals("expression_statement");
            case "javascript", "typescript", "tsx", "jsx" -> nodeType.equals("variable_declaration") ||
                    nodeType.equals("lexical_declaration") ||
                    nodeType.equals("variable_declarator");
            case "go" -> nodeType.equals("short_var_declaration") ||
                    nodeType.equals("var_declaration") ||
                    nodeType.equals("const_declaration");
            case "c", "cpp" -> nodeType.equals("declaration") ||
                    nodeType.equals("init_declarator");
            default -> false;
        };

        return isDecl;
    }

    /**
     * Извлекает значение из узла объявления переменной.
     */
    private static String extractValueFromDeclaration(TSNode node, String content, String langId) {
        String nodeType = node.getType();

        return switch (langId) {
            case "java", "kotlin" -> extractJavaVariableValue(node, content);
            case "python" -> extractPythonVariableValue(node, content);
            case "javascript", "typescript", "tsx", "jsx" -> extractJsVariableValue(node, content);
            case "go" -> extractGoVariableValue(node, content);
            case "c", "cpp" -> extractCVariableValue(node, content);
            default -> null;
        };
    }

    private static String extractJavaVariableValue(TSNode node, String content) {
        // Для Java/Kotlin ищем variable_declarator или его эквивалент
        TSNode declarator = node;
        if (node.getType().equals("local_variable_declaration") ||
                node.getType().equals("field_declaration")) {
            declarator = findChildByType(node, "variable_declarator");
        }

        if (declarator == null) return null;

        // Ищем знак = и берём всё после него
        int childCount = declarator.getChildCount();
        boolean afterEquals = false;

        for (int i = 0; i < childCount; i++) {
            TSNode child = declarator.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            if (childType.equals("=")) {
                afterEquals = true;
                continue;
            }

            if (afterEquals) {
                // Это значение
                return getNodeText(child, content);
            }
        }

        return null;
    }

    private static String extractPythonVariableValue(TSNode node, String content) {
        // Python: assignment -> left = right
        // Правая часть обычно второй ребёнок после =
        int childCount = node.getChildCount();
        boolean afterEquals = false;

        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            if (childType.equals("=")) {
                afterEquals = true;
                continue;
            }

            if (afterEquals) {
                return getNodeText(child, content);
            }
        }

        return null;
    }

    private static String extractJsVariableValue(TSNode node, String content) {
        TSNode declarator = node;
        if (node.getType().equals("variable_declaration") ||
                node.getType().equals("lexical_declaration")) {
            declarator = findChildByType(node, "variable_declarator");
        }

        if (declarator == null) return null;

        // Ищем значение после идентификатора
        int childCount = declarator.getChildCount();
        boolean afterEquals = false;

        for (int i = 0; i < childCount; i++) {
            TSNode child = declarator.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            if (childType.equals("=")) {
                afterEquals = true;
                continue;
            }

            if (afterEquals) {
                return getNodeText(child, content);
            }
        }

        return null;
    }

    private static String extractGoVariableValue(TSNode node, String content) {
        String nodeType = node.getType();

        if (nodeType.equals("short_var_declaration")) {
            // a := value -> берём правую часть
            int childCount = node.getChildCount();
            boolean afterOp = false;

            for (int i = 0; i < childCount; i++) {
                TSNode child = node.getChild(i);
                if (child == null || child.isNull()) continue;

                if (child.getType().equals(":=")) {
                    afterOp = true;
                    continue;
                }

                if (afterOp && !child.getType().equals("expression_list")) {
                    return getNodeText(child, content);
                }
                if (afterOp && child.getType().equals("expression_list")) {
                    // Берём первый элемент
                    if (child.getChildCount() > 0) {
                        return getNodeText(child.getChild(0), content);
                    }
                }
            }
        }

        return null;
    }

    private static String extractCVariableValue(TSNode node, String content) {
        TSNode declarator = node;
        if (node.getType().equals("declaration")) {
            declarator = findChildByType(node, "init_declarator");
        }

        if (declarator == null) return null;

        // Ищем значение после =
        int childCount = declarator.getChildCount();
        boolean afterEquals = false;

        for (int i = 0; i < childCount; i++) {
            TSNode child = declarator.getChild(i);
            if (child == null || child.isNull()) continue;

            if (child.getType().equals("=")) {
                afterEquals = true;
                continue;
            }

            if (afterEquals) {
                return getNodeText(child, content);
            }
        }

        return null;
    }

    /**
     * Извлекает тело метода/функции для инлайнинга.
     * Для простых методов с одним return возвращает выражение.
     *
     * @param root корневой узел AST
     * @param content содержимое файла
     * @param startLine начальная строка метода (0-based)
     * @param endLine конечная строка метода (0-based)
     * @param langId идентификатор языка
     * @return тело метода или выражение return
     */
    public static String extractMethodBodyForInline(TSNode root, String content,
                                                     int startLine, int endLine, String langId) {
        TSNode methodNode = findMethodAtLine(root, startLine, langId);
        if (methodNode == null) return null;

        // Ищем тело метода
        TSNode body = findChildByType(methodNode, "block");
        if (body == null) body = findChildByType(methodNode, "function_body");
        if (body == null) body = findChildByType(methodNode, "statement_block");

        if (body == null) return null;

        // Если тело содержит только один return, извлекаем выражение
        String singleReturn = extractSingleReturnExpression(body, content, langId);
        if (singleReturn != null) {
            return singleReturn;
        }

        // Иначе возвращаем всё тело без скобок
        String bodyText = getNodeText(body, content);
        // Убираем внешние { }
        bodyText = bodyText.trim();
        if (bodyText.startsWith("{") && bodyText.endsWith("}")) {
            bodyText = bodyText.substring(1, bodyText.length() - 1).trim();
        }

        return bodyText;
    }

    /**
     * Находит метод/функцию на указанной строке.
     */
    private static TSNode findMethodAtLine(TSNode node, int line, String langId) {
        if (node == null || node.isNull()) return null;

        String nodeType = node.getType();
        int nodeLine = node.getStartPoint().getRow();

        // Проверяем, является ли это методом/функцией
        if (nodeLine == line && isMethodNode(nodeType)) {
            return node;
        }

        // Рекурсивно ищем
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                int childStartLine = child.getStartPoint().getRow();
                int childEndLine = child.getEndPoint().getRow();

                if (line >= childStartLine && line <= childEndLine) {
                    TSNode found = findMethodAtLine(child, line, langId);
                    if (found != null) return found;
                }
            }
        }

        return null;
    }

    private static boolean isMethodNode(String nodeType) {
        return nodeType.equals("method_declaration") ||
               nodeType.equals("function_declaration") ||
               nodeType.equals("function_definition") ||
               nodeType.equals("function_item") ||
               nodeType.equals("method_definition") ||
               nodeType.equals("arrow_function") ||
               nodeType.equals("function_expression");
    }

    /**
     * Извлекает выражение из единственного return statement.
     */
    private static String extractSingleReturnExpression(TSNode body, String content, String langId) {
        // Считаем statements в теле
        int statementCount = 0;
        TSNode returnNode = null;

        int childCount = body.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = body.getChild(i);
            if (child == null || child.isNull()) continue;

            String childType = child.getType();

            // Пропускаем { и }
            if (childType.equals("{") || childType.equals("}")) continue;

            statementCount++;

            if (childType.equals("return_statement") || childType.equals("return")) {
                returnNode = child;
            }
        }

        // Если только один statement и это return
        if (statementCount == 1 && returnNode != null) {
            // Извлекаем выражение из return
            int returnChildCount = returnNode.getChildCount();
            for (int i = 0; i < returnChildCount; i++) {
                TSNode child = returnNode.getChild(i);
                if (child == null || child.isNull()) continue;

                String childType = child.getType();
                // Пропускаем ключевое слово return и ;
                if (childType.equals("return") || childType.equals(";")) continue;

                return getNodeText(child, content);
            }
        }

        return null;
    }

    /**
     * Finds an attribute by name in a start tag.
     */
    public static TSNode findAttributeByName(TSNode startTag, String attrName, String content) {
        int childCount = startTag.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = startTag.getChild(i);
            if (child != null && child.getType().equals("attribute")) {
                TSNode nameNode = findChildByType(child, "attribute_name");
                if (nameNode != null && getNodeText(nameNode, content).equals(attrName)) {
                    return child;
                }
            }
        }
        return null;
    }

    /**
     * Gets the value of an attribute.
     */
    public static String getAttributeValue(TSNode attribute, String content) {
        TSNode valueNode = findChildByType(attribute, "attribute_value");
        if (valueNode == null) {
            valueNode = findChildByType(attribute, "quoted_attribute_value");
        }
        if (valueNode == null) return null;

        String value = getNodeText(valueNode, content);
        // Remove quotes if present
        if ((value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
