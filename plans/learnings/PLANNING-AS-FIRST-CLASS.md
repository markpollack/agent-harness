# Exploration: Planning as a First-Class Concept

**Date**: 2026-01-13
**Status**: ✅ DECIDED - Approach A (Planning in AgentClient)
**Decision**: Planning belongs in AgentClient (spring-ai-agents), NOT as a loop pattern in agent-harness

---

## The Intuition

> "Planning seems so important, it feels like a precursor to even turn-based approaches."

This suggests planning might be more fundamental than we've treated it. Currently:
- `TurnLimitedLoop` - no explicit planning
- `EvaluatorOptimizerLoop` - implicit planning (improve based on feedback)
- `StateMachineLoop` - hardcoded plan (states are pre-defined)
- `CognitivePipelineLoop` - explicit LLM planning

But is planning *always* happening, just at different levels of explicitness?

---

## Planning as a Spectrum

| Level | Description | Example |
|-------|-------------|---------|
| **None** | Pure reactive | Chatbot responds to each message |
| **Implicit** | LLM internally reasons | Claude thinks "I should read the file first" |
| **Embedded** | Plan in conversation | "I'll: 1) read, 2) edit, 3) test" |
| **Explicit** | Separate planning phase | CognitivePipeline PLAN step |
| **External** | Algorithm plans | GOAP A* search |

**Observation**: Even "turn-limited" agents plan—the planning is just hidden inside the LLM's reasoning.

---

## Evidence: Claude Code Plans Implicitly

When you give Claude Code a complex task, it often outputs:

```
I'll help you implement OAuth. Let me:
1. First read the existing auth configuration
2. Understand the current user model
3. Add the OAuth dependencies
4. Create the OAuth configuration class
5. Update the security config
6. Add tests
```

This IS planning. It's just:
- Embedded in the response (not a separate phase)
- Not persisted (lost if context resets)
- Not validated (no check that plan makes sense)

---

## Two Levels of Planning

| Level | Where | What | Persistence |
|-------|-------|------|-------------|
| **Inner planning** | Inside LLM turn | "What tool should I call next?" | None (ephemeral) |
| **Outer planning** | Before execution | "What steps will this task require?" | Persisted, revisable |

**Inner planning** happens automatically via Chain-of-Thought prompting.
**Outer planning** is what CognitivePipeline makes explicit.

---

## The AgentClient Architecture Question

If AgentClient is the "outer loop" orchestrator, should it have planning as a first-class component?

```
┌─────────────────────────────────────────────────────────────────┐
│                      AgentClient                                 │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │   Planner   │→ │  Executor   │→ │    Judge    │              │
│  │             │  │             │  │             │              │
│  │ Decompose   │  │ Run steps   │  │ Evaluate    │              │
│  │ task into   │  │ using inner │  │ outcomes    │              │
│  │ steps       │  │ loops       │  │             │              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
│         │                │                │                      │
│         └────────────────┴────────────────┘                      │
│                    Feedback loop                                 │
└─────────────────────────────────────────────────────────────────┘
```

This would make planning orthogonal to execution strategy:

| Planner | Executor (Inner Loop) | Use Case |
|---------|----------------------|----------|
| None | TurnLimitedLoop | Simple tasks, chatbots |
| LLM | TurnLimitedLoop | Complex tasks, implicit decomposition |
| LLM | CognitivePipeline | Multi-step tasks, explicit decomposition |
| LLM | StateMachine | Workflow tasks with known stages |

---

## What Would a Planner Interface Look Like?

```java
public interface Planner<T> {

    /**
     * Decompose a task into steps.
     * @param task The high-level task description
     * @param context Available context (codebase, history, etc.)
     * @return A plan with ordered steps
     */
    Plan plan(T task, PlanningContext context);

    /**
     * Revise a plan based on execution feedback.
     * @param original The original plan
     * @param feedback What went wrong or changed
     * @return A revised plan
     */
    Plan replan(Plan original, ExecutionFeedback feedback);
}

public record Plan(
    List<PlanStep> steps,
    String reasoning,          // Why this decomposition
    Map<String, Object> metadata
) {
    public boolean isEmpty() { return steps.isEmpty(); }
    public PlanStep currentStep() { return steps.get(0); }
    public Plan advance() { return new Plan(steps.subList(1, steps.size()), reasoning, metadata); }
}

public record PlanStep(
    String description,        // What to do
    String acceptanceCriteria, // How to know it's done
    Set<String> toolsNeeded,   // Which tools this step needs
    Duration estimatedEffort   // Optional: for budgeting
) {}
```

---

## Planning Strategies

| Strategy | Implementation | When to Use |
|----------|----------------|-------------|
| **NullPlanner** | Returns single-step plan | Simple tasks |
| **LlmPlanner** | LLM decomposes task | Complex, open-ended tasks |
| **TemplatePlanner** | Pre-defined templates | Known workflows (PR review, deploy) |
| **HybridPlanner** | Template + LLM fills gaps | Semi-structured tasks |

