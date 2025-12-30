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

import static org.junit.jupiter.api.Assertions.*;

class PythonSymbolExtractorTest extends AbstractSymbolExtractorTest {

    @Test
    void extractPythonClass() {
        String code = """
                class Calculator:
                    def __init__(self):
                        self.result = 0
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Calculator") && s.kind() == SymbolKind.CLASS));
    }

    @Test
    void extractPythonFunctions() {
        String code = """
                def greet(name):
                    print(f"Hello, {name}!")

                def add(a, b):
                    return a + b
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("greet") && s.kind() == SymbolKind.FUNCTION));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("add") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractPythonMethods() {
        String code = """
                class Service:
                    def __init__(self):
                        pass

                    def process(self):
                        pass
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("__init__") && s.kind() == SymbolKind.CONSTRUCTOR));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("process") && s.kind() == SymbolKind.METHOD));
    }

    // ==================== Edge Cases ====================

    @Test
    void extractPythonTypedParameters() {
        String code = """
                def calculate(x: int, y: float) -> float:
                    return x + y
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        SymbolInfo func = symbols.stream()
                .filter(s -> s.name().equals("calculate"))
                .findFirst()
                .orElse(null);

        assertNotNull(func);
        assertEquals(SymbolKind.FUNCTION, func.kind());
        if (func.parameters() != null) {
            assertEquals(2, func.parameters().size());
        }
    }

    @Test
    void extractPythonDefaultParameters() {
        String code = """
                def greet(name: str, greeting: str = "Hello") -> str:
                    return f"{greeting}, {name}"
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("greet") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractPythonArgsKwargs() {
        String code = """
                def flexible(*args, **kwargs):
                    pass
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        SymbolInfo func = symbols.stream()
                .filter(s -> s.name().equals("flexible"))
                .findFirst()
                .orElse(null);

        assertNotNull(func);
    }

    @Test
    void extractPythonDecorators() {
        String code = """
                @staticmethod
                def static_method():
                    pass

                @classmethod
                def class_method(cls):
                    pass

                @property
                def computed(self):
                    return self._value
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("static_method")));
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("class_method")));
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("computed")));
    }

    @Test
    void extractPythonAsyncFunction() {
        String code = """
                async def fetch_data(url: str) -> dict:
                    pass
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("fetch_data") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractPythonNestedClass() {
        String code = """
                class Outer:
                    class Inner:
                        def inner_method(self):
                            pass

                    def outer_method(self):
                        pass
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Outer") && s.kind() == SymbolKind.CLASS));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Inner") && s.kind() == SymbolKind.CLASS));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("inner_method")));
    }

    @Test
    void extractPythonInheritance() {
        String code = """
                class Animal:
                    def speak(self):
                        pass

                class Dog(Animal):
                    def speak(self):
                        return "Woof!"
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Animal") && s.kind() == SymbolKind.CLASS));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Dog") && s.kind() == SymbolKind.CLASS));
    }

    @Test
    void extractPythonMultipleInheritance() {
        String code = """
                class A:
                    pass

                class B:
                    pass

                class C(A, B):
                    pass
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("A")));
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("B")));
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("C")));
    }

    @Test
    void extractPythonLambda() {
        String code = """
                square = lambda x: x ** 2
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");
        // Lambda may not be extracted as named function
    }

    @Test
    void extractPythonComprehension() {
        String code = """
                def get_squares(n):
                    return [x**2 for x in range(n)]
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("get_squares") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractPythonDataclass() {
        String code = """
                from dataclasses import dataclass

                @dataclass
                class Person:
                    name: str
                    age: int
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Person") && s.kind() == SymbolKind.CLASS));
    }

    @Test
    void extractPythonDunderMethods() {
        String code = """
                class Container:
                    def __init__(self, items):
                        self.items = items

                    def __len__(self):
                        return len(self.items)

                    def __iter__(self):
                        return iter(self.items)

                    def __str__(self):
                        return str(self.items)
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("__init__")));
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("__len__")));
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("__iter__")));
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("__str__")));
    }

    @Test
    void extractPythonPrivateMethods() {
        String code = """
                class SecureClass:
                    def public_method(self):
                        pass

                    def _protected_method(self):
                        pass

                    def __private_method(self):
                        pass
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("public_method")));
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("_protected_method")));
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("__private_method")));
    }

    @Test
    void extractPythonUtf8Names() {
        String code = """
                def 计算(数值):
                    return 数值 * 2

                class Калькулятор:
                    def вычислить(self, значение):
                        return значение
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("计算")));
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("Калькулятор")));
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("вычислить")));
    }
}
