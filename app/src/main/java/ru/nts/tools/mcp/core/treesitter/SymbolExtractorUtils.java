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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

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
