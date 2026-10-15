package org.shadowice.flocke.andotp.Activities;

import android.app.Fragment;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import org.shadowice.flocke.andotp.Tasks.UiBasedBackgroundTask;
import org.shadowice.flocke.andotp.Tasks.TaskOutcome;
import org.shadowice.flocke.andotp.Tasks.TaskFailure;

/**
 * Base activity for screens that run one {@link UiBasedBackgroundTask} through a retained task
 * fragment, so a running task survives configuration changes.
 */
public abstract class BackgroundTaskActivity<Result> extends BaseActivity {
    final protected String TAG_TASK_FRAGMENT = this.getClass().getSimpleName() + ".TaskFragment";

    private ProcessLifecycleObserver observer = null;
    private UiBasedBackgroundTask<Result> activeTask;
    private TaskFragment<Result> activeFragment;

    /** Called with the result of a finished task; the UI is already back to its idle state. */
    abstract void onTaskResult(Result result);

    protected void onTaskFailure(TaskFailure error) {
        onReturnToCanceledTask();
    }

    protected void onReturnToCanceledTask() {
        // Override to react when the user comes back to a canceled task
    }

    protected void setupUiForTaskState(boolean running) {
        // Override to reflect the running state in the UI
    }

    /** True to cancel the running task when the app goes to the background. */
    protected boolean cancelTaskOnScreenOff() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (cancelTaskOnScreenOff()) {
            observer = new ProcessLifecycleObserver();

            ProcessLifecycleOwner.get().getLifecycle()
                    .addObserver(observer);
        }
    }

    @Override
    protected void onDestroy() {
        if (observer != null) {
            ProcessLifecycleOwner.get().getLifecycle()
                    .removeObserver(observer);

            observer = null;
        }

        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isFinishing()) checkBackgroundTask();
    }

    @Override
    protected void onPause() {
        super.onPause();

        // We don't want the task to callback to a dead activity and cause a memory leak, so null it here.
        TaskFragment<Result> taskFragment = findTaskFragment();

        if (taskFragment != null)
            taskFragment.setOutcomeCallback(null);
    }

    /** Cancels the running task (if any) and restores the idle UI. */
    protected void cancelBackgroundTask() {
        TaskFragment<Result> taskFragment = findTaskFragment();

        if (taskFragment != null)
            taskFragment.cancelTask();

        setupUiForTaskState(false);
    }

    /** Hands the task to the retained fragment and switches to the running UI. */
    protected void startBackgroundTask(UiBasedBackgroundTask<Result> task) {
        TaskFragment<Result> fragment = findTaskFragment();
        if (fragment != null && !fragment.isCanceled()) {
            task.setOutcomeCallback(null);
            task.cancel();
            return;
        }
        if (fragment == null) {
            fragment = new TaskFragment<>();
            activeFragment = fragment;
            getFragmentManager().beginTransaction().add(fragment, TAG_TASK_FRAGMENT).commit();
        } else {
            fragment.setOutcomeCallback(null);
        }
        activeTask = task;
        task.setOutcomeCallback(outcome -> handleTaskOutcome(task, outcome));
        fragment.startTask(task);
        setupUiForTaskState(true);
    }

    private void handleTaskOutcome(UiBasedBackgroundTask<Result> source, TaskOutcome<Result> outcome) {
        if (source != activeTask) {
            source.disposeOutcome(outcome);
            return;
        }
        TaskFragment<Result> fragment = findTaskFragment();
        activeTask = null;
        activeFragment = null;
        if (fragment != null && fragment.task == source) {
            fragment.task = null;
            getFragmentManager().beginTransaction().remove(fragment).commit();
        }
        setupUiForTaskState(false);
        if (outcome instanceof TaskOutcome.Value)
            onTaskResult(((TaskOutcome.Value<Result>) outcome).getValue());
        else if (outcome instanceof TaskOutcome.Failure)
            onTaskFailure(((TaskOutcome.Failure<Result>) outcome).getError());
        else
            onReturnToCanceledTask();
    }

    /** Rebind the same task on resume, including a retained cancellation outcome. */
    private void checkBackgroundTask() {
        TaskFragment<Result> fragment = findTaskFragment();
        if (fragment == null) { setupUiForTaskState(false); return; }
        activeFragment = fragment;
        activeTask = fragment.task;
        UiBasedBackgroundTask<Result> source = activeTask;
        fragment.setOutcomeCallback(outcome -> handleTaskOutcome(source, outcome));
        setupUiForTaskState(!fragment.isCanceled());
    }

    /** The retained task fragment, or null if none is attached. */
    @Nullable
    @SuppressWarnings("unchecked")
    private TaskFragment<Result> findTaskFragment() {
        if (activeFragment != null && activeFragment.task != null) return activeFragment;
        Fragment fragment = getFragmentManager().findFragmentByTag(TAG_TASK_FRAGMENT);

        if (fragment instanceof TaskFragment && ((TaskFragment<?>) fragment).task != null)
            return (TaskFragment<Result>) fragment;
        else
            return null;
    }

    /** Cancels the running task when the app goes to the background (if the activity asks for it). */
    private class ProcessLifecycleObserver implements DefaultLifecycleObserver {
        @Override
        public void onStop(@NonNull LifecycleOwner owner) {
            if (cancelTaskOnScreenOff())
                cancelBackgroundTask();
        }
    }

    /** Retained fragment that keeps the running task alive across configuration changes. */
    public static class TaskFragment<Result> extends Fragment {
        private UiBasedBackgroundTask<Result> task;

        public TaskFragment() {
            super();
            setRetainInstance(true);
        }

        public void startTask(@NonNull UiBasedBackgroundTask<Result> task) {
            this.task = task;
            task.execute();
        }

        public void setOutcomeCallback(@Nullable UiBasedBackgroundTask.OutcomeCallback<Result> callback) {
            if (this.task != null)
                this.task.setOutcomeCallback(callback);
        }

        @Override public void onDestroy() {
            if (task != null) { task.setOutcomeCallback(null); task.cancel(); }
            super.onDestroy();
        }

        public boolean isCanceled() {
            if (task != null)
                return task.isCanceled();
            else
                return true;
        }

        public void cancelTask() {
            if (task != null)
                task.cancel();
        }
    }
}
