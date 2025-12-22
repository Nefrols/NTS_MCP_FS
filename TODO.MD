# План улучшения MCP инструментов (Inspired by line_edit)

## Общие принципы реализации
- [x] **TDD подход**: На каждый чих — тест.
- [x] **Валидация**: Проверка целостности после правок.
- [x] **Обратная совместимость**: Старые методы работают.

---

# ЭТАП: Глобальная атомарность и UNDO/REDO (ЗАВЕРШЕНО)
- [x] TransactionManager & SnapshotSystem
- [x] Multi-file Batching & Atomic Rollback
- [x] Undo/Redo & Transaction Journal

---

# НОВЫЙ ЭТАП: Инструменты автоматизации (Gradle & Git)

## 1. Безопасное выполнение (Core)
- [ ] **ProcessExecutor**: Утилита для запуска внешних программ без вызова shell.
- [ ] **Output Limiter**: Защита от слишком длинного вывода (обрезка логов).

## 2. Gradle Tool
- [ ] **gradle_task**: Выполнение задач Gradle (build, test, clean).
- [ ] **Smart Pathing**: Автоматическое использование wrapper (gradlew).

## 3. Git Tool
- [ ] **git_cmd**: Ограниченный набор команд (status, diff, log, add, commit).
- [ ] **Security**: Защита от инъекций через аргументы командной строки.

## 4. Тестирование
- [ ] Проверка изоляции процессов.
- [ ] Проверка работы в Windows/Unix средах.