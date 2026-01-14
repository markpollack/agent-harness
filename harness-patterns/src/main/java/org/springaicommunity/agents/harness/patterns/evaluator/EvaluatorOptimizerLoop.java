/*
 * Copyright 2024-2026 the original author or authors.
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
package org.springaicommunity.agents.harness.patterns.evaluator;

import org.springaicommunity.agents.harness.core.AgentLoop;
import org.springaicommunity.agents.harness.core.LoopState;
import org.springaicommunity.agents.harness.core.TerminationReason;
import org.springaicommunity.agents.harness.patterns.judge.SpringAiJuryAdapter;
import org.springaicommunity.agents.harness.strategy.TerminationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agents.judge.jury.Verdict;
import org.springaicommunity.agents.judge.score.Scores;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Evaluator-Optimizer loop pattern (Reflexion).
 * <p>
 * This pattern implements the three-phase generate-evaluate-reflect cycle:
 * <ol>
 *   <li><b>Actor</b>: Generates output using ChatClient with tools based on input and previous reflection</li>
 *   <li><b>Evaluator</b>: Jury evaluates the output quality</li>
 *   <li><b>Reflector</b>: Uses ChatClient to analyze the evaluation and produce feedback for next trial</li>
 * </ol>
 *
 * <p>Key characteristics:
 * <ul>
 *   <li>Self-improvement through reflection loop</li>
 *   <li>Tracks score progression across trials</li>
 *   <li>Detects when agent is stuck (no improvement)</li>
 *   <li>Tool calling in actor phase via ChatClient</li>
 *   <li>Full W&B-lite observability</li>
 * </ul>
 *
 * <p>Uses Spring AI ChatClient directly - synchronous API, no Reactor.
 * <p>
 * Configuration is provided at construction time via the builder pattern.
 */
public class EvaluatorOptimizerLoop implements AgentLoop<EvaluatorOptimizerResult> {

    private static final Logger log = LoggerFactory.getLogger(EvaluatorOptimizerLoop.class);

    private final EvaluatorOptimizerConfig config;
    private final List<LoopListener> listeners;
    private final AtomicBoolean abortSignal;
    private final SpringAiJuryAdapter juryAdapter;

    private EvaluatorOptimizerLoop(Builder builder) {
        this.config = builder.config;
        this.listeners = new CopyOnWriteArrayList<>(builder.listeners);
        this.abortSignal = new AtomicBoolean(false);

        // Create jury adapter if jury configured
        if (config.jury().isPresent()) {
            this.juryAdapter = new SpringAiJuryAdapter(
                    config.jury().get(),
                    "evaluator-optimizer-jury"
            );
        } else {
            this.juryAdapter = null;
        }
    }

    /**
     * Record of a single trial in the loop.
     */
    public record TrialRecord(
            int trialNumber,
            String output,
            double score,
            boolean passed,
            String reflection,
            Duration duration
    ) {}

    /**
     * Loop event listener for observability hooks.
     */
    public interface LoopListener {
        default void onLoopStarted(String runId, String userMessage) {}
        default void onTrialStarted(String runId, int trial) {}
        default void onActorCompleted(String runId, int trial, String output) {}
        default void onEvaluationCompleted(String runId, int trial, double score, boolean passed) {}
        default void onReflectionCompleted(String runId, int trial, String reflection) {}
        default void onTrialCompleted(String runId, int trial, double score) {}
        default void onLoopCompleted(String runId, TerminationReason reason) {}
        default void onLoopFailed(String runId, Throwable error) {}
    }

