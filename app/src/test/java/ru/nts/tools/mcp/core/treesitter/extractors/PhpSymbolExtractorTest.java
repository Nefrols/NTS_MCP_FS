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

import org.junit.jupiter.api.Test;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.SymbolKind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpSymbolExtractorTest extends AbstractSymbolExtractorTest {

    @Test
    void extractPhpClass() {
        String code = """
                <?php
                class UserService {
                    public function getUser() {}
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "php");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("UserService") && s.kind() == SymbolKind.CLASS));
    }

    @Test
    void extractPhpInterface() {
        String code = """
                <?php
                interface Repository {
                    public function find($id);
                    public function save($entity);
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "php");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Repository") && s.kind() == SymbolKind.INTERFACE));
    }

    @Test
    void extractPhpTrait() {
        String code = """
                <?php
                trait Loggable {
                    public function log($message) {}
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "php");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Loggable") && s.kind() == SymbolKind.TRAIT));
    }

    @Test
    void extractPhpFunction() {
        String code = """
                <?php
                function calculateSum($a, $b) {
                    return $a + $b;
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "php");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("calculateSum") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractPhpMethod() {
        String code = """
                <?php
                class Calculator {
                    public function add($a, $b) {
                        return $a + $b;
                    }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "php");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("add") && s.kind() == SymbolKind.METHOD));
    }

    @Test
    void extractPhpConstructor() {
        String code = """
                <?php
                class User {
                    public function __construct($name) {
                        $this->name = $name;
                    }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "php");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("__construct") && s.kind() == SymbolKind.CONSTRUCTOR));
    }

    @Test
    void extractPhpNamespace() {
        String code = """
                <?php
                namespace App\\Services;

                class UserService {}
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "php");

        assertTrue(symbols.stream().anyMatch(s ->
                s.kind() == SymbolKind.NAMESPACE));
    }
}
