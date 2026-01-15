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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question.Option;

class AgentCallbackTest {

	@Test
	void defaultCallbackMethodsShouldDoNothing() {
		AgentCallback callback = new AgentCallback() {};

		// All default methods should complete without exception
		callback.onThinking();
		callback.onToolCall("testTool", "{\"arg\": \"value\"}");
		callback.onToolResult("testTool", "result");
		callback.onResponse("Hello", false);
		callback.onResponse("Hello, World!", true);
		callback.onError(new RuntimeException("test error"));
		callback.onComplete();
	}

	@Test
	void defaultOnQuestionShouldReturnEmptyMap() {
		AgentCallback callback = new AgentCallback() {};

		List<Question> questions = List.of(
			new Question("Which option?", "Choice",
				List.of(new Option("A", "First option"), new Option("B", "Second option")),
				false)
		);

		Map<String, String> result = callback.onQuestion(questions);

		assertThat(result).isEmpty();
	}

	@Test
	void customCallbackShouldReceiveEvents() {
		List<String> events = new ArrayList<>();
		AtomicBoolean completed = new AtomicBoolean(false);

		AgentCallback callback = new AgentCallback() {
			@Override
			public void onThinking() {
				events.add("thinking");
			}

			@Override
			public void onToolCall(String toolName, String toolInput) {
				events.add("toolCall:" + toolName);
			}

			@Override
			public void onToolResult(String toolName, String toolResult) {
				events.add("toolResult:" + toolName);
			}

			@Override
			public void onResponse(String text, boolean isFinal) {
				events.add("response:" + isFinal);
			}

			@Override
			public void onError(Throwable error) {
				events.add("error:" + error.getMessage());
			}

			@Override
			public void onComplete() {
				completed.set(true);
			}
		};

		callback.onThinking();
		callback.onToolCall("ReadTool", "{\"path\": \"/test\"}");
		callback.onToolResult("ReadTool", "file contents");
		callback.onResponse("Processing...", false);
		callback.onResponse("Done!", true);
		callback.onError(new RuntimeException("test"));
		callback.onComplete();

		assertThat(events).containsExactly(
			"thinking",
			"toolCall:ReadTool",
			"toolResult:ReadTool",
			"response:false",
			"response:true",
			"error:test"
		);
		assertThat(completed).isTrue();
	}

	@Test
	void customOnQuestionShouldReturnAnswers() {
		AgentCallback callback = new AgentCallback() {
			@Override
			public Map<String, String> onQuestion(List<Question> questions) {
				return Map.of(questions.get(0).question(), "Selected Option A");
			}
		};

		List<Question> questions = List.of(
			new Question("Which database?", "Database",
				List.of(new Option("PostgreSQL", "Relational DB"),
					new Option("MongoDB", "Document DB")),
				false)
		);

		Map<String, String> answers = callback.onQuestion(questions);

		assertThat(answers).containsEntry("Which database?", "Selected Option A");
	}

}