    @Override
    public EvaluatorOptimizerResult execute(
            String userMessage,
            ChatClient chatClient,
            List<ToolCallback> tools
    ) {
        String runId = UUID.randomUUID().toString();
        abortSignal.set(false);

        // Initialize state
        LoopState state = LoopState.initial(runId);

        notifyLoopStarted(runId, userMessage);
        log.debug("Evaluator-optimizer loop started: runId={}, maxTrials={}, scoreThreshold={}",
                runId, config.maxTrials(), config.scoreThreshold());

        try {
            TrialLoopResult result = executeTrialLoop(state, userMessage, chatClient, tools, runId);

            notifyLoopCompleted(runId, result.reason);
            log.info("Evaluator-optimizer loop completed: totalTrials={}, bestScore={}, reason={}",
                    result.trials.size(), result.bestScore, result.reason.name());

            return EvaluatorOptimizerResult.terminated(
                    runId,
                    result.bestOutput,
                    result.reason,
                    result.trials.size(),
                    result.state.elapsed(),
                    result.state.totalTokensUsed(),
                    result.state.estimatedCost(),
                    result.trials,
                    result.bestScore,
                    result.bestReflection
            );

        } catch (Exception error) {
            log.error("Loop failed for run {}: {}", runId,
                    error.getMessage() != null ? error.getMessage() : "Unknown", error);
            notifyLoopFailed(runId, error);

            return EvaluatorOptimizerResult.failed(
                    runId,
                    state.currentTurn(),
                    state.elapsed()
            );
        }
    }

    /**
     * Main trial loop execution.
     */
    private TrialLoopResult executeTrialLoop(
            LoopState state,
            String userMessage,
            ChatClient chatClient,
            List<ToolCallback> tools,
            String runId
    ) {
        List<TrialRecord> trials = new ArrayList<>();
        List<Double> scoreHistory = new ArrayList<>();
        String currentReflection = "";
        String bestOutput = null;
        String bestReflection = null;
        double bestScore = 0.0;
        TerminationReason terminationReason = TerminationReason.NOT_TERMINATED;
        LoopState currentState = state;

        for (int trial = 1; trial <= config.maxTrials() && !abortSignal.get(); trial++) {
            Instant trialStart = Instant.now();

            notifyTrialStarted(state.runId(), trial);

            // Check timeout
            if (currentState.timeoutExceeded(config.timeout())) {
                terminationReason = TerminationReason.TIMEOUT;
                break;
            }

            // Phase 1: Actor - Generate output using ChatClient
            log.debug("Trial {} actor phase started", trial);

            String actorOutput;
            long tokensUsed = 0;
            double cost = 0.0;
            boolean finishToolCalled = false;

            try {
                // Build prompt with reflection context
                String fullPrompt = trial == 1
                        ? userMessage
                        : userMessage + "\n\nReflection from previous trial:\n" + currentReflection;

                ChatResponse actorResponse = chatClient.prompt()
                        .user(fullPrompt)
                        .tools((Object[]) tools.toArray(new ToolCallback[0]))
                        .call()
                        .chatResponse();

                actorOutput = extractOutputText(actorResponse);
                tokensUsed = extractTokensUsed(actorResponse);
                cost = estimateCost(tokensUsed);
                finishToolCalled = hasFinishTool(actorResponse, config.finishToolName());

                currentState = currentState.completeTurn(tokensUsed, cost, hasToolCalls(actorResponse), actorOutput.hashCode());

                log.debug("Trial {} actor phase completed: tokens={}", trial, tokensUsed);

            } catch (Exception e) {
                log.error("Actor phase failed in trial {}: {}", trial,
                        e.getMessage() != null ? e.getMessage() : "Unknown");
                continue;
            }

            notifyActorCompleted(state.runId(), trial, actorOutput);

            // Check if finish tool was called
            if (finishToolCalled) {
                terminationReason = TerminationReason.FINISH_TOOL_CALLED;
                bestOutput = actorOutput;
                break;
            }

            // Phase 2: Evaluator - Judge the output
            double score = 0.0;
            boolean passed = false;
            String reasoning = "No jury configured";
            Verdict verdict = null;

            if (config.jury().isPresent() && juryAdapter != null) {
                log.debug("Trial {} evaluator phase started", trial);

                try {
                    verdict = juryAdapter.evaluate(currentState, null, config.workingDirectory());

                    if (verdict != null) {
                        passed = verdict.aggregated().pass();
                        score = Scores.toNormalized(verdict.aggregated().score(), Map.of());
                        reasoning = verdict.aggregated().reasoning();
                    }
                    log.debug("Trial {} evaluator phase completed: score={}, passed={}",
                            trial, score, passed);
                } catch (Exception e) {
                    log.error("Evaluator phase failed in trial {}: {}", trial,
                            e.getMessage() != null ? e.getMessage() : "Unknown");
                }
            }

            notifyEvaluationCompleted(state.runId(), trial, score, passed);
            scoreHistory.add(score);
            log.debug("Trial {} score: {}", trial, score);

            // Update best
            if (score > bestScore) {
                bestScore = score;
                bestOutput = actorOutput;
                bestReflection = currentReflection;
            }

            // Check termination conditions
            if (config.requirePass() && passed) {
                terminationReason = TerminationReason.SCORE_THRESHOLD_MET;
                break;
            }

            if (!config.requirePass() && score >= config.scoreThreshold()) {
                terminationReason = TerminationReason.SCORE_THRESHOLD_MET;
                break;
            }

            // Check stuck detection
            if (isStuck(scoreHistory, config.stuckThreshold(), config.improvementDelta())) {
                terminationReason = TerminationReason.STUCK_DETECTED;
                break;
            }

            // Phase 3: Reflector - Generate feedback for next trial using ChatClient
            if (trial < config.maxTrials()) {
                log.debug("Trial {} reflector phase started", trial);

                try {
                    String reflectionPrompt = buildReflectionPrompt(actorOutput, verdict, score, reasoning, trial, scoreHistory);

                    ChatResponse reflectResponse = chatClient.prompt()
                            .user(reflectionPrompt)
                            .call()
                            .chatResponse();

                    currentReflection = extractOutputText(reflectResponse);
                    long reflectTokens = extractTokensUsed(reflectResponse);
                    currentState = currentState.completeTurn(reflectTokens, estimateCost(reflectTokens), false, currentReflection.hashCode());

                    log.debug("Trial {} reflector phase completed: reflectionLength={}",
                            trial, currentReflection.length());
                } catch (Exception e) {
                    log.warn("Reflector phase failed in trial {}: {}", trial,
                            e.getMessage() != null ? e.getMessage() : "Unknown");
                }

                notifyReflectionCompleted(state.runId(), trial, currentReflection);
            }

            // Record trial
            Duration trialDuration = Duration.between(trialStart, Instant.now());
            trials.add(new TrialRecord(trial, actorOutput, score, passed, currentReflection, trialDuration));
            notifyTrialCompleted(state.runId(), trial, score);

            log.debug("Trial {} completed: score={}, passed={}, duration={}ms",
                    trial, score, passed, trialDuration.toMillis());
        }

        // Handle max trials reached
        if (terminationReason == TerminationReason.NOT_TERMINATED) {
            terminationReason = TerminationReason.MAX_ITERATIONS_REACHED;
        }

        return new TrialLoopResult(currentState, trials, bestOutput, bestScore, bestReflection, terminationReason);
    }

