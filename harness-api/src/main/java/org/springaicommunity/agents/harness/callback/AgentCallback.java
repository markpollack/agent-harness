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

import java.util.List;
import java.util.Map;

import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;

/**
 * Callback interface for receiving events from an agent during execution.
 *
 * <p>This interface enables TUI and other clients to observe agent behavior
 * without depending on Spring AI internals. Implementations receive notifications
 * about agent state changes, tool calls, and can respond to questions.
 *
 * <p>All methods have default no-op implementations, allowing clients to
 * override only the callbacks they need.
 *
 * <p><strong>Thread Safety:</strong> Implementations should be thread-safe
 * as callbacks may be invoked from different threads during agent execution.
 *
 * @author Mark Pollack
 * @see Question
 */
public interface AgentCallback {

	/**
	 * Called when the agent begins processing (thinking).
	 * Use this to display a spinner or "thinking" indicator.
	 */
	default void onThinking() {
	}

	/**
	 * Called when the agent invokes a tool.
	 * @param toolName the name of the tool being called
	 * @param toolInput the input/arguments passed to the tool (may be JSON)
	 */
	default void onToolCall(String toolName, String toolInput) {
	}

	/**
	 * Called when a tool execution completes.
	 * @param toolName the name of the tool that completed
	 * @param toolResult the result returned by the tool (may be truncated)
	 */
	default void onToolResult(String toolName, String toolResult) {
	}

	/**
	 * Called when the agent needs to ask the user questions.
	 *
	 * <p>This is a synchronous callback - the agent will block until
	 * answers are returned. The returned map should have question text
	 * as keys and selected option labels (or custom text) as values.
	 *
	 * <p>Reuses {@link Question} from spring-ai-agent-utils to maintain
	 * compatibility with the AskUserQuestionTool schema.
	 *
	 * @param questions the questions to present to the user (1-4 questions)
	 * @return a map of question text to answer text; empty map if no answers
	 */
	default Map<String, String> onQuestion(List<Question> questions) {
		return Map.of();
	}

	/**
	 * Called when the agent produces a response.
	 * @param text the response text (may be partial during streaming)
	 * @param isFinal true if this is the final response, false if streaming
	 */
	default void onResponse(String text, boolean isFinal) {
	}

	/**
	 * Called when the agent encounters an error.
	 * @param error the exception that occurred
	 */
	default void onError(Throwable error) {
	}

	/**
	 * Called when the agent execution completes (successfully or not).
	 * Always called as the final callback, even after onError.
	 */
	default void onComplete() {
	}

}
