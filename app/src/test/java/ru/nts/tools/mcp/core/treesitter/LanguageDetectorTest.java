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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LanguageDetectorTest {

    @ParameterizedTest
    @CsvSource({
            "Main.java, java",
            "App.kt, kotlin",
            "build.gradle.kts, kotlin",
            "index.js, javascript",
            "module.mjs, javascript",
            "app.jsx, javascript",
            "component.ts, typescript",
            "Component.tsx, tsx",
            "script.py, python",
            "types.pyi, python",
            "main.go, go",
            "lib.rs, rust",
            // C
            "main.c, c",
            "header.h, c",
            // C++
            "main.cpp, cpp",
            "module.cc, cpp",
            "lib.cxx, cpp",
            "header.hpp, cpp",
            "types.hh, cpp",
            "impl.ipp, cpp",
            // C#
            "Program.cs, csharp",
            // PHP
            "index.php, php",
            "template.phtml, php",
            "legacy.php5, php",
            "common.inc, php",
            // HTML
            "index.html, html",
            "page.htm, html",
            "doc.xhtml, html"
    })
    void detectByExtension(String filename, String expectedLang) {
        Path path = Path.of(filename);
        Optional<String> result = LanguageDetector.detect(path);

        assertTrue(result.isPresent(), "Should detect language for " + filename);
        assertEquals(expectedLang, result.get());
    }

    @Test
    void detectUnknownExtension() {
        Optional<String> result = LanguageDetector.detect(Path.of("data.xml"));
        assertTrue(result.isEmpty(), "Should not detect XML");
    }

    @Test
    void detectNoExtension() {
        Optional<String> result = LanguageDetector.detect(Path.of("Makefile"));
        assertTrue(result.isEmpty(), "Should not detect files without extension");
    }

    @Test
    void detectNullPath() {
        Optional<String> result = LanguageDetector.detect((Path) null);
        assertTrue(result.isEmpty(), "Should return empty for null path");
    }

    @Test
    void detectByShebangPython() {
        String content = "#!/usr/bin/env python3\nprint('hello')";
        Optional<String> result = LanguageDetector.detect(Path.of("script"), content);
        assertTrue(result.isPresent());
        assertEquals("python", result.get());
    }

    @Test
    void detectByShebangNode() {
        String content = "#!/usr/bin/env node\nconsole.log('hello')";
        Optional<String> result = LanguageDetector.detect(Path.of("script"), content);
        assertTrue(result.isPresent());
        assertEquals("javascript", result.get());
    }

    @Test
    void isSupportedValidLanguages() {
        assertTrue(LanguageDetector.isSupported("java"));
        assertTrue(LanguageDetector.isSupported("kotlin"));
        assertTrue(LanguageDetector.isSupported("javascript"));
        assertTrue(LanguageDetector.isSupported("typescript"));
        assertTrue(LanguageDetector.isSupported("python"));
        assertTrue(LanguageDetector.isSupported("go"));
        assertTrue(LanguageDetector.isSupported("rust"));
        assertTrue(LanguageDetector.isSupported("c"));
        assertTrue(LanguageDetector.isSupported("cpp"));
        assertTrue(LanguageDetector.isSupported("csharp"));
        assertTrue(LanguageDetector.isSupported("php"));
        assertTrue(LanguageDetector.isSupported("html"));
    }

    @Test
    void isSupportedInvalidLanguages() {
        assertFalse(LanguageDetector.isSupported("ruby"));
        assertFalse(LanguageDetector.isSupported("perl"));
        assertFalse(LanguageDetector.isSupported(null));
        assertFalse(LanguageDetector.isSupported(""));
    }

    @Test
    void getSupportedLanguages() {
        var languages = LanguageDetector.getSupportedLanguages();

        assertNotNull(languages);
        assertTrue(languages.contains("java"));
        assertTrue(languages.contains("python"));
        assertTrue(languages.contains("c"));
        assertTrue(languages.contains("cpp"));
        assertTrue(languages.contains("csharp"));
        assertTrue(languages.contains("php"));
        assertTrue(languages.contains("html"));
        assertTrue(languages.size() >= 13, "Should have at least 13 languages");
    }

    @Test
    void getFileExtension() {
        assertEquals("java", LanguageDetector.getFileExtension("java").orElse(null));
        assertEquals("kt", LanguageDetector.getFileExtension("kotlin").orElse(null));
        assertEquals("py", LanguageDetector.getFileExtension("python").orElse(null));
        assertEquals("go", LanguageDetector.getFileExtension("go").orElse(null));
        assertEquals("rs", LanguageDetector.getFileExtension("rust").orElse(null));
        assertEquals("c", LanguageDetector.getFileExtension("c").orElse(null));
        assertEquals("cpp", LanguageDetector.getFileExtension("cpp").orElse(null));
        assertEquals("cs", LanguageDetector.getFileExtension("csharp").orElse(null));
        assertEquals("php", LanguageDetector.getFileExtension("php").orElse(null));
        assertEquals("html", LanguageDetector.getFileExtension("html").orElse(null));
        assertTrue(LanguageDetector.getFileExtension("unknown").isEmpty());
    }

    @Test
    void getGlobPattern() {
        assertEquals("**/*.java", LanguageDetector.getGlobPattern("java"));
        assertEquals("**/*.{kt,kts}", LanguageDetector.getGlobPattern("kotlin"));
        assertEquals("**/*.{js,mjs,cjs,jsx}", LanguageDetector.getGlobPattern("javascript"));
        assertEquals("**/*.{py,pyi,pyw}", LanguageDetector.getGlobPattern("python"));
        assertEquals("**/*.{c,h}", LanguageDetector.getGlobPattern("c"));
        assertEquals("**/*.{cpp,cc,cxx,c++,hpp,hh,hxx,h++,ipp}", LanguageDetector.getGlobPattern("cpp"));
        assertEquals("**/*.cs", LanguageDetector.getGlobPattern("csharp"));
        assertEquals("**/*.{php,phtml,php3,php4,php5,php7,phps,inc}", LanguageDetector.getGlobPattern("php"));
        assertEquals("**/*.{html,htm,xhtml,shtml}", LanguageDetector.getGlobPattern("html"));
    }
}
