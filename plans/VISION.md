# Vision: spring-ai-agent-harnesses

## What We're Building

A foundation library implementing all 8 agentic loop patterns discovered in academic research, with focus on SDLC/coding agent systems like Claude CLI.

**Key Insight**: Different loop patterns = different judge configurations (Judge-centric architecture)

---

## The 8 Patterns

| # | Pattern | Used By | Description |
|---|---------|---------|-------------|
| 1 | Turn-Limited Multi-Condition | Claude CLI, Gemini CLI, SWE-Agent | Fixed turn budget with multiple termination conditions |
| 2 | Evaluator-Optimizer | Reflexion, mcp-agent, sdk-sync-agent | Generate → Evaluate → Reflect → Retry |
| 3 | Status-Based State Machine | OpenHands, Embabel, AgentLite | Explicit states with transition rules |
| 4 | Finish Tool Detection | ReAct, LangChain | Terminate when agent calls finish tool |
| 5 | Pre-Planned Workflow | AutoGPT+P, AgentVerse, PDDL agents | Plan first, then execute steps |
| 6 | Generator/Yield | LATS, async streaming | Streaming/async iteration |
| 7 | Exception-Driven | mini-swe-agent | Error handling drives control flow |
| 8 | Polling with Sleep | Background monitoring | Periodic check with backoff |

Note: Patterns often combine (e.g., mini-swe-agent = Pattern 1 + Pattern 7)

**Academic Foundation** (see `~/research/AGENT-LOOP-TAXONOMY.md`):
- ReAct [Yao et al. 2022] - Pattern 4 (finish tool)
- Reflexion [Shinn et al. 2023] - Pattern 2 (evaluator-optimizer)
- Tree of Thoughts [Yao et al. 2023] - Pattern 5 (pre-planned)
- Plaat et al. 2025 Survey - Documents infinite loop problem we solve
- Masterman et al. 2024 - Architectural taxonomy complementing our patterns

**Spring AI Implementation Note** (see `~/research/appendix/SPRING-AI-TURN-SEMANTICS.md`):
Spring AI's `ToolCallAdvisor` handles the internal tool loop. What the taxonomy calls "turns" happens WITHIN a single `ChatClient.call()`. Our governance (limits, timeout) operates at the invocation level.

---

## Design Principles

1. **Synchronous API**: User-facing APIs return results directly (no `Mono<>`)
2. **Config at Construction**: Builder pattern, config provided once at construction
3. **Bounded Generics**: `AgentLoop<R extends LoopResult>` for type-safe results
4. **No Adapters**: Direct Spring AI integration
5. **Judge-Centric**: Termination logic encapsulated in judges/juries

---

## Technology Stack

- **Java 21** with records, sealed interfaces, pattern matching, virtual threads
- **Maven** multi-module project
- **Spring AI 2.0** (ChatClient, ToolCallback, Advisors)
- **spring-ai-agents-judge** for evaluation framework

---

## Success Criteria

1. [x] TOP 3 patterns implemented with termination strategies
2. [x] W&B-lite observability with Snapshot export
3. [x] Spring AI direct integration (no adapters)
4. [x] Synchronous API (no Reactor in user-facing code)
5. [x] Simplified generics with bounded type parameters
6. [x] MiniAgent example ~100 lines (101 lines - 21 fewer than Python mini-swe-agent)
7. [ ] All 8 patterns implemented
8. [ ] Spring Boot auto-configuration
9. [ ] Full SDLC tools suite

---

## Reference Materials

### Knowledge Bank (`~/research/`)
Machine-level research and background info for iterative development:
- `AGENT-LOOP-TAXONOMY.md` - Pattern classification research (8 patterns from 25+ repos)
- `appendix/SPRING-AI-TURN-SEMANTICS.md` - **Critical**: Turn semantics in Spring AI vs taxonomy
- `papers/` - Academic papers on agentic systems
- `oss-analysis/` - Open source agent analysis

### Source Repositories (`~/community/`)
Source code for inspiration and reference:
- `sdk-sync-agent` - Observability, tracking patterns
- `spring-ai-agents` - Judge/Jury framework
- `spring-ai-tool-search-tool` - Tool augmentation

### Project-Level (`plans/supporting_repos/`)
Project-specific references (symlinks to ~/community/ or clones):
- `mini-swe-agent` - Pattern 7 reference

### Analysis
- `/home/mark/tuvium/claude-code-analysis` - Claude CLI reverse engineering

### Quick Start
```bash
cd ~/tuvium/spring-ai-agent-harnesses
mvn clean compile
mvn test
```

---

*See PLAN.md for current implementation status and next steps.*
