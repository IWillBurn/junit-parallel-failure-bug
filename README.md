# junit-parallel-failure-bug

Ошибка JUnit 6.1.3 при параллельном выполнении: если источник аргументов `@ParameterizedTest` ломается посередине,
JUnit завершает тест-шаблон, не дождавшись уже запущенных вызовов.

Нужны JDK 17+ и Maven 3.9+.

```bash
mvn test
```

## Тест

`EmptyTestTest` — один и тот же `@ParameterizedTest` дважды: во вложенном классе, который выполняется
последовательно (`SAME_THREAD`), и во вложенном классе, который выполняется параллельно (`CONCURRENT`, идёт вторым).
Источник отдаёт аргументы `"a1"` и `"a2"`, а на `"b1"` бросает исключение (до `"b2"`, `"c1"` и `"c2"` дело не
доходит). У теста нет никакого состояния: он только ждёт полсекунды.

Ожидается одно и то же в обоих вариантах: вызовы `argument "a1"` и `argument "a2"` свои аргументы получили, поэтому
выполняются и проходят, а `test(String)` завершается после них с ошибкой источника `cannot provide b`.

## Что смотреть

`PrintEvents` (`TestExecutionListener`, подключён через `META-INF/services`) печатает события выполнения в конце
прогона и помечает места, где родитель и ребёнок пересекаются. Последовательно пометок нет. Параллельно шаблон
завершается раньше своего вызова:

```
STARTED    test(String)
REGISTERED argument "a1"
REGISTERED argument "a2"
STARTED    argument "a1"
STARTED    argument "a2"
FINISHED   test(String) FAILED (cannot provide b)   <-- its child [argument "a1", argument "a2"] has not finished yet
FINISHED   argument "a1" SUCCESSFUL   <-- its parent has already FINISHED
FINISHED   argument "a2" SUCCESSFUL   <-- its parent has already FINISHED
```

Это нарушает контракт платформы (`EngineExecutionListener#executionFinished`: для контейнера вызывается *после*
завершения всех его детей).

В пуле из одного потока брошенный вызов стартует только после конца всего прогона, и пустой тест пропадает из
отчёта:

```bash
mvn test -Djunit.jupiter.execution.parallel.config.strategy=fixed \
         -Djunit.jupiter.execution.parallel.config.fixed.parallelism=1
# Tests run: 4 вместо 5 — параллельного "argument a1" в отчёте Surefire нет, последовательный есть
```

Параллельное выполнение включено в `src/test/resources/junit-platform.properties`.

## Дерево

### IntelliJ IDEA

Обычный прогон `EmptyTestTest` из IDE (IntelliJ IDEA 2025.2, упрощённо):

```
sequential (SAME_THREAD)
└─ test(String)        ✘ cannot provide b
   ├─ argument "a1"    ✔
   └─ argument "a2"    ✔
parallel (CONCURRENT)
└─ test(String)        ✘ cannot provide b
   ├─ argument "a1"    Test ignored
   └─ argument "a2"    Test ignored
```

Параллельные вызовы на самом деле выполняются и проходят. Но IntelliJ узнаёт о завершении `test(String)` раньше, чем
о завершении его вызовов, и сама закрывает их как пропущенные (`testIgnored` в протоколе её раннера). Настоящие события
`a1` и `a2` приходят позже и дерево уже не меняют. С фиксом JUnit сообщает о вызовах до завершения `test(String)`,
и в IDE они ✔.

### JUnit ConsoleLauncher

`ConsoleLauncher --details=tree` рисует дерево в конце прогона по итоговым статусам, поэтому в обычном прогоне обе
ветки выглядят одинаково (всё ✔ и `test(String)` ✘), как и с фиксом. В пуле из одного потока (`parallelism=1`)
разница видна и здесь:

```
no state: @MethodSource gives "a1", "a2", then throws on "b1" ✔
├─ sequential (SAME_THREAD) ✔
│  └─ test(String) ✘ cannot provide b
│     ├─ argument "a1" ✔
│     └─ argument "a2" ✔
└─ parallel (CONCURRENT) ✔
   └─ test(String) ✘ cannot provide b
      ├─ argument "a2" ✔
      └─ argument "a1" ✘ sleep interrupted
```

Пустой `argument "a1"` последовательно проходит, а параллельно стартует уже после того, как JUnit Jupiter сообщил о своём
завершении, и прерывается посреди `Thread.sleep(500)`. В отчёте Surefire этого вызова нет вовсе (см. выше).

Как получить дерево:

```bash
mvn test-compile dependency:copy -Dartifact=org.junit.platform:junit-platform-console-standalone:6.1.3 -DoutputDirectory=target
java -jar target/junit-platform-console-standalone-6.1.3.jar execute --class-path target/test-classes \
     --select-class bug.EmptyTestTest --details=tree
# для одного потока добавить:
#    --config=junit.jupiter.execution.parallel.config.strategy=fixed \
#    --config=junit.jupiter.execution.parallel.config.fixed.parallelism=1
```

## Где в коде JUnit

[`NodeTestTask#executeRecursively`](https://github.com/junit-team/junit-framework/blob/r6.1.3/junit-platform-engine/src/main/java/org/junit/platform/engine/support/hierarchical/NodeTestTask.java#L157-L190):
когда `node.execute(...)` бросает исключение, следующая в той же лямбде строка
`dynamicTestExecutor.awaitFinished()` не выполняется, и узел шаблона завершается, пока его вызовы ещё в пуле.
