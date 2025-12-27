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
 * Exception for token-related errors (missing, invalid, expired, etc.)
 */
public class NtsTokenException extends NtsException {

    public NtsTokenException(NtsErrorCode code) {
        super(code);
    }

    public NtsTokenException(NtsErrorCode code, Map<String, Object> context) {
        super(code, context);
    }

    /**
     * Factory: Token required
     */
    public static NtsTokenException required(Path path) {
        return new NtsTokenException(NtsErrorCode.TOKEN_REQUIRED,
                Map.of("path", path.toString()));
    }

    /**
     * Factory: Invalid token format
     */
    public static NtsTokenException invalidFormat(String token) {
        return new NtsTokenException(NtsErrorCode.TOKEN_INVALID_FORMAT,
                Map.of("token", truncateToken(token)));
    }

    /**
     * Factory: Token expired (content changed)
     */
    public static NtsTokenException expired(Path path, String reason) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("path", path.toString());
        ctx.put("reason", reason);
        return new NtsTokenException(NtsErrorCode.TOKEN_EXPIRED, ctx);
    }

    /**
     * Factory: Token path mismatch
     */
    public static NtsTokenException pathMismatch(Path expected, Path actual) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("expected", expected.toString());
        ctx.put("actual", actual.toString());
        return new NtsTokenException(NtsErrorCode.TOKEN_PATH_MISMATCH, ctx);
    }

    /**
     * Factory: Token range doesn't cover edit
     */
    public static NtsTokenException rangeMismatch(int tokenStart, int tokenEnd, int editStart, int editEnd) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("tokenRange", tokenStart + "-" + tokenEnd);
        ctx.put("editRange", editStart + "-" + editEnd);
        return new NtsTokenException(NtsErrorCode.TOKEN_RANGE_MISMATCH, ctx);
    }

    /**
     * Factory: Token invalidated by external change
     */
    public static NtsTokenException externalChange(Path path) {
        return new NtsTokenException(NtsErrorCode.TOKEN_EXTERNAL_CHANGE,
                Map.of("path", path.toString()));
    }

    private static String truncateToken(String token) {
        if (token == null) return "null";
        if (token.length() <= 20) return token;
        return token.substring(0, 10) + "..." + token.substring(token.length() - 10);
    }
}
