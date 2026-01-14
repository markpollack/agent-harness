# Research Summary: Agent Loop Patterns for Agentic Harness

**Date**: 2026-01-14
**Purpose**: Comprehensive summary of research sources for continued research validation
**Target**: Research agent to verify completeness of pattern coverage

---

## Executive Summary

We have conducted extensive research into agent loop patterns across 25+ repositories and 6 academic papers. This document summarizes all sources, key findings, and potential gaps for a research agent to validate.

---

## Research Questions to Validate

1. **Have we missed any major loop patterns?** - Are there patterns beyond our 8 that production agents use?
2. **Are there newer frameworks?** - Any major agent frameworks released after Dec 2025 we haven't analyzed?
3. **Academic validation** - Are there newer papers (2025-2026) that document loop patterns?
4. **Google ADK completeness** - We just researched Google ADK Java - anything we missed?
5. **Multi-agent orchestration** - Have we adequately covered orchestration patterns (beyond single-agent loops)?
6. **Graph-based paradigm** - Is graph-defined execution (Koog, LangGraph) a separate category we should address?

---

## Two-Axis Taxonomy Model (NEW - 2026-01-14)

We identified that agent architectures should be classified on **two orthogonal axes**, not a single pattern list.

### Axis 1: Termination Pattern (How the loop ends)

Our existing 8 patterns - unchanged.

### Axis 2: Execution Definition Model (How the loop is defined)

| Model | Definition Style | Examples |
|-------|-----------------|----------|
| **Imperative** | Inline code (while/for) | Gemini CLI, Aider, Claude Code, Swarm |
| **Generator** | Yield points | Auto-Code-Rover |
| **Graph-Defined** | Nodes + edges | Koog, LangGraph |
| **Event-Driven** | Event handlers | MetaGPT |

### Key Insight

**Graph-based is NOT Pattern 9** - it's an orthogonal dimension. A graph-defined system still uses termination patterns:
- Koog's `maxAgentIterations` = Turn-Limited pattern
- LangGraph's `END` node = Finish Tool pattern
- Koog's edge conditions = State Machine pattern

**Implication**: Our loop patterns can be **nodes inside** graph-based frameworks. The paradigms are complementary.

### Failure Taxonomy (NEW)

Graph-defined execution surfaces a new failure class:

| Failure Class | Applies To | Example |
|---------------|-----------|---------|
| Resource exhaustion | All | Max turns exceeded |
| Semantic deadlock | All | Repetitive actions |
| **Graph topology** | **Graph-defined only** | Stuck node (no valid edge) |
| Tool failure | All | External API error |
| Model behavior | All | Safety refusal |

See:
- `learnings/TWO-AXIS-TAXONOMY-MODEL.md` - Full model explanation
- `learnings/KOOG-GRAPH-ARCHITECTURE-ANALYSIS.md` - Koog code analysis
- `learnings/AGENT-FAILURE-TAXONOMY.md` - Failure classification

---

## Source Documents Index

### Primary Research (Code-Verified)

| Document | Location | Purpose |
|----------|----------|---------|
| **Agent Loop Taxonomy** | `/home/mark/research/AGENT-LOOP-TAXONOMY.md` | 8 patterns from 25+ repos with code citations |
| **SDLC Agent Patterns** | `/home/mark/research/SDLC-AGENT-PATTERNS.md` | Task-to-pattern mapping for SDLC |
| **Related Work** | `/home/mark/research/RELATED-WORK.md` | Academic paper synthesis |
| **Google ADK Analysis** | `/home/mark/projects/agent-harness/plans/learnings/GOOGLE-ADK-JAVA-ANALYSIS.md` | Google ADK Java architecture |
| **Koog Graph Analysis** | `/home/mark/projects/agent-harness/plans/learnings/KOOG-GRAPH-ARCHITECTURE-ANALYSIS.md` | Graph-defined loop paradigm |
| **Two-Axis Model** | `/home/mark/projects/agent-harness/plans/learnings/TWO-AXIS-TAXONOMY-MODEL.md` | Orthogonal dimensions |
| **Failure Taxonomy** | `/home/mark/projects/agent-harness/plans/learnings/AGENT-FAILURE-TAXONOMY.md` | Failure classification |

### Learnings (Design Decisions)

| Document | Location | Purpose |
|----------|----------|---------|
| **Planning as First-Class** | `/home/mark/projects/agent-harness/plans/learnings/PLANNING-AS-FIRST-CLASS.md` | Planning dimension analysis |
| **OpenAGI vs GOAP** | `/home/mark/projects/agent-harness/plans/learnings/OPENAGI-VS-GOAP.md` | Why LLM planning differs from GOAP |
| **GOAP/Embabel Analysis** | `/home/mark/projects/agent-harness/plans/research/GOAP-EMBABEL-ANALYSIS.md` | Why GOAP is not for SDLC |
| **TUI Architecture** | `/home/mark/community/spring-ai-agents/plans/learnings/TUI-ARCHITECTURE-DECISION.md` | TUI separation rationale |
| **Experiment Tracker** | `/home/mark/projects/agent-harness/plans/learnings/EXPERIMENT-TRACKER-DECISION.md` | Tracking at outer loop |

