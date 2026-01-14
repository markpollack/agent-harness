# ChatClient Tool Call Observability Gap Analysis

## Problem Statement

The `TurnLimitedLoop` needs visibility into individual tool calls for:
- Logging and debugging
- Metrics and cost tracking
- Security auditing
- Progress reporting to users
- Detecting "finish tool" calls before execution

Currently, ChatClient uses internal tool execution by default, providing no visibility into which tools are called.

## Spring AI Architecture for Tool Calling

### Option 1: Internal Tool Execution (Default)
```java
chatClient.prompt()
    .user(message)
    .tools(tools)
    .call()           // Tools executed internally by ChatModel
    .chatResponse();  // Returns final response after all tools complete
```
**Problem**: No visibility into individual tool calls.

### Option 2: User-Controlled Tool Execution (ChatModel level)
```java
// Requires dropping to ChatModel
ToolCallingChatOptions options = ToolCallingChatOptions.builder()
    .internalToolExecutionEnabled(false)
    .build();

while (response.hasToolCalls()) {
    ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
    // We can observe tool calls here
    prompt = new Prompt(result.conversationHistory(), options);
    response = chatModel.call(prompt);
}
```
**Problem**: Loses ChatClient benefits (advisors, fluent API, interceptors).

### Option 3: ToolCallAdvisor (Recursive Advisor)
The new `ToolCallAdvisor` brings user-controlled tool execution to ChatClient!

```java
var toolCallAdvisor = ToolCallAdvisor.builder()
    .toolCallingManager(toolCallingManager)
    .build();

var chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(toolCallAdvisor)
    .build();
```

## ToolCallAdvisor Analysis

### How It Works (from source code)
1. Sets `internalToolExecutionEnabled(false)` (line 107)
2. Implements do-while loop until no tool calls (lines 115-163)
3. Executes tools via `ToolCallingManager.executeToolCalls()` (line 139-140)
4. Other advisors can intercept each iteration

### Extension Points (protected methods)
- `doInitializeLoop()` - Before loop starts
- `doBeforeCall()` - Before each model call
- `doAfterCall()` - After each model call (can observe ChatResponse with tool calls)
- `doFinalizeLoop()` - When loop ends
- `doGetNextInstructionsForToolCall()` - Gets instructions for next iteration

### **GAP IDENTIFIED**: Missing Hook Around Tool Execution

The actual tool execution in `adviseCall()`:
```java
// Line 139-140 - NO HOOK AVAILABLE
ToolExecutionResult toolExecutionResult = this.toolCallingManager
    .executeToolCalls(processedChatClientRequest.prompt(), chatClientResponse.chatResponse());
```

There's no `doBeforeToolExecution()` or `doAfterToolExecution()` hook!

## Proposed Solutions

### Solution A: Extend ToolCallAdvisor (Copy adviseCall)
Create `ObservableToolCallAdvisor extends ToolCallAdvisor` and override `adviseCall()` to add hooks around tool execution.

**Pros**: Works today, no Spring AI changes needed
**Cons**: Copies ~70 lines of code, maintenance burden

### Solution B: Observable ToolCallingManager Wrapper
Create a wrapper around `ToolCallingManager` that notifies listeners:

```java
public class ObservableToolCallingManager implements ToolCallingManager {
    private final ToolCallingManager delegate;
    private final List<ToolCallListener> listeners;

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        var toolCalls = response.getResult().getOutput().getToolCalls();
        notifyBeforeExecution(toolCalls);

        ToolExecutionResult result = delegate.executeToolCalls(prompt, response);

        notifyAfterExecution(toolCalls, result);
        return result;
    }
}
```

**Pros**: Clean separation, works with any advisor
**Cons**: Doesn't provide per-tool execution timing

### Solution C: Spring AI Enhancement (Recommended)
Add extension points to `ToolCallAdvisor`:

```java
// New protected methods in ToolCallAdvisor
protected void doBeforeToolExecution(
    ChatClientRequest request,
    ChatResponse response,
    List<AssistantMessage.ToolCall> toolCalls) {
}

protected void doAfterToolExecution(
    ChatClientRequest request,
    ChatResponse response,
    ToolExecutionResult result) {
}
```

