# Step 4 Learnings: MiniAgent Implementation

## Summary

Implemented a 101-line Java SWE agent that's 21 lines shorter than Python's mini-swe-agent (122 lines) because Spring AI's ChatClient + ToolCallAdvisor handles the tool execution loop internally.

## Key Insight

**Spring AI ChatClient + ToolCallAdvisor IS the agent loop.**

We don't build an agent loop from scratch. Spring AI provides:
- LLM calling
- Tool execution
- Automatic tool result → LLM feedback loop

Our MiniAgent just adds:
- Tool definitions (bash, submit)
- Observability wiring
- Simple configuration

## Observability Pattern

The breakthrough was using Micrometer's ObservationRegistry to get per-tool visibility without dropping from ChatClient to ChatModel.

```java
// 1. Create observation handler that bridges to our listener
var observationHandler = ToolCallObservationHandler.of(listener);

// 2. Create in-memory observation registry
var registry = ObservationRegistry.create();
registry.observationConfig().observationHandler(observationHandler);

// 3. Create tool calling manager with the registry
var toolCallingManager = DefaultToolCallingManager.builder()
        .observationRegistry(registry)
        .build();

// 4. Create ToolCallAdvisor with the manager
var toolCallAdvisor = ToolCallAdvisor.builder()
        .toolCallingManager(toolCallingManager)
        .build();

// 5. Build ChatClient with the advisor
var chatClient = ChatClient.builder(model)
        .defaultAdvisors(toolCallAdvisor)
        .build();
```

This gives us callbacks for every tool execution (start, stop, error) without modifying Spring AI.

## Line Count Comparison

| Component | mini-swe-agent (Python) | MiniAgent (Java) |
|-----------|-------------------------|------------------|
| Core agent | 122 | **101** |
| Config/env | 38 | 114 |
| Model/tools | 100 | 111 |
| **Total** | **260** | **326** |

Java is more verbose overall (type declarations, builders), but the core agent is more concise.

## Files Created

| File | Lines | Purpose |
|------|-------|---------|
| `MiniAgent.java` | 101 | Core agent using ChatClient |
| `MiniAgentConfig.java` | 114 | Builder-based configuration |
| `MiniAgentTools.java` | 111 | Bash and submit tools |
| `LoggingToolCallListener.java` | 41 | INFO-level tool call logging |

## Testing Approach

1. **Unit tests** with `DeterministicChatModel` - mock that returns predetermined responses
2. **Integration tests** with real Anthropic API (`@EnabledIfEnvironmentVariable`)
3. **API key validation** in `@BeforeAll` with clear error messages

## Design Decisions

### ChatModel vs ChatClient

MiniAgent takes `ChatModel` (not `ChatClient`) because:
- Allows internal wiring of ToolCallAdvisor with observability
- Simpler API for users
- ChatClient is built internally with proper configuration

### Tool Registration

Used `ToolCallbacks.from(toolsObject)` - the simplest API:
```java
var toolsObj = new MiniAgentTools(config.workingDirectory(), config.commandTimeout());
this.tools = Arrays.asList(ToolCallbacks.from(toolsObj));
```

### Finish Tool

The `submit` tool uses `returnDirect=true` - when called, Spring AI returns immediately without another LLM call.

## What We Learned

1. **Don't rebuild what Spring AI provides** - The tool loop is handled internally
2. **Micrometer is the observability hook** - DefaultToolCallingManager already wraps tools in observations
3. **ToolCallAdvisor is the key** - It brings user-controlled tool execution to ChatClient
4. **Java verbosity is in config, not logic** - The actual agent logic is very concise

## Next Steps

- Step 5: Observability Integration (build on ToolCallListener pattern)
- Step 6: Core File Tools (ReadTool, WriteTool, EditTool, etc.)
