package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.workflow.WorkflowHandle;

/** Await a child workflow handle, unwrapping its checked exception type to unchecked. */
final class WorkflowHandles {

    private WorkflowHandles() {
    }

    static <T> T await(WorkflowHandle<T, ? extends Exception> handle) {
        try {
            return handle.getResult();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
