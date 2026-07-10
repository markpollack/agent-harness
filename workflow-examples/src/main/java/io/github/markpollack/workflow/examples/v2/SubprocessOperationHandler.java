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
package io.github.markpollack.workflow.examples.v2;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.engine.ErrorEnvelope;
import io.github.markpollack.workflow.engine.OperationHandler;
import io.github.markpollack.workflow.engine.OperationInvocation;
import io.github.markpollack.workflow.engine.OperationResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A v2 {@link OperationHandler} that executes its work in a <strong>separate
 * process</strong> — the second, non-in-process implementation of the operation seam
 * (DD-18: an interface is not trusted until it has two implementations). The shipped
 * {@code StepOperationHandler} runs a {@code Step} in-process; this one shells out.
 *
 * <p>The handler passes the operation input as {@code $1} to a POSIX {@code sh -c}
 * script and returns the process's stdout as the operation output. A non-zero exit or
 * any failure normalizes to an {@link OperationResult#failure} with code
 * {@code SUBPROCESS_FAILED} (retryable) — the same normalization discipline the
 * interpreter's §19 boundary expects, done here as the handler's own job.
 *
 * <p>Idempotency of the external effect is the operation's obligation (the Operation
 * Contract) — this example's script is a pure function of its input, so it is trivially
 * idempotent.
 */
public final class SubprocessOperationHandler implements OperationHandler {

    /** Stable error code for a subprocess that failed; the exit code rides in details. */
    public static final String SUBPROCESS_FAILED = "SUBPROCESS_FAILED";

    private final String script;
    private final long timeoutSeconds;

    /**
     * @param script a POSIX shell script; the operation input is available as {@code $1}
     */
    public SubprocessOperationHandler(String script) {
        this(script, 30);
    }

    public SubprocessOperationHandler(String script, long timeoutSeconds) {
        this.script = script;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public OperationResult execute(OperationInvocation invocation, AgentContext context, Object input) {
        String arg = input == null ? "" : input.toString();
        try {
            Process process = new ProcessBuilder("sh", "-c", script, "op", arg)
                    .redirectErrorStream(false)
                    .start();
            byte[] stdout = process.getInputStream().readAllBytes();
            byte[] stderr = process.getErrorStream().readAllBytes();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return OperationResult.failure(ErrorEnvelope.of(SUBPROCESS_FAILED,
                        "subprocess timed out after " + timeoutSeconds + "s", true));
            }
            int exit = process.exitValue();
            if (exit != 0) {
                return OperationResult.failure(new ErrorEnvelope(SUBPROCESS_FAILED,
                        "subprocess exited " + exit + ": "
                                + new String(stderr, StandardCharsets.UTF_8).strip(),
                        true, ErrorEnvelope.ORIGIN_INFRA, Map.of("exitCode", exit)));
            }
            return OperationResult.success(new String(stdout, StandardCharsets.UTF_8).strip());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return OperationResult.aborted("interrupted running subprocess");
        } catch (Exception ex) {
            String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            return OperationResult.failure(new ErrorEnvelope(SUBPROCESS_FAILED, message, true,
                    ErrorEnvelope.ORIGIN_INFRA, Map.of("exceptionClass", ex.getClass().getName())));
        }
    }

    /** Convenience: is a POSIX shell available? Lets examples/tests skip gracefully. */
    public static boolean shellAvailable() {
        try {
            Process p = new ProcessBuilder("sh", "-c", "exit 0").start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }
}
