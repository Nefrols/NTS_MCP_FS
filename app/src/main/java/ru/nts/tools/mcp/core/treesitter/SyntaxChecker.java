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
import org.treesitter.TSTree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Проверка синтаксиса файлов через tree-sitter AST.
 * Ищет ERROR и MISSING узлы в дереве разбора.
 */
public final class SyntaxChecker {

    private static final int MAX_ERRORS = 5;

    private SyntaxChecker() {}

    public record SyntaxError(int line, int column, String message, String context) {}

    public record SyntaxCheckResult(List<SyntaxError> errors) {
        public boolean hasErrors() { return !errors.isEmpty(); }
        public int errorCount() { return errors.size(); }
    }

    private static final SyntaxCheckResult EMPTY = new SyntaxCheckResult(List.of());

    /**
     * Проверяет синтаксис файла на диске.
     *
     * @param path путь к файлу
     * @return результат проверки (пустой если язык не поддерживается)
     */
    public static SyntaxCheckResult check(Path path) {
        Optional<String> langId = LanguageDetector.detect(path);
        if (langId.isEmpty()) {
            return EMPTY;
        }

        try {
            String content = Files.readString(path);
            return checkContent(path, content);
        } catch (IOException e) {
            return EMPTY;
        }
    }

    /**
     * Проверяет синтаксис переданного контента.
     * Используется для batch-операций, когда контент ещё не записан на диск.
     *
     * @param path путь к файлу (для определения языка)
     * @param content содержимое файла
     * @return результат проверки
     */
    public static SyntaxCheckResult checkContent(Path path, String content) {
        Optional<String> langId = LanguageDetector.detect(path);
        if (langId.isEmpty()) {
            return EMPTY;
        }

        try {
            TreeSitterManager tsm = TreeSitterManager.getInstance();
            TSTree tree = tsm.parse(content, langId.get());
            TSNode root = tree.getRootNode();

            String[] lines = content.split("\n", -1);
            List<SyntaxError> errors = new ArrayList<>();
            collectErrors(root, lines, errors);

            return new SyntaxCheckResult(List.copyOf(errors));
        } catch (Exception e) {
            // Если парсер упал — не блокируем работу
            return EMPTY;
        }
    }

    private static void collectErrors(TSNode node, String[] lines, List<SyntaxError> errors) {
        if (errors.size() >= MAX_ERRORS) return;

        if (node.getType().equals("ERROR") || node.isMissing()) {
            int line = node.getStartPoint().getRow() + 1; // tree-sitter: 0-based -> 1-based
            int column = node.getStartPoint().getColumn() + 1;

            String message;
            if (node.isMissing()) {
                message = "Missing expected syntax: " + node.getType();
            } else {
                // ERROR-узел: показываем тип родителя для контекста
                TSNode parent = node.getParent();
                String parentType = (parent != null && !parent.isNull()) ? parent.getType() : "unknown";
                message = "Syntax error in " + parentType;
            }

            // Контекст: строка кода
            String context = (line - 1 < lines.length) ? lines[line - 1].trim() : "";
            if (context.length() > 80) {
                context = context.substring(0, 80) + "...";
            }

            errors.add(new SyntaxError(line, column, message, context));
            return; // Не рекурсим в ERROR-узлы — уже нашли ошибку
        }

        // Рекурсивный обход дочерних узлов
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount && errors.size() < MAX_ERRORS; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                collectErrors(child, lines, errors);
            }
        }
    }
}
