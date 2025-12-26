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

import org.treesitter.*;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.SymbolKind;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Извлекает символы из AST дерева с помощью tree-sitter.
 * Использует прямой обход дерева для максимальной совместимости.
 */
public final class SymbolExtractor {

    private static final SymbolExtractor INSTANCE = new SymbolExtractor();

    private SymbolExtractor() {}

    public static SymbolExtractor getInstance() {
        return INSTANCE;
    }

    /**
     * Извлекает все определения символов из файла.
     */
    public List<SymbolInfo> extractDefinitions(TSTree tree, Path path, String content, String langId) {
        List<SymbolInfo> symbols = new ArrayList<>();
        TSNode root = tree.getRootNode();

        extractSymbolsRecursive(root, path, content, langId, null, symbols);

        return symbols;
    }

    /**
     * Рекурсивно обходит AST и извлекает символы.
     */
    private void extractSymbolsRecursive(TSNode node, Path path, String content,
                                          String langId, String parentName,
                                          List<SymbolInfo> symbols) {
        String nodeType = node.getType();

        // Извлекаем символ если это определение
        Optional<SymbolInfo> symbol = extractSymbolFromNode(node, path, content, langId, parentName);
        symbol.ifPresent(symbols::add);

        // Определяем новое имя родителя для вложенных символов
        String newParentName = parentName;
        if (symbol.isPresent() && isContainerSymbol(symbol.get().kind())) {
            newParentName = symbol.get().name();
        }

        // Рекурсивно обходим дочерние узлы
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                extractSymbolsRecursive(child, path, content, langId, newParentName, symbols);
            }
        }
    }

    /**
     * Проверяет, является ли символ контейнером.
     */
    private boolean isContainerSymbol(SymbolKind kind) {
        return kind == SymbolKind.CLASS ||
                kind == SymbolKind.INTERFACE ||
                kind == SymbolKind.ENUM ||
                kind == SymbolKind.STRUCT ||
                kind == SymbolKind.TRAIT ||
                kind == SymbolKind.OBJECT ||
                kind == SymbolKind.MODULE ||
                kind == SymbolKind.NAMESPACE;
    }

    /**
     * Извлекает символ из узла AST.
     */
    private Optional<SymbolInfo> extractSymbolFromNode(TSNode node, Path path, String content,
                                                        String langId, String parentName) {
        String nodeType = node.getType();

        return switch (langId) {
            case "java" -> extractJavaSymbol(node, nodeType, path, content, parentName);
            case "kotlin" -> extractKotlinSymbol(node, nodeType, path, content, parentName);
            case "javascript", "tsx" -> extractJavaScriptSymbol(node, nodeType, path, content, parentName);
            case "typescript" -> extractTypeScriptSymbol(node, nodeType, path, content, parentName);
            case "python" -> extractPythonSymbol(node, nodeType, path, content, parentName);
            case "go" -> extractGoSymbol(node, nodeType, path, content, parentName);
            case "rust" -> extractRustSymbol(node, nodeType, path, content, parentName);
            case "c" -> extractCSymbol(node, nodeType, path, content, parentName);
            case "cpp" -> extractCppSymbol(node, nodeType, path, content, parentName);
            case "csharp" -> extractCSharpSymbol(node, nodeType, path, content, parentName);
            case "php" -> extractPhpSymbol(node, nodeType, path, content, parentName);
            case "html" -> extractHtmlSymbol(node, nodeType, path, content, parentName);
            default -> Optional.empty();
        };
    }

    // ===================== JAVA =====================

    private Optional<SymbolInfo> extractJavaSymbol(TSNode node, String nodeType, Path path,
                                                    String content, String parentName) {
        return switch (nodeType) {
            case "class_declaration" -> extractJavaClass(node, path, content, parentName, SymbolKind.CLASS);
            case "interface_declaration" -> extractJavaClass(node, path, content, parentName, SymbolKind.INTERFACE);
            case "enum_declaration" -> extractJavaClass(node, path, content, parentName, SymbolKind.ENUM);
            case "record_declaration" -> extractJavaClass(node, path, content, parentName, SymbolKind.CLASS);
            case "method_declaration" -> extractJavaMethod(node, path, content, parentName);
            case "constructor_declaration" -> extractJavaConstructor(node, path, content, parentName);
            case "field_declaration" -> extractJavaField(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractJavaClass(TSNode node, Path path, String content,
                                                   String parentName, SymbolKind kind) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, kind, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractJavaMethod(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);
        String signature = extractMethodSignature(node, content);

        TSNode returnType = findChildByType(node, "type_identifier");
        if (returnType == null) returnType = findChildByType(node, "void_type");
        if (returnType == null) returnType = findChildByType(node, "generic_type");
        String type = returnType != null ? getNodeText(returnType, content) : null;

        return Optional.of(new SymbolInfo(name, SymbolKind.METHOD, type, signature, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractJavaConstructor(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);
        String signature = extractMethodSignature(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.CONSTRUCTOR, null, signature, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractJavaField(TSNode node, Path path, String content, String parentName) {
        TSNode declarator = findChildByType(node, "variable_declarator");
        if (declarator == null) return Optional.empty();

        TSNode nameNode = findChildByType(declarator, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(nameNode, path);
        String type = extractJavaTypeFromFieldDeclaration(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.FIELD, type, null, null, location, parentName));
    }

    /**
     * Extracts the type from a Java field_declaration node.
     * Handles all Java type forms: primitives, classes, generics, arrays.
     */
    private String extractJavaTypeFromFieldDeclaration(TSNode node, String content) {
        // All possible type node types in tree-sitter-java
        String[] typeNodeTypes = {
            "type_identifier",       // String, MyClass
            "generic_type",          // List<String>, Map<K,V>
            "array_type",            // int[], String[][]
            "integral_type",         // int, long, short, byte, char
            "floating_point_type",   // float, double
            "boolean_type",          // boolean
            "scoped_type_identifier", // java.util.List
            "void_type"              // void (rare for fields but possible)
        };

        for (String typeName : typeNodeTypes) {
            TSNode typeNode = findChildByType(node, typeName);
            if (typeNode != null) {
                return getNodeText(typeNode, content);
            }
        }

        // Fallback: try to find any node before the variable_declarator that looks like a type
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                String childType = child.getType();
                // Skip modifiers, annotations, and the declarator itself
                if (!childType.equals("modifiers") &&
                    !childType.equals("variable_declarator") &&
                    !childType.equals(";") &&
                    !childType.startsWith("marker_annotation") &&
                    !childType.startsWith("annotation")) {
                    // This is likely the type node
                    return getNodeText(child, content);
                }
            }
        }

        return null;
    }

    // ===================== KOTLIN =====================

    private Optional<SymbolInfo> extractKotlinSymbol(TSNode node, String nodeType, Path path,
                                                      String content, String parentName) {
        return switch (nodeType) {
            case "class_declaration" -> extractKotlinClass(node, path, content, parentName);
            case "object_declaration" -> extractKotlinObject(node, path, content, parentName);
            case "function_declaration" -> extractKotlinFunction(node, path, content, parentName);
            case "property_declaration" -> extractKotlinProperty(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractKotlinClass(TSNode node, Path path, String content, String parentName) {
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

    private Optional<SymbolInfo> extractKotlinObject(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) nameNode = findChildByType(node, "simple_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.OBJECT, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractKotlinFunction(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "simple_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractKotlinProperty(TSNode node, Path path, String content, String parentName) {
        TSNode declarator = findChildByType(node, "variable_declaration");
        if (declarator == null) return Optional.empty();

        TSNode nameNode = findChildByType(declarator, "simple_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(nameNode, path);

        return Optional.of(new SymbolInfo(name, SymbolKind.PROPERTY, null, null, null, location, parentName));
    }

    // ===================== JAVASCRIPT/TSX =====================

    private Optional<SymbolInfo> extractJavaScriptSymbol(TSNode node, String nodeType, Path path,
                                                          String content, String parentName) {
        return switch (nodeType) {
            case "function_declaration" -> extractJsFunctionDeclaration(node, path, content, parentName);
            case "class_declaration" -> extractJsClassDeclaration(node, path, content, parentName);
            case "method_definition" -> extractJsMethodDefinition(node, path, content, parentName);
            case "lexical_declaration", "variable_declaration" ->
                    extractJsVariableDeclaration(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractJsFunctionDeclaration(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractJsClassDeclaration(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.CLASS, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractJsMethodDefinition(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "property_identifier");
        if (nameNode == null) nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        SymbolKind kind = name.equals("constructor") ? SymbolKind.CONSTRUCTOR : SymbolKind.METHOD;

        return Optional.of(new SymbolInfo(name, kind, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractJsVariableDeclaration(TSNode node, Path path, String content, String parentName) {
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

    // ===================== TYPESCRIPT =====================

    private Optional<SymbolInfo> extractTypeScriptSymbol(TSNode node, String nodeType, Path path,
                                                          String content, String parentName) {
        Optional<SymbolInfo> jsSymbol = extractJavaScriptSymbol(node, nodeType, path, content, parentName);
        if (jsSymbol.isPresent()) return jsSymbol;

        return switch (nodeType) {
            case "interface_declaration" -> extractTsInterfaceDeclaration(node, path, content, parentName);
            case "type_alias_declaration" -> extractTsTypeAlias(node, path, content, parentName);
            case "enum_declaration" -> extractTsEnumDeclaration(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractTsInterfaceDeclaration(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.INTERFACE, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractTsTypeAlias(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);

        return Optional.of(new SymbolInfo(name, SymbolKind.TYPE_PARAMETER, null, null, null, location, parentName));
    }

    private Optional<SymbolInfo> extractTsEnumDeclaration(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.ENUM, null, null, doc, location, parentName));
    }

    // ===================== PYTHON =====================

    private Optional<SymbolInfo> extractPythonSymbol(TSNode node, String nodeType, Path path,
                                                      String content, String parentName) {
        return switch (nodeType) {
            case "function_definition" -> extractPythonFunction(node, path, content, parentName);
            case "class_definition" -> extractPythonClass(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractPythonFunction(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPythonDocstring(node, content);

        SymbolKind kind = name.equals("__init__") ? SymbolKind.CONSTRUCTOR :
                (parentName != null ? SymbolKind.METHOD : SymbolKind.FUNCTION);

        return Optional.of(new SymbolInfo(name, kind, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractPythonClass(TSNode node, Path path, String content, String parentName) {
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

    // ===================== GO =====================

    private Optional<SymbolInfo> extractGoSymbol(TSNode node, String nodeType, Path path,
                                                  String content, String parentName) {
        return switch (nodeType) {
            case "function_declaration" -> extractGoFunction(node, path, content, parentName);
            case "method_declaration" -> extractGoMethod(node, path, content, parentName);
            case "type_declaration" -> extractGoTypeDeclaration(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractGoFunction(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractGoMethod(TSNode node, Path path, String content, String parentName) {
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

    private Optional<SymbolInfo> extractGoTypeDeclaration(TSNode node, Path path, String content, String parentName) {
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

    // ===================== RUST =====================

    private Optional<SymbolInfo> extractRustSymbol(TSNode node, String nodeType, Path path,
                                                    String content, String parentName) {
        return switch (nodeType) {
            case "function_item" -> extractRustFunction(node, path, content, parentName);
            case "struct_item" -> extractRustStruct(node, path, content, parentName);
            case "enum_item" -> extractRustEnum(node, path, content, parentName);
            case "trait_item" -> extractRustTrait(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractRustFunction(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractRustStruct(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.STRUCT, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractRustEnum(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.ENUM, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractRustTrait(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.TRAIT, null, null, doc, location, parentName));
    }

    // ===================== C =====================

    private Optional<SymbolInfo> extractCSymbol(TSNode node, String nodeType, Path path,
                                                  String content, String parentName) {
        return switch (nodeType) {
            case "function_definition" -> extractCFunction(node, path, content, parentName);
            case "declaration" -> extractCDeclaration(node, path, content, parentName);
            case "struct_specifier" -> extractCStruct(node, path, content, parentName);
            case "enum_specifier" -> extractCEnum(node, path, content, parentName);
            case "type_definition" -> extractCTypedef(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractCFunction(TSNode node, Path path, String content, String parentName) {
        TSNode declarator = findChildByType(node, "function_declarator");
        if (declarator == null) return Optional.empty();

        TSNode nameNode = findChildByType(declarator, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);
        String signature = extractMethodSignature(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, null, signature, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractCDeclaration(TSNode node, Path path, String content, String parentName) {
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

    private Optional<SymbolInfo> extractCStruct(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.STRUCT, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractCEnum(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.ENUM, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractCTypedef(TSNode node, Path path, String content, String parentName) {
        TSNode declarator = findChildByType(node, "type_declarator");
        if (declarator == null) return Optional.empty();

        TSNode nameNode = findChildByType(declarator, "type_identifier");
        if (nameNode == null) nameNode = findChildByType(declarator, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);

        return Optional.of(new SymbolInfo(name, SymbolKind.TYPE_PARAMETER, null, null, null, location, parentName));
    }

    // ===================== C++ =====================

    private Optional<SymbolInfo> extractCppSymbol(TSNode node, String nodeType, Path path,
                                                    String content, String parentName) {
        // C++ extends C, so check C symbols first
        Optional<SymbolInfo> cSymbol = extractCSymbol(node, nodeType, path, content, parentName);
        if (cSymbol.isPresent()) return cSymbol;

        return switch (nodeType) {
            case "class_specifier" -> extractCppClass(node, path, content, parentName);
            case "function_definition" -> extractCppFunction(node, path, content, parentName);
            case "field_declaration" -> extractCppField(node, path, content, parentName);
            case "namespace_definition" -> extractCppNamespace(node, path, content, parentName);
            case "template_declaration" -> extractCppTemplate(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractCppClass(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "type_identifier");
        if (nameNode == null) nameNode = findChildByType(node, "name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        // Determine if it's a struct or class
        String fullText = getNodeText(node, content);
        SymbolKind kind = fullText.trim().startsWith("struct") ? SymbolKind.STRUCT : SymbolKind.CLASS;

        return Optional.of(new SymbolInfo(name, kind, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractCppFunction(TSNode node, Path path, String content, String parentName) {
        TSNode declarator = findChildByType(node, "function_declarator");
        if (declarator == null) return Optional.empty();

        // Check for qualified identifier (method with class prefix)
        TSNode qualId = findChildByType(declarator, "qualified_identifier");
        TSNode nameNode;
        if (qualId != null) {
            nameNode = findChildByType(qualId, "identifier");
            if (nameNode == null) nameNode = findChildByType(qualId, "destructor_name");
        } else {
            nameNode = findChildByType(declarator, "identifier");
            if (nameNode == null) nameNode = findChildByType(declarator, "destructor_name");
            if (nameNode == null) nameNode = findChildByType(declarator, "operator_name");
        }

        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);
        String signature = extractMethodSignature(node, content);

        // Detect constructor/destructor
        SymbolKind kind = SymbolKind.FUNCTION;
        if (name.startsWith("~")) {
            kind = SymbolKind.METHOD; // Destructor
        } else if (parentName != null && name.equals(parentName)) {
            kind = SymbolKind.CONSTRUCTOR;
        } else if (parentName != null) {
            kind = SymbolKind.METHOD;
        }

        return Optional.of(new SymbolInfo(name, kind, null, signature, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractCppField(TSNode node, Path path, String content, String parentName) {
        if (parentName == null) return Optional.empty(); // Only extract fields inside classes

        TSNode declarator = findChildByType(node, "field_declarator");
        if (declarator == null) declarator = findChildByType(node, "declarator");
        if (declarator == null) return Optional.empty();

        TSNode nameNode = findChildByType(declarator, "field_identifier");
        if (nameNode == null) nameNode = findChildByType(declarator, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(nameNode, path);

        return Optional.of(new SymbolInfo(name, SymbolKind.FIELD, null, null, null, location, parentName));
    }

    private Optional<SymbolInfo> extractCppNamespace(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) nameNode = findChildByType(node, "namespace_identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.NAMESPACE, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractCppTemplate(TSNode node, Path path, String content, String parentName) {
        // Extract the templated declaration
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (child != null) {
                String childType = child.getType();
                if (childType.equals("class_specifier") || childType.equals("function_definition")) {
                    return extractCppSymbol(child, childType, path, content, parentName);
                }
            }
        }
        return Optional.empty();
    }

    // ===================== C# =====================

    private Optional<SymbolInfo> extractCSharpSymbol(TSNode node, String nodeType, Path path,
                                                       String content, String parentName) {
        return switch (nodeType) {
            case "class_declaration" -> extractCSharpClass(node, path, content, parentName, SymbolKind.CLASS);
            case "interface_declaration" -> extractCSharpClass(node, path, content, parentName, SymbolKind.INTERFACE);
            case "struct_declaration" -> extractCSharpClass(node, path, content, parentName, SymbolKind.STRUCT);
            case "enum_declaration" -> extractCSharpEnum(node, path, content, parentName);
            case "record_declaration" -> extractCSharpClass(node, path, content, parentName, SymbolKind.CLASS);
            case "method_declaration" -> extractCSharpMethod(node, path, content, parentName);
            case "constructor_declaration" -> extractCSharpConstructor(node, path, content, parentName);
            case "property_declaration" -> extractCSharpProperty(node, path, content, parentName);
            case "field_declaration" -> extractCSharpField(node, path, content, parentName);
            case "namespace_declaration" -> extractCSharpNamespace(node, path, content, parentName);
            case "delegate_declaration" -> extractCSharpDelegate(node, path, content, parentName);
            case "event_declaration" -> extractCSharpEvent(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractCSharpClass(TSNode node, Path path, String content,
                                                      String parentName, SymbolKind kind) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, kind, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractCSharpEnum(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.ENUM, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractCSharpMethod(TSNode node, Path path, String content, String parentName) {
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

    private Optional<SymbolInfo> extractCSharpConstructor(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);
        String signature = extractMethodSignature(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.CONSTRUCTOR, null, signature, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractCSharpProperty(TSNode node, Path path, String content, String parentName) {
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

    private Optional<SymbolInfo> extractCSharpField(TSNode node, Path path, String content, String parentName) {
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

    private Optional<SymbolInfo> extractCSharpNamespace(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) nameNode = findChildByType(node, "qualified_name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.NAMESPACE, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractCSharpDelegate(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractCSharpEvent(TSNode node, Path path, String content, String parentName) {
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

    // ===================== PHP =====================

    private Optional<SymbolInfo> extractPhpSymbol(TSNode node, String nodeType, Path path,
                                                    String content, String parentName) {
        return switch (nodeType) {
            case "class_declaration" -> extractPhpClass(node, path, content, parentName);
            case "interface_declaration" -> extractPhpInterface(node, path, content, parentName);
            case "trait_declaration" -> extractPhpTrait(node, path, content, parentName);
            case "enum_declaration" -> extractPhpEnum(node, path, content, parentName);
            case "function_definition" -> extractPhpFunction(node, path, content, parentName);
            case "method_declaration" -> extractPhpMethod(node, path, content, parentName);
            case "property_declaration" -> extractPhpProperty(node, path, content, parentName);
            case "const_declaration" -> extractPhpConst(node, path, content, parentName);
            case "namespace_definition" -> extractPhpNamespace(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractPhpClass(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.CLASS, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractPhpInterface(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.INTERFACE, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractPhpTrait(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.TRAIT, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractPhpEnum(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.ENUM, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractPhpFunction(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.FUNCTION, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractPhpMethod(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPrecedingComment(node, content);

        SymbolKind kind = name.equals("__construct") ? SymbolKind.CONSTRUCTOR : SymbolKind.METHOD;

        return Optional.of(new SymbolInfo(name, kind, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractPhpProperty(TSNode node, Path path, String content, String parentName) {
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

    private Optional<SymbolInfo> extractPhpConst(TSNode node, Path path, String content, String parentName) {
        TSNode constElement = findChildByType(node, "const_element");
        if (constElement == null) return Optional.empty();

        TSNode nameNode = findChildByType(constElement, "name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(nameNode, path);

        return Optional.of(new SymbolInfo(name, SymbolKind.CONSTANT, null, null, null, location, parentName));
    }

    private Optional<SymbolInfo> extractPhpNamespace(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "namespace_name");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);

        return Optional.of(new SymbolInfo(name, SymbolKind.NAMESPACE, null, null, null, location, parentName));
    }

    // ===================== HTML =====================

    private Optional<SymbolInfo> extractHtmlSymbol(TSNode node, String nodeType, Path path,
                                                     String content, String parentName) {
        return switch (nodeType) {
            case "element" -> extractHtmlElement(node, path, content, parentName);
            case "script_element" -> extractHtmlScript(node, path, content, parentName);
            case "style_element" -> extractHtmlStyle(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractHtmlElement(TSNode node, Path path, String content, String parentName) {
        TSNode startTag = findChildByType(node, "start_tag");
        if (startTag == null) return Optional.empty();

        TSNode tagName = findChildByType(startTag, "tag_name");
        if (tagName == null) return Optional.empty();

        String name = getNodeText(tagName, content);

        // Only extract significant elements with id attribute
        TSNode idAttr = findAttributeByName(startTag, "id", content);
        if (idAttr == null) {
            // Also check for named elements like form, a with name attribute
            if (!name.equals("form") && !name.equals("a") && !name.equals("iframe") &&
                !name.equals("img") && !name.equals("input") && !name.equals("map") &&
                !name.equals("meta") && !name.equals("object") && !name.equals("param") &&
                !name.equals("select") && !name.equals("textarea")) {
                return Optional.empty();
            }
            idAttr = findAttributeByName(startTag, "name", content);
            if (idAttr == null) return Optional.empty();
        }

        String idValue = getAttributeValue(idAttr, content);
        if (idValue == null || idValue.isEmpty()) return Optional.empty();

        Location location = nodeToLocation(node, path);

        return Optional.of(new SymbolInfo(idValue, SymbolKind.VARIABLE, name, null, null, location, parentName));
    }

    private Optional<SymbolInfo> extractHtmlScript(TSNode node, Path path, String content, String parentName) {
        TSNode startTag = findChildByType(node, "start_tag");
        if (startTag == null) return Optional.empty();

        TSNode srcAttr = findAttributeByName(startTag, "src", content);
        if (srcAttr != null) {
            String src = getAttributeValue(srcAttr, content);
            if (src != null && !src.isEmpty()) {
                Location location = nodeToLocation(node, path);
                return Optional.of(new SymbolInfo(src, SymbolKind.IMPORT, "script", null, null, location, parentName));
            }
        }
        return Optional.empty();
    }

    private Optional<SymbolInfo> extractHtmlStyle(TSNode node, Path path, String content, String parentName) {
        // Style elements are less useful for navigation, skip them
        return Optional.empty();
    }

    /**
     * Finds an attribute by name in a start tag.
     */
    private TSNode findAttributeByName(TSNode startTag, String attrName, String content) {
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
    private String getAttributeValue(TSNode attribute, String content) {
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

    // ===================== УТИЛИТЫ =====================

    /**
     * Находит символ в указанной позиции.
     */
    public Optional<SymbolInfo> symbolAtPosition(TSTree tree, Path path, String content,
                                                  String langId, int line, int column) {
        List<SymbolInfo> allSymbols = extractDefinitions(tree, path, content, langId);

        return allSymbols.stream()
                .filter(s -> s.location().contains(line, column))
                .min((a, b) -> Integer.compare(a.location().lineSpan(), b.location().lineSpan()));
    }

    /**
     * Находит все ссылки на символ в файле.
     */
    public List<Location> findReferences(TSTree tree, Path path, String content,
                                          String langId, String symbolName) {
        List<Location> references = new ArrayList<>();
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        findReferencesRecursive(tree.getRootNode(), path, contentBytes, symbolName, references);
        return references;
    }

    private void findReferencesRecursive(TSNode node, Path path, byte[] contentBytes,
                                          String symbolName, List<Location> references) {
        String nodeType = node.getType();

        if (nodeType.equals("identifier") ||
                nodeType.equals("simple_identifier") ||
                nodeType.equals("type_identifier") ||
                nodeType.equals("property_identifier") ||
                nodeType.equals("field_identifier")) {

            String text = getNodeTextFromBytes(node, contentBytes);
            if (text.equals(symbolName)) {
                references.add(nodeToLocation(node, path));
            }
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                findReferencesRecursive(child, path, contentBytes, symbolName, references);
            }
        }
    }

    /**
     * Находит дочерний узел указанного типа.
     */
    private TSNode findChildByType(TSNode parent, String type) {
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
    private String getNodeText(TSNode node, String content) {
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        return getNodeTextFromBytes(node, contentBytes);
    }

    /**
     * Извлекает текст узла из байтового массива (корректно для UTF-8).
     */
    private String getNodeTextFromBytes(TSNode node, byte[] contentBytes) {
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
    private Location nodeToLocation(TSNode node, Path path) {
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
     * Извлекает комментарий, предшествующий узлу.
     */
    private String extractPrecedingComment(TSNode node, String content) {
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
    private String cleanupComment(String comment) {
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
    private String extractMethodSignature(TSNode node, String content) {
        String fullText = getNodeText(node, content);
        int braceIdx = fullText.indexOf('{');
        int semiIdx = fullText.indexOf(';');

        int endIdx = fullText.length();
        if (braceIdx > 0) endIdx = braceIdx;
        if (semiIdx > 0 && semiIdx < endIdx) endIdx = semiIdx;

        String signature = fullText.substring(0, endIdx).trim();
        return signature.replaceAll("\\s+", " ");
    }
}
