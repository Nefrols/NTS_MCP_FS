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
import java.util.Map;
import java.util.Optional;

/**
 * Определяет язык программирования по расширению файла или содержимому.
 * Поддерживаемые языки: java, kotlin, javascript, typescript, python, go, rust, c, cpp, csharp, php, html.
 */
public final class LanguageDetector {

    private LanguageDetector() {}

    /**
     * Отображение расширений файлов на идентификаторы языков.
     */
    private static final Map<String, String> EXTENSION_MAP = Map.ofEntries(
            // Java
            Map.entry("java", "java"),

            // Kotlin
            Map.entry("kt", "kotlin"),
            Map.entry("kts", "kotlin"),

            // JavaScript
            Map.entry("js", "javascript"),
            Map.entry("mjs", "javascript"),
            Map.entry("cjs", "javascript"),
            Map.entry("jsx", "javascript"),

            // TypeScript
            Map.entry("ts", "typescript"),
            Map.entry("tsx", "tsx"),
            Map.entry("mts", "typescript"),
            Map.entry("cts", "typescript"),

            // Python
            Map.entry("py", "python"),
            Map.entry("pyi", "python"),
            Map.entry("pyw", "python"),

            // Go
            Map.entry("go", "go"),

            // Rust
            Map.entry("rs", "rust"),

            // C
            Map.entry("c", "c"),
            Map.entry("h", "c"),

            // C++
            Map.entry("cpp", "cpp"),
            Map.entry("cc", "cpp"),
            Map.entry("cxx", "cpp"),
            Map.entry("c++", "cpp"),
            Map.entry("hpp", "cpp"),
            Map.entry("hh", "cpp"),
            Map.entry("hxx", "cpp"),
            Map.entry("h++", "cpp"),
            Map.entry("ipp", "cpp"),

            // C#
            Map.entry("cs", "csharp"),

            // PHP
            Map.entry("php", "php"),
            Map.entry("phtml", "php"),
            Map.entry("php3", "php"),
            Map.entry("php4", "php"),
            Map.entry("php5", "php"),
            Map.entry("php7", "php"),
            Map.entry("phps", "php"),
            Map.entry("inc", "php"),

            // HTML
            Map.entry("html", "html"),
            Map.entry("htm", "html"),
            Map.entry("xhtml", "html"),
            Map.entry("shtml", "html")
    );

    /**
     * Список поддерживаемых языков.
     */
    private static final List<String> SUPPORTED_LANGUAGES = List.of(
            "java", "kotlin", "javascript", "typescript", "tsx", "python", "go", "rust",
            "c", "cpp", "csharp", "php", "html"
    );

    /**
     * Определяет язык по пути к файлу.
     *
     * @param path путь к файлу
     * @return идентификатор языка или empty если язык не поддерживается
     */
    public static Optional<String> detect(Path path) {
        if (path == null) {
            return Optional.empty();
        }

        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');

        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return Optional.empty();
        }

        String extension = fileName.substring(dotIndex + 1).toLowerCase();
        return Optional.ofNullable(EXTENSION_MAP.get(extension));
    }

    /**
     * Определяет язык по пути к файлу и содержимому (для shebang).
     *
     * @param path путь к файлу
     * @param content содержимое файла (первые строки)
     * @return идентификатор языка или empty если язык не поддерживается
     */
    public static Optional<String> detect(Path path, String content) {
        // Сначала пробуем по расширению
        Optional<String> byExtension = detect(path);
        if (byExtension.isPresent()) {
            return byExtension;
        }

        // Если расширения нет, пробуем по shebang
        if (content != null && content.startsWith("#!")) {
            String firstLine = content.lines().findFirst().orElse("");

            if (firstLine.contains("python")) {
                return Optional.of("python");
            }
            if (firstLine.contains("node") || firstLine.contains("deno") || firstLine.contains("bun")) {
                return Optional.of("javascript");
            }
        }

        return Optional.empty();
    }

    /**
     * Проверяет, поддерживается ли указанный язык.
     *
     * @param langId идентификатор языка
     * @return true если язык поддерживается
     */
    public static boolean isSupported(String langId) {
        return langId != null && SUPPORTED_LANGUAGES.contains(langId.toLowerCase());
    }

    /**
     * Возвращает список всех поддерживаемых языков.
     *
     * @return неизменяемый список идентификаторов языков
     */
    public static List<String> getSupportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }

    /**
     * Возвращает расширение файла для указанного языка.
     *
     * @param langId идентификатор языка
     * @return основное расширение файла или empty
     */
    public static Optional<String> getFileExtension(String langId) {
        return switch (langId) {
            case "java" -> Optional.of("java");
            case "kotlin" -> Optional.of("kt");
            case "javascript" -> Optional.of("js");
            case "typescript" -> Optional.of("ts");
            case "tsx" -> Optional.of("tsx");
            case "python" -> Optional.of("py");
            case "go" -> Optional.of("go");
            case "rust" -> Optional.of("rs");
            case "c" -> Optional.of("c");
            case "cpp" -> Optional.of("cpp");
            case "csharp" -> Optional.of("cs");
            case "php" -> Optional.of("php");
            case "html" -> Optional.of("html");
            default -> Optional.empty();
        };
    }

    /**
     * Возвращает glob паттерн для поиска файлов указанного языка.
     *
     * @param langId идентификатор языка
     * @return glob паттерн (например "**\/*.java")
     */
    public static String getGlobPattern(String langId) {
        return switch (langId) {
            case "java" -> "**/*.java";
            case "kotlin" -> "**/*.{kt,kts}";
            case "javascript" -> "**/*.{js,mjs,cjs,jsx}";
            case "typescript" -> "**/*.{ts,mts,cts}";
            case "tsx" -> "**/*.tsx";
            case "python" -> "**/*.{py,pyi,pyw}";
            case "go" -> "**/*.go";
            case "rust" -> "**/*.rs";
            case "c" -> "**/*.{c,h}";
            case "cpp" -> "**/*.{cpp,cc,cxx,c++,hpp,hh,hxx,h++,ipp}";
            case "csharp" -> "**/*.cs";
            case "php" -> "**/*.{php,phtml,php3,php4,php5,php7,phps,inc}";
            case "html" -> "**/*.{html,htm,xhtml,shtml}";
            default -> "**/*";
        };
    }
}
