# 06. Бизнес-правила и формулы ЛУТ

Все расчёты — в `backend/app/services/sheet_calc.py`, чистые функции, тип `Decimal`.
Округление — до 2 знаков, `ROUND_HALF_UP`, только в конце вычисления каждого поля.
Если для поля не хватает данных — значение `null`.

## Вычисляемые поля (`calc`)
| Поле | Формула | Когда считается |
|---|---|---|
| `refueled_l` | Σ `liters` всех заправок листа | всегда (0.00 если заправок нет) |
| `refueled_cost` | Σ `total_cost` | всегда |
| `fuel_available_l` | `fuel_start_l` + `refueled_l` | всегда |
| `mileage_km` | `odometer_end_km` − `odometer_start_km` | если введён пробег на конец |
| `norm_consumption_l` | `mileage_km` × `norm_l_per_100km` / 100 | если есть `mileage_km` |
| `fuel_end_calc_l` | `fuel_available_l` − `norm_consumption_l` | если есть `mileage_km` |
| `fuel_end_l` | `fuel_end_actual_l`, иначе `fuel_end_calc_l` | итоговый остаток на конец месяца |
| `actual_consumption_l` | `fuel_available_l` − `fuel_end_actual_l` | если введены пробег на конец И фактический остаток |
| `actual_l_per_100km` | `actual_consumption_l` / `mileage_km` × 100 | если есть `actual_consumption_l` и `mileage_km` > 0 |
| `deviation_l` | `actual_consumption_l` − `norm_consumption_l` (> 0 — перерасход, < 0 — экономия) | если есть оба |
| `cost_per_km` | `refueled_cost` / `mileage_km` | если `mileage_km` > 0 |

«Остаток в баке» в карточке = `fuel_end_l`. Пока пробег на конец не введён, карточка показывает
«доступно: `fuel_available_l` л» (остаток на начало + заправлено).

## Предупреждения (`calc.warnings`) — не блокируют сохранение
| code | Условие |
|---|---|
| `FUEL_END_NEGATIVE` | `fuel_end_calc_l` < 0 (по норме топлива не хватило — проверьте данные) |
| `FUEL_END_OVER_TANK` | `fuel_end_l` > `tank_capacity_l` авто |
| `REFUELING_OVER_TANK` | одна заправка > `tank_capacity_l` |
| `ODOMETER_GAP` | `odometer_start_km` ≠ `odometer_end_km` предыдущего месяца |
| `REFUELING_ODOMETER_OUT_OF_RANGE` | `odometer_km` заправки вне [`odometer_start_km`, `odometer_end_km`] |

Формат: `{ "code": "FUEL_END_NEGATIVE", "message": "Расчётный остаток отрицательный" }`.

## Валидации — блокируют (ошибка 400/422)
1. `odometer_end_km` >= `odometer_start_km`.
2. `fuel_start_l` >= 0, `fuel_end_actual_l` >= 0, `liters` > 0, цены >= 0.
3. `fuel_end_actual_l` <= `fuel_available_l` (нельзя закончить месяц с большим количеством
   топлива, чем было).
4. Дата заправки внутри месяца листа (`year`/`month`).
5. Один лист на авто на месяц (409 `SHEET_EXISTS`).
6. Нельзя создать лист на месяц позже текущего более чем на 1 (`BUSINESS_RULE`).
7. Закрыть лист можно только с `odometer_end_km`.
8. Закрытый лист и его заправки не редактируются (409 `SHEET_CLOSED`) — сначала `reopen`.
9. `total_cost`, если не передан, = `liters` × `price_per_liter`.

## Перенос между месяцами (`next-prefill`)
- Есть предыдущий лист (самый поздний): следующий месяц после него;
  `odometer_start_km` = его `odometer_end_km` (если null — его `odometer_start_km`);
  `fuel_start_l` = его `calc.fuel_end_l` (если null — `0.00`).
- Листов нет: текущий месяц, `odometer_start_km` = 0, `fuel_start_l` = 0.00 — пользователь вводит сам.
- Пользователь может изменить подставленные значения (тогда может появиться `ODOMETER_GAP`).
- Переоткрытие и изменение прошлого листа НЕ меняет автоматически следующий лист.

## Эталонные примеры (обязательные тест-кейсы для `sheet_calc`)
**Пример A — закрытый месяц с фактическим остатком.**
Норма 8.50; пробег 52340 → 53340; остаток на начало 12.00; заправки 40.00 л / 2200.00 ₽ и
40.00 л / 2200.00 ₽; фактический остаток 10.00.
→ refueled_l 80.00 · refueled_cost 4400.00 · fuel_available_l 92.00 · mileage_km 1000 ·
norm_consumption_l 85.00 · fuel_end_calc_l 7.00 · fuel_end_l 10.00 · actual_consumption_l 82.00 ·
actual_l_per_100km 8.20 · deviation_l −3.00 · cost_per_km 4.40 · warnings [].

**Пример B — открытый месяц, только начало.**
Пробег на начало 53340, конец null; остаток 10.00; одна заправка 30.00 л / 1650.00 ₽.
→ refueled_l 30.00 · fuel_available_l 40.00 · все поля, зависящие от пробега, — null.

**Пример C — закрыт без фактического остатка.**
Как A, но `fuel_end_actual_l` = null → fuel_end_l 7.00 · actual_* и deviation_l — null.

**Пример D — отрицательный остаток.**
Норма 8.50; пробег 1500 км; остаток на начало 12.00; заправлено 80.00.
→ norm_consumption_l 127.50 · fuel_end_calc_l −35.50 · warnings [FUEL_END_NEGATIVE].

**Пример E — нулевой пробег.**
Пробег 53340 → 53340, заправка 20.00 л → mileage_km 0 · norm_consumption_l 0.00 ·
actual_l_per_100km null · cost_per_km null.

**Пример F — округление.**
Пробег 333 км, норма 7.77 → 333 × 7.77 / 100 = 25.8741 → norm_consumption_l 25.87.
