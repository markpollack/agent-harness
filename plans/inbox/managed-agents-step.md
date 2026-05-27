# Handoff: Add ManagedAgentStep to agent-workflow

> **Created**: 2026-05-27
> **Context**: Netflix call (Paul Bakker asked about Managed Agents), JetBrains slide deck (shown as step runtime), STRAT-23 (Koog comparison)
> **Target**: `~/projects/agent-workflow/` on `main` branch

## What to build

A `ManagedAgentStep` that delegates workflow steps to Anthropic's hosted Managed Agents API. This makes Managed Agents just another step runtime — the workflow graph stays in charge, but specific steps can run in Anthropic's cloud sandbox.

## Why

- Paul Bakker (Netflix) asked about Managed Agents as a compelling alternative to Spring AI
- The slide deck shows it as a step runtime alongside Temporal and local execution
- This proves the "workflow is portable, execution substrate is pluggable" thesis (STRAT-21 SCDF analogy)
- Koog doesn't have this — differentiator

## Pre-work

```bash
cd ~/projects/agent-workflow
git pull  # 6 commits behind — includes workflow-journal module, sub-workflow context, etc.
./mvnw compile -q  # verify clean state
```

## API Overview — Anthropic Managed Agents

Beta header: `managed-agents-2026-04-01` (set automatically by SDK on `client.beta()` calls).

**Core pattern:**
1. Create an Agent config (persisted, versioned): `POST /v1/agents`
2. Start a Session referencing that agent: `POST /v1/sessions`  
3. Stream session events: `GET /v1/sessions/{id}/stream`

Model, system prompt, tools, MCP servers, and skills are on the **Agent**, not the Session. Sessions reference a pre-created agent by ID.

**Java SDK usage:**
```java
// Create agent (do once, store the ID)
var agent = client.beta().agents().create(params);
String agentId = agent.id();

// Create session (per workflow execution)
var session = client.beta().sessions().create(sessionParams);

// Stream results
client.beta().sessions().stream(session.id());
```

## Documentation to read/scrape

The implementing session should read these URLs (use puppeteer MCP tool or WebFetch to scrape and save locally if needed):

### Must read
1. **Managed Agents Overview**: https://github.com/anthropics/skills/blob/main/skills/claude-api/shared/managed-agents-overview.md
2. **Managed Agents API Reference**: https://github.com/anthropics/skills/blob/main/skills/claude-api/shared/managed-agents-api-reference.md
3. **Managed Agents Core Concepts**: https://github.com/anthropics/skills/blob/main/skills/claude-api/shared/managed-agents-core.md
4. **Java Managed Agents README**: https://github.com/anthropics/skills/blob/main/skills/claude-api/java/managed-agents/README.md
5. **Anthropic Java SDK repo**: https://github.com/anthropics/anthropic-sdk-java

### Should read
6. **Managed Agents Onboarding**: https://github.com/anthropics/skills/blob/main/skills/claude-api/shared/managed-agents-onboarding.md
7. **Python Managed Agents README** (for comparison): https://github.com/anthropics/skills/blob/main/skills/claude-api/python/managed-agents/README.md
8. **Managed Agents Cookbooks**: https://github.com/anthropics/claude-cookbooks/tree/main/managed_agents
9. **Engineering blog — architecture**: https://www.anthropic.com/engineering/managed-agents
10. **API overview**: https://docs.anthropic.com/en/api/overview

### Reference
11. **Anthropic news — agent capabilities**: https://www.anthropic.com/news/agent-capabilities-api
12. **Claude API Skill definition**: https://github.com/anthropics/skills/blob/main/skills/claude-api/SKILL.md

## Implementation pattern

Follow the existing step patterns in `workflow-flows/src/main/java/io/github/markpollack/workflow/flows/steps/`:

### Template: A2AStep (closest match)
- File: `A2AStep.java`
- Pattern: delegates to remote agent, builder-style config, immutable instances
- Adapt: replace A2A protocol with Anthropic Managed Agents API

