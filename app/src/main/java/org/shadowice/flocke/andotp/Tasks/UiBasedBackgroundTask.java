package org.shadowice.flocke.andotp.Tasks;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.shadowice.flocke.andotp.Utilities.VaultSession;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Android scheduling adapter over the Kotlin task state/outcome contract. */
public abstract class UiBasedBackgroundTask<Result> {
    private final Result failedResult;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    private final Object callbackLock = new Object();
    private final TaskController controller = new TaskController(VaultSession.generation());
    private OutcomeCallback<Result> callback;
    private TaskOutcome<Result> pending;
    private boolean deliveryPosted;
    private Future<?> future;
    private boolean cancellationCleaned;
    private final Runnable sessionChanged = () -> { if (isCanceled()) cancelFor(TaskCancellation.SESSION_REVOKED); };

    public UiBasedBackgroundTask(@NonNull Result failedResult) { this.failedResult = failedResult; }
    protected final long taskGeneration() { return controller.getGeneration(); }
    public TaskState getState() { isCanceled(); return controller.snapshot(); }

    /** Legacy callback adapter. New clients must handle Failure/Cancelled explicitly. */
    public void setCallback(@Nullable UiCallback<Result> callback) {
        if (callback == null) { setOutcomeCallback(null); return; }
        setOutcomeCallback(outcome -> {
            if (outcome instanceof TaskOutcome.Value) callback.onResult(((TaskOutcome.Value<Result>) outcome).getValue());
            else if (outcome instanceof TaskOutcome.Failure) callback.onResult(failedResult);
        });
    }
    public void setOutcomeCallback(@Nullable OutcomeCallback<Result> callback) {
        synchronized (callbackLock) {
            if (controller.deliveryConsumed()) return;
            this.callback = callback;
            scheduleDelivery();
        }
    }
    private void scheduleDelivery() {
        // Called under callbackLock. The queue captures this task, not a result or Activity.
        if (pending != null && callback != null && !deliveryPosted) {
            deliveryPosted = true;
            mainThreadHandler.post(this::deliverPending);
        }
    }
    private TaskOutcome<Result> cancellation() {
        TaskState state = controller.snapshot();
        TaskCancellation reason = state instanceof TaskState.Cancelled
                ? ((TaskState.Cancelled) state).getReason() : TaskCancellation.SESSION_REVOKED;
        return new TaskOutcome.Cancelled<>(reason);
    }
    private void deliverPending() {
        // Delivery and activation/revocation share the mutation lock. Never acquire it under callbackLock.
        synchronized (VaultSession.MutationLock) {
            OutcomeCallback<Result> target;
            TaskOutcome<Result> outcome;
            synchronized (callbackLock) {
                deliveryPosted = false;
                if (isCanceled() && pending != null && !(pending instanceof TaskOutcome.Cancelled)) {
                    disposeOutcome(pending);
                    pending = cancellation();
                }
                if (pending == null || callback == null) return;
                outcome = pending;
                pending = null;
                if (!controller.claimDelivery()) { disposeOutcome(outcome); callback = null; return; }
                VaultSession.removeChangeListener(sessionChanged);
                target = callback;
                callback = null;
            }
            target.onOutcome(outcome);
        }
    }

    @AnyThread public void execute() {
        synchronized (callbackLock) {
            isCanceled();
            TaskStart start = controller.start();
            if (start == TaskStart.ALREADY_SUBMITTED) throw new IllegalStateException("Task already submitted");
            if (start == TaskStart.CANCELED) {
                executor.shutdownNow();
                cleanupCancellation();
                discardResult(failedResult);
                pending = cancellation();
                scheduleDelivery();
                return;
            }
            VaultSession.addChangeListener(sessionChanged);
            future = executor.submit(this::runTask);
        }
    }
    private void runTask() {
        TaskOutcome<Result> outcome;
        try {
            if (isCanceled()) outcome = cancellation();
            else {
                Result result = doInBackground();
                outcome = result == null ? new TaskOutcome.Failure<>(TaskFailure.NULL_RESULT) : new TaskOutcome.Value<>(result);
            }
        } catch (Exception error) {
            if (isCanceled()) outcome = cancellation();
            else {
                Log.e("UiBasedBackgroundTask", "Background operation failed");
                outcome = new TaskOutcome.Failure<>(TaskFailure.UNEXPECTED_EXCEPTION);
            }
        } finally { executor.shutdown(); }
        synchronized (callbackLock) {
            if (isCanceled()) {
                VaultSession.removeChangeListener(sessionChanged);
                disposeOutcome(outcome);
                // Cancellation may have already been delivered while work was still unwinding.
                pending = controller.deliveryConsumed() ? null : cancellation();
            } else {
                TaskCompletion completion = outcome instanceof TaskOutcome.Value ? TaskCompletion.VALUE : TaskCompletion.FAILURE;
                if (!controller.complete(completion)) { disposeOutcome(outcome); return; }
                pending = outcome;
            }
            scheduleDelivery();
        }
    }

    @NonNull protected abstract Result doInBackground() throws Exception;
    /** Idempotent owned-input cleanup; no external I/O or MutationLock acquisition. */
    protected void onCancellation() { }
    private void cleanupCancellation() {
        if (!cancellationCleaned) { cancellationCleaned = true; onCancellation(); }
    }
    protected void discardResult(Result result) { }
    public final void disposeOutcome(TaskOutcome<Result> outcome) {
        if (outcome instanceof TaskOutcome.Value) discardResult(((TaskOutcome.Value<Result>) outcome).getValue());
    }
    protected final boolean wasCanceled() {
        TaskState state = controller.snapshot();
        return state instanceof TaskState.Cancelled && ((TaskState.Cancelled) state).getReason() == TaskCancellation.EXPLICIT;
    }
    @AnyThread public boolean isCanceled() {
        controller.observeGeneration(VaultSession.generation());
        return controller.snapshot() instanceof TaskState.Cancelled;
    }
    @AnyThread public void cancel() { cancelFor(TaskCancellation.EXPLICIT); }
    private void cancelFor(TaskCancellation reason) {
        synchronized (callbackLock) {
            controller.cancel(reason);
            if (!(controller.snapshot() instanceof TaskState.Cancelled)) return;
            VaultSession.removeChangeListener(sessionChanged);
            if (future != null) future.cancel(true);
            executor.shutdownNow();
            cleanupCancellation();
            if (pending != null) disposeOutcome(pending);
            pending = cancellation();
            scheduleDelivery();
        }
    }

    @FunctionalInterface public interface OutcomeCallback<Result> { @MainThread void onOutcome(@NonNull TaskOutcome<Result> outcome); }
    @FunctionalInterface public interface UiCallback<Result> { @MainThread void onResult(@NonNull Result result); }
}
