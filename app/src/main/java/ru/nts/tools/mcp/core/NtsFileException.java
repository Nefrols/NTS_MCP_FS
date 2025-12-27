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
package ru.nts.tools.mcp.core;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Exception for file-related errors (not found, not readable, too large, etc.)
 */
public class NtsFileException extends NtsException {

    public NtsFileException(NtsErrorCode code, Path path) {
        super(code, Map.of("path", path.toString()));
    }

    public NtsFileException(NtsErrorCode code, String pathStr) {
        super(code, Map.of("path", pathStr));
    }

    public NtsFileException(NtsErrorCode code, Path path, String action) {
        super(code, createContext(path, action));
    }

    public NtsFileException(NtsErrorCode code, Path path, Throwable cause) {
        super(code, Map.of("path", path.toString()), cause);
    }

    private static Map<String, Object> createContext(Path path, String action) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("path", path.toString());
        if (action != null) {
            ctx.put("action", action);
        }
        return ctx;
    }

    /**
     * Factory: File not found
     */
    public static NtsFileException notFound(Path path) {
        return new NtsFileException(NtsErrorCode.FILE_NOT_FOUND, path);
    }

    /**
     * Factory: File not found (string path)
     */
    public static NtsFileException notFound(String pathStr) {
        return new NtsFileException(NtsErrorCode.FILE_NOT_FOUND, pathStr);
    }

    /**
     * Factory: File too large
     */
    public static NtsFileException tooLarge(Path path, long sizeBytes, long maxBytes) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("path", path.toString());
        ctx.put("size", sizeBytes);
        ctx.put("maxAllowed", maxBytes);
        return new NtsFileException(NtsErrorCode.FILE_TOO_LARGE, ctx);
    }

    private NtsFileException(NtsErrorCode code, Map<String, Object> context) {
        super(code, context);
    }

    /**
     * Factory: Directory not found
     */
    public static NtsFileException directoryNotFound(Path path) {
        return new NtsFileException(NtsErrorCode.DIRECTORY_NOT_FOUND, path);
    }

    /**
     * Factory: Directory not empty
     */
    public static NtsFileException directoryNotEmpty(Path path) {
        return new NtsFileException(NtsErrorCode.DIRECTORY_NOT_EMPTY, path);
    }
}
