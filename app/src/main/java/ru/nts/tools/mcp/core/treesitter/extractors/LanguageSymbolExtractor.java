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

import java.nio.file.Path;
import java.util.Optional;

/**
 * Интерфейс для извлечения символов для конкретного языка.
 */
public interface LanguageSymbolExtractor {

    /**
     * Извлекает символ из узла AST.
     *
     * @param node       Текущий узел AST
     * @param nodeType   Тип узла (передается для оптимизации, чтобы не вызывать node.getType() лишний раз)
     * @param path       Путь к файлу
     * @param content    Содержимое файла
     * @param parentName Имя родительского символа (контейнера)
     * @return Optional с информацией о символе, если он был найден
     */
    Optional<SymbolInfo> extractSymbol(TSNode node, String nodeType, Path path, String content, String parentName);
}
