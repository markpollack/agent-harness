/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springaicommunity.agents.harness.callback;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agents.harness.core.TerminationReason;

class AgentResultTest {

	@Test
	void successFactoryShouldCreateSuccessResult() {
		AgentResult result = AgentResult.success("Task completed successfully", 5);

		assertThat(result.response()).isEqualTo("Task completed successfully");
		assertThat(result.terminationReason()).isEqualTo(TerminationReason.FINISH_TOOL_CALLED);
		assertThat(result.turnsUsed()).isEqualTo(5);
		assertThat(result.error()).isNull();
		assertThat(result.isSuccess()).isTrue();
		assertThat(result.isError()).isFalse();
	}

	@Test
	void maxTurnsReachedFactoryShouldCreateLimitedResult() {
		AgentResult result = AgentResult.maxTurnsReached("Partial response...", 10);

		assertThat(result.response()).isEqualTo("Partial response...");
		assertThat(result.terminationReason()).isEqualTo(TerminationReason.MAX_TURNS_REACHED);
		assertThat(result.turnsUsed()).isEqualTo(10);
		assertThat(result.error()).isNull();
		assertThat(result.isSuccess()).isFalse();
		assertThat(result.isError()).isFalse();
	}

	@Test
	void timeoutFactoryShouldCreateTimeoutResult() {
		AgentResult result = AgentResult.timeout("Timed out after...", 3);

		assertThat(result.response()).isEqualTo("Timed out after...");
		assertThat(result.terminationReason()).isEqualTo(TerminationReason.TIMEOUT);
		assertThat(result.turnsUsed()).isEqualTo(3);
		assertThat(result.error()).isNull();
		assertThat(result.isSuccess()).isFalse();
		assertThat(result.isError()).isFalse();
	}

	@Test
	void errorFactoryShouldCreateErrorResult() {
		RuntimeException exception = new RuntimeException("Connection failed");
		AgentResult result = AgentResult.error(exception, 2);

		assertThat(result.response()).isNull();
		assertThat(result.terminationReason()).isEqualTo(TerminationReason.ERROR);
		assertThat(result.turnsUsed()).isEqualTo(2);
		assertThat(result.error()).isSameAs(exception);
		assertThat(result.isSuccess()).isFalse();
		assertThat(result.isError()).isTrue();
	}

	@Test
	void abortedFactoryShouldCreateAbortedResult() {
		AgentResult result = AgentResult.aborted("User cancelled", 7);

		assertThat(result.response()).isEqualTo("User cancelled");
		assertThat(result.terminationReason()).isEqualTo(TerminationReason.EXTERNAL_SIGNAL);
		assertThat(result.turnsUsed()).isEqualTo(7);
		assertThat(result.error()).isNull();
		assertThat(result.isSuccess()).isFalse();
		assertThat(result.isError()).isFalse();
	}

	@Test
	void isSuccessShouldReturnTrueForSuccessfulTerminations() {
		assertThat(new AgentResult("done", TerminationReason.FINISH_TOOL_CALLED, 1, null).isSuccess()).isTrue();
		assertThat(new AgentResult("done", TerminationReason.SCORE_THRESHOLD_MET, 1, null).isSuccess()).isTrue();
		assertThat(new AgentResult("done", TerminationReason.USER_APPROVAL, 1, null).isSuccess()).isTrue();
		assertThat(new AgentResult("done", TerminationReason.WORKFLOW_COMPLETE, 1, null).isSuccess()).isTrue();
	}

	@Test
	void isSuccessShouldReturnFalseForNonSuccessTerminations() {
		assertThat(new AgentResult("partial", TerminationReason.MAX_TURNS_REACHED, 1, null).isSuccess()).isFalse();
		assertThat(new AgentResult("partial", TerminationReason.TIMEOUT, 1, null).isSuccess()).isFalse();
		assertThat(new AgentResult("partial", TerminationReason.COST_LIMIT_EXCEEDED, 1, null).isSuccess()).isFalse();
		assertThat(new AgentResult("partial", TerminationReason.STUCK_DETECTED, 1, null).isSuccess()).isFalse();
		assertThat(new AgentResult(null, TerminationReason.ERROR, 1, new RuntimeException()).isSuccess()).isFalse();
	}

	@Test
	void recordEqualityShouldWork() {
		AgentResult result1 = AgentResult.success("done", 5);
		AgentResult result2 = AgentResult.success("done", 5);
		AgentResult result3 = AgentResult.success("done", 6);

		assertThat(result1).isEqualTo(result2);
		assertThat(result1).isNotEqualTo(result3);
		assertThat(result1.hashCode()).isEqualTo(result2.hashCode());
	}

}
