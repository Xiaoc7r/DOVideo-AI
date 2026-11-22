package com.example.server.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AgentExecutionBudgetTest {

  @Test
  void rejectsWorkAfterDeadline() throws Exception {
    try (AgentExecutionBudget.Scope ignored = AgentExecutionBudget.open(1)) {
      Thread.sleep(5);
      assertThrows(
          AgentExecutionBudget.DeadlineExceededException.class,
          AgentExecutionBudget::remainingMillis);
    }
    assertDoesNotThrow(AgentExecutionBudget::remainingMillis);
  }
}
