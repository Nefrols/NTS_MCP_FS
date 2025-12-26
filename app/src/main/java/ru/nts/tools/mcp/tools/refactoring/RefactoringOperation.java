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
package ru.nts.tools.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Базовый интерфейс для операций рефакторинга.
 * Каждая операция выполняет атомарное преобразование кода.
 */
public interface RefactoringOperation {

    /**
     * Имя операции (rename, generate, delete, etc.)
     */
    String getName();

    /**
     * Выполняет операцию рефакторинга.
     *
     * @param params параметры операции
     * @param context контекст выполнения
     * @return результат операции
     * @throws RefactoringException если операция не может быть выполнена
     */
    RefactoringResult execute(JsonNode params, RefactoringContext context) throws RefactoringException;

    /**
     * Выполняет preview операции без применения изменений.
     *
     * @param params параметры операции
     * @param context контекст выполнения
     * @return предварительный результат с diff
     * @throws RefactoringException если операция не может быть выполнена
     */
    RefactoringResult preview(JsonNode params, RefactoringContext context) throws RefactoringException;

    /**
     * Валидирует параметры операции.
     *
     * @param params параметры для проверки
     * @throws IllegalArgumentException если параметры некорректны
     */
    void validateParams(JsonNode params) throws IllegalArgumentException;
}