### Template: ClaudeStep (for reference)
- File: `ClaudeStep.java`  
- Pattern: full agentic loop, fluent builder, permission mode, MCP configs
- Adapt: ManagedAgentStep is simpler — API call, not subprocess

### Step interface
- File: `Step.java`
- Contract: `O execute(AgentContext ctx, I input)`
- Must implement: `Step<String, String>` and `AgentStep` (marker for cost tracking)

### Key design decisions

**DD-1: Agent creation vs reuse**
- Option A: Create agent config once in step constructor, reuse across executions
- Option B: Create agent per execution (wasteful, agents are persisted)
- **Recommend A**: Create agent in constructor or lazy-init, store agentId. Sessions are per-execution.

**DD-2: Session lifecycle**
- Create session on `execute()`, stream results, return final output
- Store session ID in AgentContext for debugging/tracing
- Don't maintain sessions across step executions — each step call is independent

**DD-3: Anthropic Java SDK dependency**
- Add `com.anthropic:anthropic-java` to `workflow-flows/pom.xml`
- Current version in spring-ai: 2.30.0
- Uses beta header `managed-agents-2026-04-01` automatically

**DD-4: Authentication**
- API key via `ANTHROPIC_API_KEY` env var (standard Anthropic SDK behavior)
- Or inject via builder: `.apiKey(key)`

## Suggested class structure

```java
// Location: workflow-flows/src/main/java/.../steps/ManagedAgentStep.java

public class ManagedAgentStep implements Step<String, String>, AgentStep {

    private final AnthropicClient client;
    private final String agentId;  // pre-created or lazy-created
    private final String name;
    private final Duration timeout;

    // Private constructor — use factory methods
    private ManagedAgentStep(AnthropicClient client, String agentId, String name, Duration timeout) { ... }

    // Factory: reference an existing agent by ID
    public static ManagedAgentStep of(String agentId) { ... }
    
    // Factory: create a new agent config inline
    public static ManagedAgentStep create(String model, String systemPrompt) { ... }

    // Builder-style config (immutable, returns new instance like A2AStep)
    public ManagedAgentStep name(String name) { ... }
    public ManagedAgentStep timeout(Duration timeout) { ... }
    public ManagedAgentStep apiKey(String apiKey) { ... }

    @Override
    public String execute(AgentContext ctx, String input) {
        // 1. Create session referencing this.agentId
        // 2. Send input as initial instruction
        // 3. Stream/poll for completion
        // 4. Return final text output
        // 5. Store session ID in ctx for tracing
    }
}
```

## Usage in a workflow

```java
var workflow = WorkflowGraph.builder()
    .step("analyze", Steps.deterministic(ctx -> analyzeCode(ctx)))
    .step("remediate", ManagedAgentStep.of(agentId)
        .name("managed-remediation")
        .timeout(Duration.ofMinutes(5)))
    .step("validate", Steps.deterministic(ctx -> runTests(ctx)))
    .build();
```

## Testing

- Unit test: mock the AnthropicClient, verify session create/stream calls
- Integration test: gated by `ANTHROPIC_API_KEY` env var, create a real agent + session with a trivial task
- Existing test patterns: see `A2AStepTest.java` and `ClaudeStepTest.java`

## What NOT to do

- Don't touch the polyglot V2 IR work — it's all in `plans/v2/`, zero code
- Don't modify the Step interface or StepRunner — ManagedAgentStep is just another Step implementation
- Don't add Spring dependencies to workflow-flows — keep it framework-agnostic
- Don't hardcode model or system prompt — those belong on the Agent config

## Verification

```bash
./mvnw compile -q           # compiles
./mvnw test                 # all existing tests pass + new ManagedAgentStep tests
```

Then demonstrate in a workflow:
```java
// Create agent once
var step = ManagedAgentStep.create("claude-sonnet-4-20250514", "You are a helpful assistant")
    .name("managed-test");

// Execute
String result = step.execute(ctx, "What is 2+2?");
assert result.contains("4");
```
