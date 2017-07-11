/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.execution.taskgraph;

import com.google.common.collect.Maps;
import org.gradle.api.Action;
import org.gradle.api.Transformer;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.concurrent.ManagedExecutor;
import org.gradle.internal.concurrent.ParallelismConfiguration;
import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.resources.ResourceLockCoordinationService;
import org.gradle.internal.resources.ResourceLockState;
import org.gradle.internal.time.Timer;
import org.gradle.internal.time.Timers;
import org.gradle.internal.work.WorkerLeaseRegistry.WorkerLease;
import org.gradle.internal.work.WorkerLeaseService;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.gradle.internal.resources.DefaultResourceLockCoordinationService.unlock;
import static org.gradle.internal.resources.ResourceLockState.Disposition.FINISHED;
import static org.gradle.internal.resources.ResourceLockState.Disposition.RETRY;
import static org.gradle.internal.time.Clock.prettyTime;

class DefaultTaskPlanExecutor implements TaskPlanExecutor, Stoppable {
    private static final Logger LOGGER = Logging.getLogger(DefaultTaskPlanExecutor.class);
    private final int executorCount;
    private final ExecutorFactory executorFactory;
    private final ResourceLockCoordinationService coordinationService;
    private final WorkerLeaseService workerLeaseService;
    private ManagedExecutor executor;
    private final Map<TaskExecutionPlan, Action<? super TaskInternal>> taskExecutionPlans = Maps.newLinkedHashMap();

    public DefaultTaskPlanExecutor(ParallelismConfiguration parallelismConfiguration, ExecutorFactory executorFactory,
                                   ResourceLockCoordinationService coordinationService, WorkerLeaseService workerLeaseService) {
        this.executorFactory = executorFactory;
        int numberOfParallelExecutors = parallelismConfiguration.getMaxWorkerCount();
        if (numberOfParallelExecutors < 1) {
            throw new IllegalArgumentException("Not a valid number of parallel executors: " + numberOfParallelExecutors);
        }

        this.executorCount = numberOfParallelExecutors;
        this.coordinationService = coordinationService;
        this.workerLeaseService = workerLeaseService;
    }

    private void start() {
        if (executor == null) {
            executor = executorFactory.create("Task worker for 'gradle - all'");
            WorkerLease parentWorkerLease = workerLeaseService.getCurrentWorkerLease();
            startWorkers(executor, parentWorkerLease);
        }
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.stop();
            executor = null;
        }
    }

    @Override
    public void process(TaskExecutionPlan taskExecutionPlan, Action<? super TaskInternal> taskWorker) {
        taskExecutionPlans.put(taskExecutionPlan, taskWorker);
        start();
        try {
            System.out.println("Awaiting completion");
            taskExecutionPlan.awaitCompletion();
            System.out.println("Awaited");
        } finally {
            stop();
        }
    }

    private void startWorkers(Executor executor, WorkerLease parentWorkerLease) {
        LOGGER.debug("Using {} parallel executor threads", executorCount);

        for (int i = 0; i < executorCount; i++) {
            Runnable worker = taskWorker(parentWorkerLease);
            executor.execute(worker);
        }
    }

    private Runnable taskWorker(WorkerLease parentWorkerLease) {
        return new TaskExecutorWorker(parentWorkerLease);
    }

    private class TaskExecutorWorker implements Runnable {
        private final WorkerLease parentWorkerLease;
        final AtomicLong busy = new AtomicLong(0);

        private TaskExecutorWorker(WorkerLease parentWorkerLease) {
            this.parentWorkerLease = parentWorkerLease;
        }

        public void run() {
            Timer totalTimer = Timers.startTimer();
            System.out.printf("Task worker [%s] started", Thread.currentThread());

            WorkerLease childLease = parentWorkerLease.createChild();
            boolean moreTasksToExecute = true;
            while (moreTasksToExecute) {
                moreTasksToExecute = executeWithTask(childLease);
            }

            long total = totalTimer.getElapsedMillis();

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Task worker [{}] finished, busy: {}, idle: {}", Thread.currentThread(), prettyTime(busy.get()), prettyTime(total - busy.get()));
            }
            System.out.printf("Task worker [%s] finished, busy: %s, idle: %s", Thread.currentThread(), prettyTime(busy.get()), prettyTime(total - busy.get()));
        }

        private boolean executeWithTask(final WorkerLease workerLease) {
            final AtomicReference<TaskInfo> taskInfo = new AtomicReference<TaskInfo>();
            final AtomicReference<TaskExecution> selected = new AtomicReference<TaskExecution>();
            final AtomicBoolean workRemaining = new AtomicBoolean();
            coordinationService.withStateLock(new Transformer<ResourceLockState.Disposition, ResourceLockState>() {
                @Override
                public ResourceLockState.Disposition transform(ResourceLockState resourceLockState) {
                    workRemaining.set(false);
                    for (TaskExecutionPlan taskExecutionPlan : taskExecutionPlans.keySet()) {
                        workRemaining.compareAndSet(false, taskExecutionPlan.hasWorkRemaining());
                        if (workRemaining.get()) {
                            try {
                                taskInfo.set(taskExecutionPlan.selectNextTask(workerLease));
                            } catch (Throwable t) {
                                workRemaining.set(false);
                                return FINISHED;
                            }
                            if (taskInfo.get() != null) {
                                TaskExecution taskExecution = new TaskExecution();
                                taskExecution.taskInfo = taskInfo.get();
                                taskExecution.plan = taskExecutionPlan;
                                taskExecution.worker = taskExecutionPlans.get(taskExecutionPlan);
                                selected.set(taskExecution);
                                return FINISHED;
                            }
                        }
                    }
                    if (workRemaining.get()) {
                        return RETRY;
                    }
                    return FINISHED;
                }
            });

            TaskExecution selectedTask = selected.get();
            execute(selectedTask, workerLease);
            return workRemaining.get();
        }

        private void execute(TaskExecution selectedTask, WorkerLease workerLease) {
            if (selectedTask == null) {
                return;
            }
            TaskInfo taskInfo = selectedTask.taskInfo;
            try {
                if (!taskInfo.isComplete()) {
                    executeTask(selectedTask);
                }
            } finally {
                coordinationService.withStateLock(unlock(workerLease, taskInfo.projectLock));
            }
        }

        private void executeTask(TaskExecution taskExecution) {
            TaskInfo task = taskExecution.taskInfo;
            final String taskPath = task.getTask().getPath();
            LOGGER.info("{} ({}) started.", taskPath, Thread.currentThread());
            final Timer taskTimer = Timers.startTimer();
            processTask(taskExecution);
            long taskDuration = taskTimer.getElapsedMillis();
            busy.addAndGet(taskDuration);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("{} ({}) completed. Took {}.", taskPath, Thread.currentThread(), prettyTime(taskDuration));
            }
        }

        private void processTask(TaskExecution taskExecution) {
            TaskInfo taskInfo = taskExecution.taskInfo;
            try {
                taskExecution.worker.execute(taskInfo.getTask());
            } catch (Throwable e) {
                taskInfo.setExecutionFailure(e);
            } finally {
                taskExecution.plan.taskComplete(taskInfo);
            }
        }
    }

    private static class TaskExecution {
        public TaskExecutionPlan plan;
        public Action<? super TaskInternal> worker;
        public TaskInfo taskInfo;
    }
}