Then in `adviseCall()`:
```java
if (isToolCall) {
    var toolCalls = chatClientResponse.chatResponse().getResult().getOutput().getToolCalls();

    this.doBeforeToolExecution(processedChatClientRequest, chatClientResponse, toolCalls);

    ToolExecutionResult toolExecutionResult = this.toolCallingManager
        .executeToolCalls(processedChatClientRequest.prompt(), chatClientResponse.chatResponse());

    this.doAfterToolExecution(processedChatClientRequest, chatClientResponse, toolExecutionResult);
    // ... rest of code
}
```

**Pros**: Clean API, proper extension point, follows existing pattern
**Cons**: Requires Spring AI PR

### Solution D: Custom Advisor Before ToolCallAdvisor
Create an advisor that runs BEFORE `ToolCallAdvisor` (higher order number) to observe the ChatResponse:

```java
public class ToolCallObservabilityAdvisor implements CallAdvisor {
    @Override
    public int getOrder() {
        return BaseAdvisor.HIGHEST_PRECEDENCE + 400; // After ToolCallAdvisor (300)
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);

        if (response.chatResponse() != null && response.chatResponse().hasToolCalls()) {
            var toolCalls = response.chatResponse().getResult().getOutput().getToolCalls();
            notifyToolCallsDetected(toolCalls);
        }

        return response;
    }
}
```

**Pros**: Works today, no Spring AI changes needed
**Cons**: Only sees tool calls BEFORE execution, not after. Runs on each iteration.

## DISCOVERY: Spring AI Already Has Per-Tool Micrometer Observability!

`DefaultToolCallingManager` wraps each tool execution in a Micrometer observation:

```java
// Line 238 in DefaultToolCallingManager.java
String toolCallResult = ToolCallingObservationDocumentation.TOOL_CALL
    .observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION,
                 () -> observationContext, this.observationRegistry)
    .observe(() -> {
        String toolResult = toolCallback.call(finalToolInputArguments, toolContext);
        // ...
    });
```

The `ToolCallingObservationContext` provides:
- `toolDefinition` - which tool is being called
- `toolMetadata` - metadata about the tool
- `toolCallArguments` - the arguments passed
- `toolCallResult` - the result (set after execution)

**This means we can observe ALL tool calls by simply registering a Micrometer `ObservationHandler`!**

### Solution: Micrometer ObservationHandler (BEST APPROACH)

```java
public class ToolCallObservationHandler implements ObservationHandler<ToolCallingObservationContext> {

    private final List<ToolCallListener> listeners;

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ToolCallingObservationContext;
    }

    @Override
    public void onStart(ToolCallingObservationContext context) {
        // Tool execution starting
        listeners.forEach(l -> l.onToolExecutionStarted(
            context.getToolDefinition().name(),
            context.getToolCallArguments()
        ));
    }

    @Override
    public void onStop(ToolCallingObservationContext context) {
        // Tool execution completed
        listeners.forEach(l -> l.onToolExecutionCompleted(
            context.getToolDefinition().name(),
            context.getToolCallArguments(),
            context.getToolCallResult()
        ));
    }

    @Override
    public void onError(ToolCallingObservationContext context) {
        // Tool execution failed
        listeners.forEach(l -> l.onToolExecutionFailed(
            context.getToolDefinition().name(),
            context.getError()
        ));
    }
}
```

**Pros**:
- No subclassing, no AOP, no wrappers
- Uses Spring AI's built-in observability
- Works with any tool execution (ToolCallAdvisor or internal)
- Clean separation of concerns

**Cons**:
- Need to ensure ObservationRegistry is properly configured
- Micrometer dependency

## Key Insight from Spring AI Blog

