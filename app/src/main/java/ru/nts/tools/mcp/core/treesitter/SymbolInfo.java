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

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Информация о символе (класс, метод, переменная и т.д.).
 * Используется для представления результатов навигации по коду.
 *
 * @param name имя символа
 * @param kind тип символа (class, method, field и т.д.)
 * @param type тип данных (для переменных/полей/параметров), может быть null
 * @param signature полная сигнатура (для методов/функций), может быть null
 * @param parameters структурированный список параметров (для методов/функций), может быть null
 * @param documentation документация (Javadoc/docstring), может быть null
 * @param location позиция символа в файле
 * @param parentName имя родительского символа (например, класс для метода), может быть null
 */
public record SymbolInfo(
        String name,
        SymbolKind kind,
        String type,
        String signature,
        List<ParameterInfo> parameters,
        String documentation,
        Location location,
        String parentName
) {

    /**
     * Создает SymbolInfo с минимальным набором данных.
     */
    public SymbolInfo(String name, SymbolKind kind, Location location) {
        this(name, kind, null, null, null, null, location, null);
    }

    /**
     * Создает SymbolInfo с именем родителя.
     */
    public SymbolInfo(String name, SymbolKind kind, Location location, String parentName) {
        this(name, kind, null, null, null, null, location, parentName);
    }

    /**
     * Создает SymbolInfo с типом, сигнатурой и документацией (без parameters для обратной совместимости).
     */
    public SymbolInfo(String name, SymbolKind kind, String type, String signature,
                      String documentation, Location location, String parentName) {
        this(name, kind, type, signature, null, documentation, location, parentName);
    }

    /**
     * Возвращает полное квалифицированное имя символа.
     * Например: "ClassName.methodName" или просто "methodName" если родитель не указан.
     */
    public String qualifiedName() {
        if (parentName != null && !parentName.isEmpty()) {
            return parentName + "." + name;
        }
        return name;
    }

    /**
     * Создает копию с обновленной документацией.
     */
    public SymbolInfo withDocumentation(String doc) {
        return new SymbolInfo(name, kind, type, signature, parameters, doc, location, parentName);
    }

    /**
     * Создает копию с обновленной сигнатурой.
     */
    public SymbolInfo withSignature(String sig) {
        return new SymbolInfo(name, kind, type, sig, parameters, documentation, location, parentName);
    }

    /**
     * Создает копию с обновленным типом.
     */
    public SymbolInfo withType(String newType) {
        return new SymbolInfo(name, kind, newType, signature, parameters, documentation, location, parentName);
    }

    /**
     * Создает копию с обновленными параметрами.
     */
    public SymbolInfo withParameters(List<ParameterInfo> params) {
        return new SymbolInfo(name, kind, type, signature, params, documentation, location, parentName);
    }

    /**
     * Возвращает нормализованную сигнатуру для сравнения перегруженных методов.
     * Формат: "(Type1, Type2)" - только типы параметров без имён.
     */
    public String normalizedParameterSignature() {
        if (parameters == null || parameters.isEmpty()) {
            return "()";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) sb.append(", ");
            String type = parameters.get(i).type();
            // Убираем все generics (включая вложенные) для упрощённого сравнения
            type = removeGenerics(type);
            // Убираем квалификацию пакетов
            type = type.replaceAll("\\b[a-z][\\w.]*\\.([A-Z])", "$1");
            sb.append(type.trim());
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * Удаляет generics из типа, включая вложенные.
     */
    private static String removeGenerics(String type) {
        if (type == null || !type.contains("<")) return type;
        StringBuilder result = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < type.length(); i++) {
            char c = type.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (depth == 0) {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * Проверяет соответствие параметров указанной сигнатуре.
     *
     * @param signaturePattern сигнатура в формате "(Type1, Type2)" или "()"
     * @return true если сигнатура совпадает
     */
    public boolean matchesParameterSignature(String signaturePattern) {
        if (signaturePattern == null || signaturePattern.isEmpty()) {
            return true; // Без фильтра - всегда совпадает
        }
        String normalized = normalizedParameterSignature();
        String patternNormalized = signaturePattern.trim()
                .replaceAll("\\s*\\(\\s*", "(")
                .replaceAll("\\s*\\)\\s*", ")")
                .replaceAll("\\s*,\\s*", ", ");
        // Удаляем generics из паттерна
        patternNormalized = removeGenerics(patternNormalized);
        // Убираем квалификацию пакетов
        patternNormalized = patternNormalized.replaceAll("\\b[a-z][\\w.]*\\.([A-Z])", "$1");
        return normalized.equals(patternNormalized);
    }

    /**
     * Типы символов для LSP-подобной навигации.
     */
    public enum SymbolKind {
        // Структурные типы
        CLASS("class"),
        INTERFACE("interface"),
        ENUM("enum"),
        STRUCT("struct"),
        TRAIT("trait"),
        OBJECT("object"),

        // Методы и функции
        METHOD("method"),
        FUNCTION("function"),
        CONSTRUCTOR("constructor"),

        // Данные
        FIELD("field"),
        PROPERTY("property"),
        VARIABLE("variable"),
        PARAMETER("parameter"),
        CONSTANT("constant"),

        // Модули
        IMPORT("import"),
        PACKAGE("package"),
        MODULE("module"),
        NAMESPACE("namespace"),

        // Прочее
        TYPE_PARAMETER("type_parameter"),
        ANNOTATION("annotation"),
        REFERENCE("reference"),
        EVENT("event"),
        UNKNOWN("unknown");

        private final String displayName;

        SymbolKind(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName.toUpperCase();
        }
    }

    /**
     * Позиция символа в файле.
     * Строки и колонки 1-based (как в большинстве редакторов).
     *
     * @param path путь к файлу
     * @param startLine начальная строка (1-based)
     * @param startColumn начальная колонка (1-based)
     * @param endLine конечная строка (1-based)
     * @param endColumn конечная колонка (1-based)
     */
    public record Location(
            Path path,
            int startLine,
            int startColumn,
            int endLine,
            int endColumn
    ) {
        /**
         * Создает Location для одной строки.
         */
        public static Location singleLine(Path path, int line, int startColumn, int endColumn) {
            return new Location(path, line, startColumn, line, endColumn);
        }

        /**
         * Создает Location для точки (без диапазона).
         */
        public static Location point(Path path, int line, int column) {
            return new Location(path, line, column, line, column);
        }

        /**
         * Проверяет, находится ли указанная позиция внутри этой локации.
         */
        public boolean contains(int line, int column) {
            if (line < startLine || line > endLine) {
                return false;
            }
            if (line == startLine && column < startColumn) {
                return false;
            }
            if (line == endLine && column > endColumn) {
                return false;
            }
            return true;
        }

        /**
         * Возвращает диапазон строк (для LAT интеграции).
         */
        public int lineSpan() {
            return endLine - startLine + 1;
        }

        /**
         * Форматирует позицию для вывода.
         */
        public String format() {
            if (startLine == endLine) {
                return String.format("%s:%d:%d-%d", path.getFileName(), startLine, startColumn, endColumn);
            }
            return String.format("%s:%d:%d-%d:%d", path.getFileName(), startLine, startColumn, endLine, endColumn);
        }

        /**
         * Краткий формат: только файл и строка.
         */
        public String formatShort() {
            return String.format("%s:%d", path.getFileName(), startLine);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Location location = (Location) o;
            return startLine == location.startLine &&
                    startColumn == location.startColumn &&
                    endLine == location.endLine &&
                    endColumn == location.endColumn &&
                    Objects.equals(path.toAbsolutePath().normalize(),
                            location.path.toAbsolutePath().normalize());
        }

        @Override
        public int hashCode() {
            return Objects.hash(path.toAbsolutePath().normalize(),
                    startLine, startColumn, endLine, endColumn);
        }
    }

    /**
     * Информация о параметре метода/функции.
     * Извлекается из AST tree-sitter для точного сравнения сигнатур.
     *
     * @param name имя параметра
     * @param type тип параметра (полный, включая generics)
     * @param isVarargs true если это varargs параметр (String... args)
     */
    public record ParameterInfo(
            String name,
            String type,
            boolean isVarargs
    ) {
        /**
         * Создает ParameterInfo для обычного параметра.
         */
        public ParameterInfo(String name, String type) {
            this(name, type, false);
        }

        /**
         * Возвращает нормализованный тип для сравнения.
         * Убирает generics и квалификацию пакетов.
         */
        public String normalizedType() {
            if (type == null) return "";
            return type
                    .replaceAll("<[^>]*>", "")
                    .replaceAll("\\b[a-z][\\w.]*\\.([A-Z])", "$1")
                    .trim();
        }

        @Override
        public String toString() {
            return type + " " + name + (isVarargs ? " (varargs)" : "");
        }
    }
}