```java
// NullPlanner - treats entire task as one step
public class NullPlanner implements Planner<String> {
    @Override
    public Plan plan(String task, PlanningContext context) {
        return Plan.singleStep(task);
    }
}

// LlmPlanner - asks LLM to decompose
public class LlmPlanner implements Planner<String> {
    private final ChatClient chatClient;

    @Override
    public Plan plan(String task, PlanningContext context) {
        String prompt = """
            Decompose this task into concrete steps:
            Task: %s

            For each step, specify:
            - What to do
            - How to know it's done
            - What tools are needed
            """.formatted(task);

        return chatClient.prompt(prompt)
            .call()
            .entity(Plan.class);
    }
}
```

---

## How This Changes the Loop Patterns

### Before: Planning embedded in specific patterns

```
TurnLimitedLoop      → No planning
EvaluatorOptimizer   → Implicit planning (refinement)
StateMachine         → Hardcoded plan (states)
CognitivePipeline    → Explicit planning
```

### After: Planning is orthogonal

```
AgentClient
  .planner(llmPlanner)           // or nullPlanner, templatePlanner
  .executor(turnLimitedLoop)     // or stateMachine, etc.
  .judge(testJudge)
  .execute(task);
```

Any planner can be combined with any executor:

| Combination | Behavior |
|-------------|----------|
| NullPlanner + TurnLimited | Current MiniAgent behavior |
| LlmPlanner + TurnLimited | Plan first, then execute freely |
| LlmPlanner + StateMachine | Plan maps to state transitions |
| TemplatePlanner + TurnLimited | Known workflow, flexible execution |

---

## The Key Insight

**Planning is not a pattern—it's a dimension.**

Current taxonomy treats CognitivePipeline as "the planning pattern." But planning can be applied to ANY execution strategy:

```
           ┌─────────────────────────────────────┐
           │         PLANNING DIMENSION          │
           │  None → Implicit → Explicit → Algo  │
           └─────────────────────────────────────┘
                           ×
           ┌─────────────────────────────────────┐
           │        EXECUTION DIMENSION          │
           │  TurnLimited | StateMachine | etc.  │
           └─────────────────────────────────────┘
```

---

## Implications for AgentClient

If we accept this, AgentClient should have:

```java
public class AgentClient {
    private final Planner<?> planner;           // How to decompose tasks
    private final AgentLoop<?> executor;        // How to execute steps
    private final Judge judge;                  // How to evaluate outcomes
    private final List<Advisor> advisors;       // Cross-cutting concerns

    public Result execute(Task task) {
        Plan plan = planner.plan(task, context);

        for (PlanStep step : plan.steps()) {
            StepResult result = executor.execute(step);

            if (!judge.evaluate(result)) {
                plan = planner.replan(plan, result.feedback());
                // Continue with revised plan
            }
        }

        return aggregateResults();
    }
}
```

---

## Open Questions

1. **Should planning be mandatory or optional?**
   - NullPlanner makes it optional (no-op)
   - But having the interface means everyone thinks about it

2. **Where does planning context come from?**
   - Codebase structure (from ExploreTool)
   - Previous attempts (from tracking)
   - User preferences (from config)

3. **How does planning interact with Human-in-Loop?**
   - Should humans approve plans before execution?
   - Should humans be able to edit plans?

4. **Is replanning the same interface as planning?**
   - Replanning has additional context (what failed)
   - Might need different prompts/strategies

---

## Recommendation

**Make Planner a first-class component of AgentClient**, not just an internal detail of CognitivePipelineLoop.

This:
- Makes the architecture more composable
- Forces explicit thinking about task decomposition
- Allows planning strategies to evolve independently
- Enables plan persistence, editing, and approval

The inner loops (TurnLimited, StateMachine, etc.) become **execution strategies**, while planning becomes a separate concern at the AgentClient level.

---

## Decision: Approach A - Planning in AgentClient

After evaluating options, we chose **Approach A**:

| Aspect | Decision |
|--------|----------|
| **Where planning lives** | AgentClient (spring-ai-agents) |
| **What agent-harness provides** | Pure execution patterns (TurnLimited, StateMachine, EvaluatorOptimizer) |
| **CognitivePipelineLoop** | REMOVED from agent-harness - it's just AgentClient + Planner + inner loop |

### Why Approach A?

1. **No inheritance problem**: "TurnLimitedLoop with planning" = AgentClient(planner=LlmPlanner, executor=TurnLimitedLoop)
2. **No wrapping loops**: CognitivePipelineLoop wrapping TurnLimitedLoop was confusing
3. **Single responsibility**: Each loop does one thing (execution)
4. **Clear layering**: AgentClient orchestrates, agent-harness executes

### The Clean Architecture

```
┌──────────────────────────────────────────┐
│  AgentClient (spring-ai-agents)          │
│  - Planner (decompose)                   │
│  - Judge (evaluate)                      │
│  - Orchestration (plan/execute/adapt)    │
├──────────────────────────────────────────┤
│  agent-harness                           │
│  - TurnLimitedLoop                       │
│  - StateMachineLoop                      │
│  - EvaluatorOptimizerLoop                │
│  (pure execution, no planning)           │
└──────────────────────────────────────────┘
```

### Implementation

- **spring-ai-agents ROADMAP Step 1**: Add Planner component to AgentClient
- **agent-harness**: CognitivePipelineLoop removed from roadmap (out of scope)

---

*This exploration led to a clear architectural decision: planning is a first-class component of AgentClient, not a loop pattern.*