### Academic Papers Analyzed

| Paper | Location | Key Contribution |
|-------|----------|------------------|
| **Plaat 2025** | `/home/mark/research/papers/plaat-2025/analysis.md` | Comprehensive survey, infinite loop problem |
| **Masterman 2024** | `/home/mark/research/papers/masterman-2024/analysis.md` | Architecture landscape, loop challenges |
| **ReAct (Yao 2022)** | `/home/mark/research/papers/react-2022/analysis.md` | finish[answer] pattern, step limits |
| **Reflexion (Shinn 2023)** | `/home/mark/research/papers/reflexion-2023/analysis.md` | Trial-based loop, evaluator-optimizer |
| **ToT (Yao 2023)** | `/home/mark/research/papers/tot-2023/analysis.md` | Tree search patterns |
| **Workflows 2024** | `/home/mark/research/papers/workflows-2024/analysis.md` | LMPR abstractions |

### Project Documents

| Document | Location | Purpose |
|----------|----------|---------|
| **agent-harness PLAN** | `/home/mark/projects/agent-harness/plans/PLAN.md` | Implementation roadmap |
| **agent-harness VISION** | `/home/mark/projects/agent-harness/plans/VISION.md` | Project goals, 8 patterns |
| **spring-ai-agents ROADMAP** | `/home/mark/community/spring-ai-agents/ROADMAP.md` | Planner component, orchestration |

---

## The 8 Documented Patterns

From 25+ repository analysis:

| # | Pattern | Representatives | Termination |
|---|---------|-----------------|-------------|
| 1 | **Turn-Limited Multi-Condition** | Gemini CLI, Swarm, SWE-Agent | max_turns + timeout + abort + finish_tool |
| 2 | **Finish Tool Detection** | Koog, Gemini (complete_task) | Agent calls specific tool |
| 3 | **Status-Based State Machine** | Embabel, OpenHands, Cerebrum | State transitions to terminal |
| 4 | **Pre-Planned Workflow** | OpenAGI, AgentVerse | All workflow steps complete |
| 5 | **Generator/Yield** | Auto-Code-Rover, LangGraph | Caller stops iterating |
| 6 | **Exception-Driven** | Aider | Error propagation |
| 7 | **Event-Driven Single-Step** | MetaGPT | Environment calls repeatedly |
| 8 | **Polling with Sleep** | Devika, Devon | Condition flag + sleep |

---

## Repositories Analyzed (Code-Verified)

| # | Repository | Language | Loop Pattern | File Path |
|---|------------|----------|--------------|-----------|
| 1 | Gemini CLI | TypeScript | Turn-limited | `packages/core/src/agents/executor.ts:394` |
| 2 | Koog | Kotlin | Finish tool | `agents/agents-ext/.../AIAgentSubtaskExt.kt:205` |
| 3 | Embabel | Kotlin | State machine | `embabel-agent-api/.../AbstractAgentProcess.kt:184` |
| 4 | Cerebrum | Python | Status polling | `cerebrum/agents/base_agent.py:203` |
| 5 | OpenAGI | Python | Workflow iteration | `pyopenagi/agents/react_agent.py:136` |
| 6 | OpenAI Swarm | Python | Turn-limited | `swarm/core.py:257` |
| 7 | OpenHands | Python | State machine | `openhands/core/loop.py:44` |
| 8 | MetaGPT | Python | Event-driven | `metagpt/roles/role.py:530` |
| 9 | LangGraph | Python | Generator | `langgraph/pregel/_runner.py:223` |
| 10 | Aider | Python | Exception-driven | `aider/main.py:1159` |
| 11 | SWE-Agent | Python | Interactive input | `sweagent/agent/models.py:398` |
| 12 | Auto-Code-Rover | Python | Generator | `app/agents/agent_search.py:122` |
| 13 | Devika | Python | Polling | `src/agents/agent.py:328` |
| 14 | Devon | Python | Retry loop | `devon_agent/agents/runner.py` |
| 15 | Claude Code | - | **UNKNOWN** | Closed source (inferred: turn-limited) |
| 16 | Google ADK Java | Java | Multi-agent orchestration | See analysis document |
| 17 | Koog | Kotlin | Graph-defined loop | `/tmp/koog/agents/agents-core/.../AIAgentSubgraph.kt:242` |

---

## Architecture Layers (Established)

