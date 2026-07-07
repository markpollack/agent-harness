package io.github.markpollack.workflow.spec;

import java.io.OutputStream;

/**
 * Emits canonical form (DD-15): RFC 8785 JCS bytes plus the project content rules
 * (no nulls — absent fields omitted; arrays preserve declaration order; maps sorted
 * by JCS key ordering).
 *
 * <p>Round-trip law: {@code write(read(j))} is canonically byte-equal to
 * {@code canonicalize(j)} for every valid spec {@code j}. Cross-SDK equivalence tests
 * compare exactly these bytes.
 */
public interface WorkflowSpecWriter {

    void write(WorkflowSpec spec, OutputStream out);
}
