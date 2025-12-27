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

import org.junit.jupiter.api.BeforeEach;
import org.treesitter.TSTree;
import ru.nts.tools.mcp.core.treesitter.LanguageDetector;
import ru.nts.tools.mcp.core.treesitter.SymbolExtractor;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo;
import ru.nts.tools.mcp.core.treesitter.TreeSitterManager;

import java.nio.file.Path;
import java.util.List;

public abstract class AbstractSymbolExtractorTest {

    protected SymbolExtractor extractor;
    protected TreeSitterManager manager;

    @BeforeEach
    void setUp() {
        extractor = SymbolExtractor.getInstance();
        manager = TreeSitterManager.getInstance();
    }

    protected List<SymbolInfo> parseAndExtract(String code, String langId) {
        Path path = Path.of("test." + LanguageDetector.getFileExtension(langId).orElse("txt"));
        TSTree tree = manager.parse(code, langId);
        return extractor.extractDefinitions(tree, path, code, langId);
    }
}
