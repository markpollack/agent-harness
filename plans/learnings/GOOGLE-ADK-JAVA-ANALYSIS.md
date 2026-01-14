# Learning: Google ADK Java Architecture Analysis

**Date**: 2026-01-14
**Context**: Research into Google's Agent Development Kit (Java) to understand loop patterns and how they compare to agent-harness

---

## Summary

Google ADK Java represents a **different architectural layer** than agent-harness. Key insight: ADK operates primarily at the **multi-agent orchestration** level, while agent-harness operates at the **single-agent execution** level.

---

## ADK Agent Types

| Type | Purpose | Deterministic? |
|------|---------|----------------|
| **LlmAgent** | Uses LLM for reasoning, tool calling | No |
| **SequentialAgent** | Executes sub-agents in order | Yes |
| **ParallelAgent** | Runs sub-agents simultaneously | Yes |
| **LoopAgent** | Iterates sub-agents until condition | Yes |
| **Custom Agent** | Extends BaseAgent for custom logic | Varies |

---

## Critical Insight: Two-Level Architecture

ADK has a clear separation between:

### Inner Execution (Inside LlmAgent)
- LLM reasoning
- Tool calling
- Response generation
- **This is what agent-harness models with TurnLimitedLoop**

### Outer Orchestration (Workflow Agents)
- SequentialAgent, ParallelAgent, LoopAgent
- Multi-agent coordination
- State passing between agents
- **This is what AgentClient models with Planner + orchestration**

```
┌────────────────────────────────────────────────────────────────┐
│  ADK OUTER LAYER (Workflow Agents)                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐            │
│  │ Sequential  │  │  Parallel   │  │    Loop     │            │
│  │   Agent     │  │   Agent     │  │   Agent     │            │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘            │
│         │                │                │                    │
│         └────────────────┴────────────────┘                    │
│                          ▼                                     │
├────────────────────────────────────────────────────────────────┤
│  ADK INNER LAYER (LlmAgent)                                    │
│  ┌────────────────────────────────────────────────────────┐   │
│  │  LLM reasoning → tool calls → responses → ...          │   │
│  │  (Internal loop - not explicitly exposed)              │   │
│  └────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────┘
```

---

## ADK Multi-Agent Patterns (8 Patterns)

| Pattern | ADK Implementation | Description |
|---------|-------------------|-------------|
| **Sequential Pipeline** | SequentialAgent | Agents execute in order, like assembly line |
| **Coordinator/Dispatcher** | AutoFlow | Central agent routes to specialists |
| **Parallel Fan-Out/Gather** | ParallelAgent | Simultaneous execution for speed |
| **Hierarchical Decomposition** | AgentTool | Wrap agent as callable tool |
| **Generator and Critic** | LoopAgent | Create → validate → iterate |
| **Iterative Refinement** | LoopAgent + escalate | Refine until quality threshold |
| **Human-in-the-Loop** | Approval tools | Human authorizes high-stakes decisions |
| **Composite** | Combinations | Real apps combine multiple patterns |

---

## LoopAgent Termination Mechanisms

ADK's LoopAgent requires explicit termination (no infinite loops):

1. **maxIterations(N)** - Safety limit, hard cap on iterations
2. **Escalate via tool** - Sub-agent calls `setEscalate(true)` when done
3. **beforeAgentCallback** - Check condition before each iteration
4. **afterAgentCallback** - Check condition after each iteration

```java
// Java example: Escalate from tool
public static Map<String, Object> exitLoop(
    @Schema(name = "toolContext") ToolContext toolContext) {
    toolContext.actions().setEscalate(true);
    return Map.of();
}
```

---

## Mapping ADK to Our Architecture

| ADK Concept | agent-harness Equivalent | Notes |
|-------------|-------------------------|-------|
| LlmAgent internal loop | TurnLimitedLoop | Both handle single-agent tool execution |
| LoopAgent | AgentClient orchestration | Outer loop iterating over agents/steps |
| SequentialAgent | Planner step execution | Execute plan steps in order |
| ParallelAgent | (Not modeled) | Could be Strategy variant |
| maxIterations | maxTurns | Safety limit on iterations |
| escalate=true | Finish tool pattern | Agent signals completion |
| session.state | AgentContext | Shared state across execution |

---

## What ADK Does NOT Have (That We Do)

| Our Feature | ADK Equivalent | Notes |
|-------------|---------------|-------|
| **Judge abstraction** | None | ADK uses callbacks, not scored evaluation |
| **Evaluator-Optimizer** | Generator/Critic pattern | Similar but ours is more formalized |
| **StateMachineLoop** | Custom Agent | ADK doesn't have built-in state machine |
| **JuryTerminationStrategy** | maxIterations + escalate | Ours is more flexible with scoring |

---

## What ADK Has (That We Should Consider)

| ADK Feature | Value | Priority |
|-------------|-------|----------|
| **ParallelAgent** | Speed via concurrency | LOW - niche use case |
| **AutoFlow routing** | Automatic agent selection | MEDIUM - could help multi-agent |
| **AgentTool** | Wrap agent as tool | LOW - interesting abstraction |
| **Human-in-the-Loop tools** | Approval gates | HIGH - aligns with Plan Mode |

---

## Key Takeaway

**ADK validates our architectural decision**: Planning and multi-agent orchestration belong at a higher level than single-agent execution loops.

- ADK's `LoopAgent` is NOT a replacement for `TurnLimitedLoop`
- ADK's `LoopAgent` is an ORCHESTRATOR that runs multiple LlmAgents
- Our TurnLimitedLoop maps to what happens INSIDE an ADK LlmAgent
- Our Planner/AgentClient maps to ADK's Workflow Agents layer

This confirms the `learnings/PLANNING-AS-FIRST-CLASS.md` decision: planning in AgentClient, execution patterns in agent-harness.

---

## References

- [Google ADK Documentation](https://google.github.io/adk-docs/)
- [ADK Java GitHub](https://github.com/google/adk-java)
- [Loop Agents Documentation](https://google.github.io/adk-docs/agents/workflow-agents/loop-agents/)
- [Multi-Agent Patterns Guide](https://developers.googleblog.com/developers-guide-to-multi-agent-patterns-in-adk/)
- [Mastering ADK Loop Agents](https://glaforge.dev/posts/2025/07/28/mastering-agentic-workflows-with-adk-loop-agents/)

---

*This learning captures how Google ADK Java's architecture compares to our agent-harness and validates our two-layer approach (AgentClient for orchestration, agent-harness for execution).*