```
┌─────────────────────────────────────────────────────────────────┐
│  APPLICATIONS (TUI, CLI, Web)                                    │
│  - spring-ai-agent-tui (future)                                  │
├─────────────────────────────────────────────────────────────────┤
│  ORCHESTRATION (AgentClient)                                     │
│  - Planner component (task decomposition)                        │
│  - Plan→Execute→Evaluate→Adapt cycle                             │
│  - Multi-agent coordination                                      │
├─────────────────────────────────────────────────────────────────┤
│  EXECUTION PATTERNS (agent-harness)                              │
│  - TurnLimitedLoop                                               │
│  - EvaluatorOptimizerLoop                                        │
│  - StateMachineLoop                                              │
│  (Single-agent inner loops)                                      │
├─────────────────────────────────────────────────────────────────┤
│  EVALUATION (spring-ai-judge)                                    │
│  - Judge/Jury framework                                          │
│  - Quality scoring                                               │
├─────────────────────────────────────────────────────────────────┤
│  FOUNDATION (Spring AI)                                          │
│  - ChatClient, ToolCallback, Advisors                            │
└─────────────────────────────────────────────────────────────────┘
```

---

## Key Findings Summary

### 1. Planning is a Dimension, Not a Pattern

Planning can be applied to ANY execution strategy:
- NullPlanner + TurnLimited = Current MiniAgent
- LlmPlanner + TurnLimited = Complex tasks with planning
- LlmPlanner + StateMachine = Workflow with explicit plan

**Decision**: Planning belongs in AgentClient (orchestration layer), not in loop patterns.

### 2. Two-Level Loop Architecture

| Level | Purpose | Example |
|-------|---------|---------|
| **Inner Loop** | Single agent executing turns | TurnLimitedLoop in agent-harness |
| **Outer Loop** | Multi-agent orchestration | LoopAgent in Google ADK, AgentClient |

### 3. GOAP is Not for SDLC Agents

- GOAP requires enumerable action space (won't work for "fix this bug")
- GOAP has deterministic effects (LLM effects are probabilistic)
- Production CLI agents (Claude Code, Gemini CLI, Aider) use Turn-Limited, not GOAP

### 4. Google ADK Validates Our Architecture

- ADK LoopAgent = our AgentClient orchestration
- ADK LlmAgent internal = our TurnLimitedLoop
- ADK's 8 multi-agent patterns complement our 8 loop patterns

### 5. Academic Papers Confirm Infinite Loop Problem

- ReAct: 47% of errors from repetitive loops
- CAMEL: Depth-limited to 40 messages
- Masterman: "agents may get stuck in endless execution loop"

---

## Potential Research Gaps to Validate

### 1. Newer Frameworks (Post-Dec 2025)

- Any major agent frameworks released 2025-2026?
- Updates to existing frameworks (LangGraph, AutoGen, CrewAI)?

### 2. Multi-Agent Patterns

We focused on single-agent loops. Multi-agent patterns to validate:
- Coordinator/Dispatcher (Google ADK AutoFlow)
- Parallel Fan-Out/Gather
- Hierarchical Decomposition
- Human-in-the-Loop gates

### 3. Streaming/Async Patterns

Pattern 5 (Generator/Yield) may need expansion:
- SSE-based streaming
- WebSocket bidirectional
- Long-polling patterns

### 4. Enterprise/Compliance Patterns

- Approval workflows
- Audit trail patterns
- Deterministic control flow requirements

### 5. Tool Orchestration Patterns

- MCP (Model Context Protocol) patterns
- Tool chaining strategies
- Fallback/retry patterns

---

## Suggested Research Queries

For validating completeness:

```
1. "agent loop" OR "agentic loop" 2025 2026 new framework
2. LangGraph update 2025 2026 patterns
3. AutoGen 0.3 0.4 loop patterns
4. CrewAI agent loop implementation
5. "multi-agent orchestration" patterns 2025
6. "Claude Code" loop architecture (any new analysis?)
7. OpenAI Swarm successor patterns
8. "agent termination" strategies taxonomy
9. "human-in-the-loop" agent patterns enterprise
10. MCP Model Context Protocol agent patterns
```

---

## Reference: External Sources Consulted

### Web Sources

- [Google ADK Documentation](https://google.github.io/adk-docs/)
- [Google ADK Java GitHub](https://github.com/google/adk-java)
- [Loop Agents Documentation](https://google.github.io/adk-docs/agents/workflow-agents/loop-agents/)
- [Multi-Agent Patterns Guide](https://developers.googleblog.com/developers-guide-to-multi-agent-patterns-in-adk/)

### Academic Sources

- arXiv:2503.23037 (Plaat 2025 - Agentic LLMs Survey)
- arXiv:2404.11584 (Masterman 2024 - Agent Architectures)
- ICLR 2023 (Yao - ReAct)
- NeurIPS 2023 (Shinn - Reflexion, Yao - ToT)

### Decompiled/Analyzed Sources

- `/home/mark/tuvium/claude-code-analysis/` - Claude Code CLI analysis

---

## Summary for Research Agent

**What we have**: Comprehensive analysis of 8 loop patterns across 25+ repos, 6 academic papers, architectural decisions documented.

**What to validate**:
1. Are there patterns 9, 10, 11 we missed?
2. Any new frameworks (2025-2026) with novel patterns?
3. Multi-agent orchestration patterns beyond what we documented?
4. Enterprise/compliance patterns we haven't covered?

**Goal**: Confirm our 8-pattern taxonomy is complete or identify gaps.

---

*This document was created to hand off research context to a research agent for validation.*