    /**
     * Builds a reflection prompt from trial results.
     */
    private String buildReflectionPrompt(
            String actorOutput,
            Verdict verdict,
            double score,
            String reasoning,
            int trial,
            List<Double> scoreHistory
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a reflector analyzing the output of an AI agent.\n\n");
        prompt.append("Trial: ").append(trial).append("\n");
        prompt.append("Score: ").append(String.format("%.2f", score)).append("\n");
        prompt.append("Score history: ").append(scoreHistory).append("\n\n");
        prompt.append("Agent output:\n").append(actorOutput).append("\n\n");
        prompt.append("Evaluation reasoning:\n").append(reasoning).append("\n\n");
        prompt.append("Please provide constructive feedback for the next trial to improve the score. ");
        prompt.append("Focus on specific actionable improvements.");

        if (config.reflectorPrompt() != null && !config.reflectorPrompt().isBlank()) {
            prompt.append("\n\nAdditional guidance:\n").append(config.reflectorPrompt());
        }

        return prompt.toString();
    }

    /**
     * Checks if the optimization is stuck (no improvement for N trials).
     */
    private boolean isStuck(List<Double> scoreHistory, int threshold, double minDelta) {
        if (scoreHistory.size() < threshold) {
            return false;
        }

        // Check last N scores for improvement
        int n = scoreHistory.size();
        double recentMax = scoreHistory.get(n - 1);
        double earlierMax = scoreHistory.get(n - threshold);

        for (int i = n - threshold; i < n; i++) {
            double s = scoreHistory.get(i);
            if (s > recentMax) recentMax = s;
            if (i < n - 1 && s > earlierMax) earlierMax = s;
        }

        // Stuck if improvement is below threshold
        return (recentMax - earlierMax) < minDelta;
    }

