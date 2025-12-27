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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Base exception class for NTS MCP tools.
 * Provides structured error reporting with error codes and context.
 *
 * <p>Usage:
 * <pre>
 * throw new NtsException(NtsErrorCode.FILE_NOT_FOUND, Map.of("path", pathStr));
 * </pre>
 */
public class NtsException extends RuntimeException {

    private final NtsErrorCode code;
    private final Map<String, Object> context;

    public NtsException(NtsErrorCode code) {
        super(code.getMessage());
        this.code = code;
        this.context = Collections.emptyMap();
    }

    public NtsException(NtsErrorCode code, Map<String, Object> context) {
        super(code.getMessage());
        this.code = code;
        this.context = context != null ? new HashMap<>(context) : Collections.emptyMap();
    }

    public NtsException(NtsErrorCode code, String key, Object value) {
        super(code.getMessage());
        this.code = code;
        this.context = Map.of(key, value);
    }

    public NtsException(NtsErrorCode code, Throwable cause) {
        super(code.getMessage(), cause);
        this.code = code;
        this.context = Collections.emptyMap();
    }

    public NtsException(NtsErrorCode code, Map<String, Object> context, Throwable cause) {
        super(code.getMessage(), cause);
        this.code = code;
        this.context = context != null ? new HashMap<>(context) : Collections.emptyMap();
    }

    public NtsErrorCode getCode() {
        return code;
    }

    public Map<String, Object> getContext() {
        return Collections.unmodifiableMap(context);
    }

    /**
     * Returns a formatted user-friendly error message.
     */
    public String toUserMessage() {
        return code.format(context);
    }

    @Override
    public String getMessage() {
        return toUserMessage();
    }

    /**
     * Returns a compact single-line error message for logs.
     */
    public String toLogMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(code.name()).append("] ").append(code.getMessage());
        if (!context.isEmpty()) {
            sb.append(" | ");
            boolean first = true;
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
                first = false;
            }
        }
        return sb.toString();
    }
}
