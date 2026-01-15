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

import org.springaicommunity.agents.harness.core.TerminationReason;

/**
 * Result of an agent execution.
 *
 * <p>Captures the outcome of running an agent, including the final response,
 * why it terminated, and execution metrics.
 *
 * @param response the final response text from the agent
 * @param terminationReason why the agent stopped executing
 * @param turnsUsed number of turns (LLM calls) executed
 * @param error exception if terminated due to error, null otherwise
 * @author Mark Pollack
 */
public record AgentResult(
		String response,
		TerminationReason terminationReason,
		int turnsUsed,
		Throwable error) {

	/**
	 * Create a successful result.
	 * @param response the final response text
	 * @param turnsUsed number of turns executed
	 * @return a successful AgentResult
	 */
	public static AgentResult success(String response, int turnsUsed) {
		return new AgentResult(response, TerminationReason.FINISH_TOOL_CALLED, turnsUsed, null);
	}

	/**
	 * Create a result that terminated due to turn limit.
	 * @param response the partial response text
	 * @param turnsUsed number of turns executed (should equal max turns)
	 * @return an AgentResult indicating max turns reached
	 */
	public static AgentResult maxTurnsReached(String response, int turnsUsed) {
		return new AgentResult(response, TerminationReason.MAX_TURNS_REACHED, turnsUsed, null);
	}

	/**
	 * Create a result that terminated due to timeout.
	 * @param response the partial response text
	 * @param turnsUsed number of turns executed before timeout
	 * @return an AgentResult indicating timeout
	 */
	public static AgentResult timeout(String response, int turnsUsed) {
		return new AgentResult(response, TerminationReason.TIMEOUT, turnsUsed, null);
	}

	/**
	 * Create a result that terminated due to an error.
	 * @param error the exception that caused termination
	 * @param turnsUsed number of turns executed before error
	 * @return an AgentResult indicating error
	 */
	public static AgentResult error(Throwable error, int turnsUsed) {
		return new AgentResult(null, TerminationReason.ERROR, turnsUsed, error);
	}

	/**
	 * Create a result that terminated due to external abort signal.
	 * @param response the partial response text
	 * @param turnsUsed number of turns executed before abort
	 * @return an AgentResult indicating external abort
	 */
	public static AgentResult aborted(String response, int turnsUsed) {
		return new AgentResult(response, TerminationReason.EXTERNAL_SIGNAL, turnsUsed, null);
	}

	/**
	 * Check if this result represents a successful completion.
	 * @return true if the agent completed successfully
	 */
	public boolean isSuccess() {
		return terminationReason == TerminationReason.FINISH_TOOL_CALLED
				|| terminationReason == TerminationReason.SCORE_THRESHOLD_MET
				|| terminationReason == TerminationReason.USER_APPROVAL
				|| terminationReason == TerminationReason.WORKFLOW_COMPLETE;
	}

	/**
	 * Check if this result represents an error.
	 * @return true if the agent terminated due to an error
	 */
	public boolean isError() {
		return terminationReason == TerminationReason.ERROR;
	}

}