    // --- Helper methods for ChatResponse processing ---

    private String extractOutputText(ChatResponse response) {
        if (response == null || response.getResult() == null) return "";
        var output = response.getResult().getOutput();
        return output != null && output.getText() != null ? output.getText() : "";
    }

    private boolean hasToolCalls(ChatResponse response) {
        if (response == null || response.getResult() == null) return false;
        var output = response.getResult().getOutput();
        return output != null && output.hasToolCalls();
    }

    private boolean hasFinishTool(ChatResponse response, String finishToolName) {
        if (!hasToolCalls(response)) return false;
        var toolCalls = response.getResult().getOutput().getToolCalls();
        return toolCalls.stream().anyMatch(tc -> tc.name().equals(finishToolName));
    }

    private long extractTokensUsed(ChatResponse response) {
        if (response == null || response.getMetadata() == null) return 0;
        var usage = response.getMetadata().getUsage();
        if (usage == null) return 0;
        return usage.getTotalTokens() != null ? usage.getTotalTokens() : 0;
    }

    private double estimateCost(long tokens) {
        return tokens * 0.000006; // Average ~$6/1M tokens
    }

    @Override
    public TerminationStrategy terminationStrategy() {
        return TerminationStrategy.allOf(List.of(
                TerminationStrategy.maxTurns(100),
                TerminationStrategy.timeout(Duration.ofMinutes(30)),
                TerminationStrategy.stuckDetection(3),
                TerminationStrategy.abortSignal()
        ));
    }

    @Override
    public LoopType loopType() {
        return LoopType.EVALUATOR_OPTIMIZER;
    }

    /**
     * Signals the loop to abort.
     */
    public void abort() {
        abortSignal.set(true);
    }

    /**
     * Adds a loop listener.
     */
    public void addListener(LoopListener listener) {
        listeners.add(listener);
    }

    // --- Inner classes ---

    /**
     * Internal result of trial loop execution.
     */
    private record TrialLoopResult(
            LoopState state,
            List<TrialRecord> trials,
            String bestOutput,
            double bestScore,
            String bestReflection,
            TerminationReason reason
    ) {}

    // --- Listener notifications ---

    private void notifyLoopStarted(String runId, String userMessage) {
        for (var listener : listeners) {
            try {
                listener.onLoopStarted(runId, userMessage);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    private void notifyTrialStarted(String runId, int trial) {
        for (var listener : listeners) {
            try {
                listener.onTrialStarted(runId, trial);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    private void notifyActorCompleted(String runId, int trial, String output) {
        for (var listener : listeners) {
            try {
                listener.onActorCompleted(runId, trial, output);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    private void notifyEvaluationCompleted(String runId, int trial, double score, boolean passed) {
        for (var listener : listeners) {
            try {
                listener.onEvaluationCompleted(runId, trial, score, passed);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    private void notifyReflectionCompleted(String runId, int trial, String reflection) {
        for (var listener : listeners) {
            try {
                listener.onReflectionCompleted(runId, trial, reflection);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    private void notifyTrialCompleted(String runId, int trial, double score) {
        for (var listener : listeners) {
            try {
                listener.onTrialCompleted(runId, trial, score);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    private void notifyLoopCompleted(String runId, TerminationReason reason) {
        for (var listener : listeners) {
            try {
                listener.onLoopCompleted(runId, reason);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    private void notifyLoopFailed(String runId, Throwable error) {
        for (var listener : listeners) {
            try {
                listener.onLoopFailed(runId, error);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    // --- Builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private EvaluatorOptimizerConfig config;
        private List<LoopListener> listeners = new ArrayList<>();

        public Builder config(EvaluatorOptimizerConfig config) {
            this.config = config;
            return this;
        }

        public Builder addListener(LoopListener listener) {
            this.listeners.add(listener);
            return this;
        }

        public EvaluatorOptimizerLoop build() {
            if (config == null) {
                throw new IllegalStateException("Config must be set");
            }
            return new EvaluatorOptimizerLoop(this);
        }
    }
}
