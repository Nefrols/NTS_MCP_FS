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

import java.util.Set;

/**
 * Role-aware фильтрация подсказок (TIPs) в ответах инструментов.
 *
 * Предотвращает ситуацию, когда подсказки ссылаются на инструменты,
 * недоступные текущей роли. Маленькая LLM попытается вызвать указанный
 * инструмент и получит ошибку — потеряет время и фокус.
 *
 * Список допустимых инструментов приходит динамически из конфигурации роли
 * (default_allowed_nts_tools в таблице roles), а не хардкодится.
 *
 * Для external clients (VSCode, Claude Code) allowedTools = null → все tips разрешены.
 */
public final class TipFilter {

    private static final ThreadLocal<Set<String>> currentAllowedTools = new ThreadLocal<>();

    private TipFilter() {}

    /**
     * Устанавливает набор допустимых инструментов для текущего потока.
     * Вызывается из McpTool.executeWithFeedback() при обработке ntsAllowedTools из params.
     *
     * @param tools набор имён инструментов, или null для external clients
     */
    public static void setCurrentAllowedTools(Set<String> tools) {
        currentAllowedTools.set(tools);
    }

    /**
     * Возвращает текущий набор допустимых инструментов.
     * null означает external client (все инструменты доступны).
     */
    public static Set<String> getCurrentAllowedTools() {
        return currentAllowedTools.get();
    }

    /**
     * Очищает ThreadLocal. Вызывается после обработки запроса.
     */
    public static void clear() {
        currentAllowedTools.remove();
    }

    /**
     * Проверяет, можно ли упоминать инструмент в подсказках.
     *
     * Если allowedTools == null (external client) → всегда true.
     * Иначе проверяет наличие в списке допустимых инструментов роли.
     *
     * @param toolName имя инструмента (например "nts_task", "nts_verify")
     * @return true если инструмент можно упоминать в подсказках
     */
    public static boolean canMention(String toolName) {
        Set<String> allowed = currentAllowedTools.get();
        if (allowed == null) return true; // external client — все tips разрешены
        return allowed.contains(toolName);
    }
}
