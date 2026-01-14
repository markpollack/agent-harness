# Spring AI Agent Architecture

## Key Insight

**Spring AI ChatClient + ToolCallAdvisor IS the agent loop.**

We are NOT building an agent loop from scratch. Spring AI already provides:
- LLM calling
- Tool execution
- Automatic tool result → LLM feedback loop
- Conversation memory via advisors

Our harness library adds **governance** on top:
- Invocation limits (max ChatClient calls)
- Finish tool detection
- Observability integration
- Jury evaluation
- Cost/token tracking

## Terminology

| Term | Definition |
|------|------------|
| **Invocation** | One call to `ChatClient.call()`. Spring AI handles tool execution internally. |
| **Tool Call** | Individual tool execution within an invocation. Observed via Micrometer. |
| **Tool Loop** | Spring AI's internal loop: call LLM → execute tools → repeat until done. |
| **Finish Tool** | A tool with `returnDirect=true` that signals task completion. |

## Architecture Layers

```
┌─────────────────────────────────────────────────────────────┐
│                    MiniAgent (User Code)                    │
│  - Task prompt                                              │
│  - Tool definitions                                         │
│  - Configuration (maxInvocations, costLimit, etc.)          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   InvocationLimitedLoop                     │
│  - Counts ChatClient invocations                            │
│  - Enforces limits (max invocations, timeout, cost)         │
│  - Detects finish tool                                      │
│  - Wires observability                                      │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│               Spring AI ChatClient + Advisors               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │              ToolCallAdvisor                        │    │
│  │  - Handles tool execution loop internally           │    │
│  │  - Calls LLM → executes tools → repeats             │    │
│  │  - Uses ToolCallingManager for execution            │    │
│  └─────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │           MessageChatMemoryAdvisor                  │    │
│  │  - Maintains conversation history                   │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  Micrometer Observability                   │
│  ┌─────────────────────────────────────────────────────┐    │
│  │         ToolCallObservationHandler                  │    │
│  │  - Receives callback for EACH tool call             │    │
│  │  - Logs: tool name, arguments, result               │    │
│  │  - Fires ToolCallListener events                    │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

## What Spring AI Handles (We Don't Build This)

1. **Tool Execution Loop**: `ToolCallAdvisor` loops until LLM stops calling tools
2. **Tool Callback Execution**: `ToolCallingManager` executes each tool
3. **Conversation History**: `MessageChatMemoryAdvisor` manages context
4. **Per-Tool Observability**: `DefaultToolCallingManager` wraps each tool in Micrometer

## What We Add

1. **Invocation Counting**: Track how many times we call ChatClient
2. **Limit Enforcement**: Stop after N invocations, timeout, or cost limit
3. **Finish Tool Detection**: Recognize when task is complete
4. **Observability Wiring**: Register ToolCallObservationHandler
5. **Jury Evaluation**: Optional scoring of agent output

## Configuration Mapping

| Config | Purpose |
|--------|---------|
| `maxInvocations` | Maximum ChatClient.call() invocations |
| `timeout` | Wall-clock time limit |
| `costLimit` | Maximum estimated cost |
| `finishToolName` | Tool that signals completion (e.g., "submit") |

## Why This is Simpler Than mini-swe-agent

Python mini-swe-agent builds the tool loop from scratch:
```python
while not done:
    response = model.chat(messages)
    if has_tool_calls(response):
        results = execute_tools(response)
        messages.append(results)
    else:
        done = True
```

Spring AI provides this automatically. Our code is just:
```java
ChatClient chatClient = /* configured with ToolCallAdvisor */;
chatClient.prompt()
    .user(task)
    .toolCallbacks(tools)
    .call();
// Spring AI handles the entire tool loop internally
```

## Observability via Micrometer

Spring AI's `DefaultToolCallingManager` wraps each tool execution:
```java
ToolCallingObservationDocumentation.TOOL_CALL
    .observe(() -> toolCallback.call(args, context));
```

We register a handler to receive these events:
```java
ObservationRegistry registry = ObservationRegistry.create();
registry.observationConfig()
    .observationHandler(new ToolCallObservationHandler(listeners));
```

This gives us per-tool visibility without modifying Spring AI.

## Implications for TurnLimitedLoop

Consider renaming to `InvocationLimitedLoop` or similar to reflect:
- We're not limiting "turns" in the traditional sense
- We're limiting ChatClient invocations
- Tool loop iterations happen within each invocation

## MiniAgent: The 100-Line Implementation

The `MiniAgent` class demonstrates this architecture in practice - a complete SWE agent in ~100 lines:

```java
public class MiniAgent {
    private final ChatClient chatClient;
    private final List<ToolCallback> tools;
    private final ToolCallObservationHandler observationHandler;

    public MiniAgent(MiniAgentConfig config, ChatModel model, ToolCallListener listener) {
        // Create tools
        var toolsObj = new MiniAgentTools(config.workingDirectory(), config.commandTimeout());
        this.tools = Arrays.asList(ToolCallbacks.from(toolsObj));

        // Wire observability: ObservationRegistry → ToolCallObservationHandler → ToolCallListener
        this.observationHandler = ToolCallObservationHandler.of(listener);
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(observationHandler);

        // Create ChatClient with ToolCallAdvisor - Spring AI handles the tool loop!
        var toolCallingManager = DefaultToolCallingManager.builder()
                .observationRegistry(registry)
                .build();
        var toolCallAdvisor = ToolCallAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .build();
        this.chatClient = ChatClient.builder(model)
                .defaultAdvisors(toolCallAdvisor)
                .build();
    }

    public MiniAgentResult run(String task) {
        ChatResponse response = chatClient.prompt()
                .user(config.systemPrompt() + "\n\nTask: " + task)
                .toolCallbacks(tools)  // List<ToolCallback> accepted directly
                .call()
                .chatResponse();
        // Spring AI handles entire tool loop internally!
        return new MiniAgentResult("COMPLETED", extractText(response), 1, extractTokens(response));
    }
}
```

**Key Points:**
- Constructor wires observability via Micrometer's `ObservationRegistry`
- `ToolCallAdvisor` + `DefaultToolCallingManager` handle the tool loop
- `run()` is a single ChatClient call - Spring AI loops until done
- Per-tool visibility via `ToolCallObservationHandler` → `ToolCallListener`

## Summary

Spring AI does the heavy lifting. We add governance, limits, and observability wiring. This makes our implementation MORE concise than Python alternatives because we leverage Spring AI's built-in agent loop.
