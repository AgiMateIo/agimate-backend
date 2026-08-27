package ru.agimate.agentworker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Арифметика бюджета ходов — та, что раньше жила переприсваиванием переменной цикла и проверялась
 * только через полный прогон рана в {@link AgiMateAgentTest}.
 */
class TurnBudgetTest {

    @Nested
    @DisplayName("счётчик")
    class Counter {

        @Test
        @DisplayName("выдаёт ровно maxTurns ходов, нумерация с единицы")
        void countsFromOne() {
            TurnBudget budget = new TurnBudget(3);

            assertTrue(budget.next());
            assertEquals(1, budget.current());
            assertTrue(budget.next());
            assertTrue(budget.next());
            assertEquals(3, budget.current());
            assertFalse(budget.next());
        }

        @Test
        @DisplayName("сброс делает текущий ход первым — следующий второй, то есть покупает один ход")
        void resetBuysOneTurn() {
            TurnBudget budget = new TurnBudget(2);
            budget.next();
            budget.next();
            assertEquals(2, budget.current());

            budget.reset();

            assertEquals(1, budget.current());
            assertTrue(budget.next());
            assertEquals(2, budget.current());
            assertFalse(budget.next());
        }

        @Test
        @DisplayName("потолок сбросов: дальше шов не опрашивается")
        void steeringIsCapped() {
            TurnBudget budget = new TurnBudget(2);
            for (int i = 0; i < AgiMateAgent.MAX_STEERING_RESETS; i++) {
                assertTrue(budget.canSteer());
                budget.reset();
            }

            assertFalse(budget.canSteer());
            assertEquals(AgiMateAgent.MAX_STEERING_RESETS, budget.resets());
        }
    }

    @Nested
    @DisplayName("мягкая посадка")
    class SoftLanding {

        @Test
        @DisplayName("нотис за WRAP_UP_TURNS до капа, последний ход без тулов")
        void wrapUpThenToolless() {
            TurnBudget budget = new TurnBudget(4);
            int wrapUpAt = 0;
            int toollessAt = 0;
            while (budget.next()) {
                if (budget.wrapUpTurn()) {
                    wrapUpAt = budget.current();
                }
                if (budget.toolless()) {
                    toollessAt = budget.current();
                }
            }

            assertEquals(3, wrapUpAt);
            assertEquals(4, toollessAt);
        }

        @Test
        @DisplayName("сброс перевзводит посадку: нотис инжектится заново на новом отсчёте")
        void resetRearmsTheLanding() {
            TurnBudget budget = new TurnBudget(4);
            budget.next();
            budget.next();
            budget.next();
            assertTrue(budget.wrapUpTurn());

            budget.reset();

            assertFalse(budget.wrapUpTurn());
            budget.next();
            budget.next();
            assertTrue(budget.wrapUpTurn());
        }

        @Test
        @DisplayName("крошечный кап (<= WRAP_UP_TURNS) посадку не включает вовсе")
        void skippedForTinyCap() {
            TurnBudget budget = new TurnBudget(AgiMateAgent.WRAP_UP_TURNS);

            while (budget.next()) {
                assertFalse(budget.wrapUpTurn());
                assertFalse(budget.toolless());
            }
        }
    }
}
