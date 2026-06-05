/*
 * Copyright 2024-2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://mariadb.com/bsl11/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.markpollack.workflow.callback;

import java.util.List;

/**
 * A question for the user, presented through {@link AgentCallback#onQuestion}.
 *
 * <p>Owned by workflow-api so the callback SPI does not depend on any tool
 * library. Adapters (e.g. AskUserQuestionTool integration in workflow-agents)
 * map their tool-specific question types onto this record.
 *
 * @param question the complete question to ask the user; clear, specific,
 * ends with a question mark
 * @param header very short label displayed as a chip/tag (max ~12 chars),
 * e.g. "Auth method", "Library", "Approach"
 * @param options the available choices (typically 2-4, mutually exclusive
 * unless {@code multiSelect})
 * @param multiSelect true to allow selecting multiple options; null defaults
 * to false
 * @author Mark Pollack
 */
public record Question(String question, String header, List<Option> options, Boolean multiSelect) {

	public Question {
		if (question == null || question.isBlank()) {
			throw new IllegalArgumentException("Question text cannot be null or blank");
		}
		if (header == null || header.isBlank()) {
			throw new IllegalArgumentException("Header cannot be null or blank");
		}
		if (multiSelect == null) {
			multiSelect = false;
		}
		options = options == null ? List.of() : List.copyOf(options);
	}

	/**
	 * A single option/choice for a question.
	 *
	 * @param label concise display text for this option (1-5 words)
	 * @param description explanation of what this option means or what will
	 * happen if chosen
	 */
	public record Option(String label, String description) {

		public Option {
			if (label == null || label.isBlank()) {
				throw new IllegalArgumentException("Option label cannot be null or blank");
			}
			if (description == null || description.isBlank()) {
				throw new IllegalArgumentException("Option description cannot be null or blank");
			}
		}

	}

}
