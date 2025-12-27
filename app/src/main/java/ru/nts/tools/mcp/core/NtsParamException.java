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

import java.util.HashMap;
import java.util.Map;

/**
 * Exception for parameter-related errors (missing, invalid, out of range, etc.)
 */
public class NtsParamException extends NtsException {

    public NtsParamException(NtsErrorCode code, Map<String, Object> context) {
        super(code, context);
    }

    /**
     * Factory: Missing required parameter
     */
    public static NtsParamException missing(String paramName) {
        return new NtsParamException(NtsErrorCode.PARAM_MISSING,
                Map.of("parameter", paramName));
    }

    /**
     * Factory: Invalid parameter value
     */
    public static NtsParamException invalid(String paramName, Object value, String expected) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("parameter", paramName);
        ctx.put("value", value);
        ctx.put("expected", expected);
        return new NtsParamException(NtsErrorCode.PARAM_INVALID, ctx);
    }

    /**
     * Factory: Parameter out of range
     */
    public static NtsParamException outOfRange(String paramName, Object value, Object min, Object max) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("parameter", paramName);
        ctx.put("value", value);
        ctx.put("min", min);
        ctx.put("max", max);
        return new NtsParamException(NtsErrorCode.PARAM_OUT_OF_RANGE, ctx);
    }

    /**
     * Factory: Line number exceeds file
     */
    public static NtsParamException lineExceeds(int line, int totalLines, String path) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("line", line);
        ctx.put("totalLines", totalLines);
        ctx.put("path", path);
        return new NtsParamException(NtsErrorCode.PARAM_LINE_EXCEEDS, ctx);
    }

    /**
     * Factory: Conflicting parameters
     */
    public static NtsParamException conflict(String param1, String param2) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("param1", param1);
        ctx.put("param2", param2);
        return new NtsParamException(NtsErrorCode.PARAM_CONFLICT, ctx);
    }

    /**
     * Factory: Symbol not found
     */
    public static NtsParamException symbolNotFound(String symbol, String path) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("symbol", symbol);
        ctx.put("path", path);
        return new NtsParamException(NtsErrorCode.SYMBOL_NOT_FOUND, ctx);
    }

    /**
     * Factory: Pattern not found
     */
    public static NtsParamException patternNotFound(String pattern, String path) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("pattern", pattern);
        ctx.put("path", path);
        return new NtsParamException(NtsErrorCode.PATTERN_NOT_FOUND, ctx);
    }
}