From the [Spring AI Recursive Advisors blog post](https://spring.io/blog/2025/11/04/spring-ai-recursive-advisors):

> "The ToolCallAdvisor enables other advisors (such as Advisor ABC) to intercept and alter each tool call request and response."

This means **Solution D is the intended pattern**! When ToolCallAdvisor loops, other advisors in the chain see each iteration. The key is advisor ordering:

```
Request flow (low order to high):
  [Our Observer Advisor (200)] → [ToolCallAdvisor (300)] → [Model Call]

Response flow (high order to low):
  [Model Call] → [ToolCallAdvisor (300)] → [Our Observer Advisor (200)]
```

By placing our observer advisor BEFORE ToolCallAdvisor (lower order number), we see:
- Each ChatResponse before tools are executed
- The final response after all tool iterations complete

## Recommendation

**PRIMARY (BEST)**: Use **Micrometer ObservationHandler**
- Register a custom `ObservationHandler<ToolCallingObservationContext>`
- Receives callbacks for EVERY tool execution (start, stop, error)
- No code changes to Spring AI needed
- Works with both internal tool execution AND ToolCallAdvisor
- Clean, idiomatic Spring approach

**Supplementary**: Use **Solution D** (Observer Advisor) for loop-level tracking
- Track iteration counts, detect finish tool before execution
- Combine with Micrometer for complete picture

**NOT NEEDED**: Subclassing, AOP, or wrappers
- Spring AI already provides the hooks we need via Micrometer

## Implementation for TurnLimitedLoop

### Step 1: Create Tool Call Observation Handler

```java
public class ToolCallObservationHandler
        implements ObservationHandler<ToolCallingObservationContext> {

    private final List<ToolCallListener> listeners;

    public ToolCallObservationHandler(List<ToolCallListener> listeners) {
        this.listeners = listeners;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ToolCallingObservationContext;
    }

    @Override
    public void onStart(ToolCallingObservationContext context) {
        var toolName = context.getToolDefinition().name();
        var args = context.getToolCallArguments();
        listeners.forEach(l -> l.onToolExecutionStarted(runId, turn, toolName, args));
    }

    @Override
    public void onStop(ToolCallingObservationContext context) {
        var toolName = context.getToolDefinition().name();
        var result = context.getToolCallResult();
        listeners.forEach(l -> l.onToolExecutionCompleted(runId, turn, toolName, result));
    }

    @Override
    public void onError(ToolCallingObservationContext context) {
        var toolName = context.getToolDefinition().name();
        listeners.forEach(l -> l.onToolExecutionFailed(runId, turn, toolName, context.getError()));
    }
}
```

### Step 2: Configure In-Memory ObservationRegistry

```java
// Create in-memory observation registry with our handler
ObservationRegistry observationRegistry = ObservationRegistry.create();
observationRegistry.observationConfig()
    .observationHandler(new ToolCallObservationHandler(toolCallListeners));

// Create ToolCallingManager with the registry
ToolCallingManager toolCallingManager = DefaultToolCallingManager.builder()
    .observationRegistry(observationRegistry)
    .build();
```

### Step 3: Configure ChatClient with ToolCallAdvisor

```java
// Create ToolCallAdvisor with our observable tool calling manager
var toolCallAdvisor = ToolCallAdvisor.builder()
    .toolCallingManager(toolCallingManager)
    .build();

// Build ChatClient with the advisor
var chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(toolCallAdvisor)
    .build();

// TurnLimitedLoop uses this chatClient - now with full per-tool observability!
```

### Spring Boot Alternative

In a Spring Boot app, the `ObservationRegistry` is auto-configured:

```java
@Bean
public ObservationHandler<ToolCallingObservationContext> toolCallObservationHandler(
        List<ToolCallListener> listeners) {
    return new ToolCallObservationHandler(listeners);
}
```

## Files to Create

1. `ToolCallObservationHandler` - Micrometer handler that bridges to ToolCallListener
2. Update `TurnLimitedLoop` to:
   - Accept ObservationRegistry in builder
   - Configure ToolCallAdvisor with observable ToolCallingManager
3. Tests for tool call observability

## Summary

**No gaps in ChatClient!** Spring AI already provides the observability hooks we need:
- `ToolCallAdvisor` brings user-controlled tool execution to ChatClient
- `DefaultToolCallingManager` has built-in Micrometer observability per tool
- We just need to register our `ObservationHandler` to receive callbacks

The harness library can stay with ChatClient and benefit from all its features (advisors, fluent API, interceptors) while still having full tool call observability.

## References

- [Spring AI Recursive Advisors](https://docs.spring.io/spring-ai/reference/2.0/api/advisors-recursive.html)
- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- `ToolCallAdvisor.java` - `/spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/advisor/ToolCallAdvisor.java`
