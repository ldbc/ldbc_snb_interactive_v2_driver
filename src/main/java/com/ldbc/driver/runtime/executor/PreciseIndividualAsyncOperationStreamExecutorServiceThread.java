package com.ldbc.driver.runtime.executor;

import com.ldbc.driver.*;
import com.ldbc.driver.runtime.ConcurrentErrorReporter;
import com.ldbc.driver.runtime.coordination.CompletionTimeException;
import com.ldbc.driver.runtime.coordination.DummyLocalCompletionTimeWriter;
import com.ldbc.driver.runtime.coordination.GlobalCompletionTimeReader;
import com.ldbc.driver.runtime.coordination.LocalCompletionTimeWriter;
import com.ldbc.driver.runtime.metrics.ConcurrentMetricsService;
import com.ldbc.driver.runtime.scheduling.GctDependencyCheck;
import com.ldbc.driver.runtime.scheduling.Spinner;
import com.ldbc.driver.temporal.Duration;
import com.ldbc.driver.temporal.Time;
import com.ldbc.driver.temporal.TimeSource;
import com.ldbc.driver.util.Function0;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class PreciseIndividualAsyncOperationStreamExecutorServiceThread extends Thread {
    private static final Duration POLL_INTERVAL_WHILE_WAITING_FOR_LAST_HANDLER_TO_FINISH = Duration.fromMilli(100);
    private static final LocalCompletionTimeWriter DUMMY_LOCAL_COMPLETION_TIME_WRITER = new DummyLocalCompletionTimeWriter();

    private final TimeSource TIME_SOURCE;
    private final OperationHandlerExecutor operationHandlerExecutor;
    private final Spinner slightlyEarlySpinner;
    private final ConcurrentErrorReporter errorReporter;
    private final AtomicBoolean hasFinished;
    private final AtomicBoolean forcedTerminate;
    private final AtomicInteger runningHandlerCount = new AtomicInteger(0);
    private final HandlerRetriever handlerRetriever;
    private final Duration durationToWaitForAllHandlersToFinishBeforeShutdown;

    public PreciseIndividualAsyncOperationStreamExecutorServiceThread(TimeSource timeSource,
                                                                      OperationHandlerExecutor operationHandlerExecutor,
                                                                      ConcurrentErrorReporter errorReporter,
                                                                      Iterator<Operation<?>> gctReadOperations,
                                                                      Iterator<Operation<?>> gctWriteOperations,
                                                                      AtomicBoolean hasFinished,
                                                                      Spinner spinner,
                                                                      Spinner slightlyEarlySpinner,
                                                                      AtomicBoolean forcedTerminate,
                                                                      Map<Class<? extends Operation>, OperationClassification> operationClassifications,
                                                                      Db db,
                                                                      LocalCompletionTimeWriter localCompletionTimeWriter,
                                                                      GlobalCompletionTimeReader globalCompletionTimeReader,
                                                                      ConcurrentMetricsService metricsService,
                                                                      Duration durationToWaitForAllHandlersToFinishBeforeShutdown) {
        super(PreciseIndividualAsyncOperationStreamExecutorServiceThread.class.getSimpleName() + "-" + System.currentTimeMillis());
        this.TIME_SOURCE = timeSource;
        this.operationHandlerExecutor = operationHandlerExecutor;
        this.slightlyEarlySpinner = slightlyEarlySpinner;
        this.errorReporter = errorReporter;
        this.hasFinished = hasFinished;
        this.forcedTerminate = forcedTerminate;
        this.durationToWaitForAllHandlersToFinishBeforeShutdown = durationToWaitForAllHandlersToFinishBeforeShutdown;
        this.handlerRetriever = new HandlerRetriever(
                gctReadOperations,
                gctWriteOperations,
                db,
                localCompletionTimeWriter,
                globalCompletionTimeReader,
                operationClassifications,
                spinner,
                timeSource,
                errorReporter,
                metricsService);
    }

    @Override
    public void run() {
        while (handlerRetriever.hasNextHandler() && false == forcedTerminate.get()) {
            OperationHandler<?> handler;
            try {
                handler = handlerRetriever.nextHandler();
            } catch (Exception e) {
                errorReporter.reportError(
                        this,
                        String.format("Error while retrieving next handler\n%s",
                                ConcurrentErrorReporter.stackTraceToString(e)));
                continue;
            }

            // Schedule slightly early to account for context switch - internally, handler will schedule at exact start time
            // TODO forcedTerminate does not cover all cases at present this spin loop is still blocking -> inject a check that throws exception?
            // TODO or SpinnerChecks have three possible results? (TRUE, NOT_TRUE_YET, FALSE)
            // TODO and/or Spinner has an emergency terminate button?
            slightlyEarlySpinner.waitForScheduledStartTime(handler.operation());

            // execute handler
            try {
                executeHandler(handler);
            } catch (OperationHandlerExecutorException e) {
                String errMsg = String.format("Error encountered while submitting operation for execution\n%s",
                        ConcurrentErrorReporter.stackTraceToString(e));
                errorReporter.reportError(this, errMsg);
                continue;
            }
        }

        boolean handlersFinishedInTime = awaitAllRunningHandlers(durationToWaitForAllHandlersToFinishBeforeShutdown);
        if (false == handlersFinishedInTime) {
            errorReporter.reportError(
                    this,
                    String.format(
                            "%s operation handlers did not complete in time (within %s of the time the last operation was submitted for execution)",
                            runningHandlerCount.get(),
                            durationToWaitForAllHandlersToFinishBeforeShutdown
                    )
            );
        }
        this.hasFinished.set(true);
    }

    private void executeHandler(OperationHandler<?> handler) throws OperationHandlerExecutorException {
        try {
            runningHandlerCount.incrementAndGet();
            DecrementRunningHandlerCountFun decrementRunningHandlerCountFun = new DecrementRunningHandlerCountFun(runningHandlerCount);
            handler.addOnCompleteTask(decrementRunningHandlerCountFun);
            operationHandlerExecutor.execute(handler);
        } catch (OperationHandlerExecutorException e) {
            throw new OperationHandlerExecutorException(
                    String.format("Error encountered while submitting operation for execution\nOperation: %s", handler.operation()));
        }
    }

    private boolean awaitAllRunningHandlers(Duration timeoutDuration) {
        long pollInterval = POLL_INTERVAL_WHILE_WAITING_FOR_LAST_HANDLER_TO_FINISH.asMilli();
        long timeoutTimeMs = TIME_SOURCE.now().plus(timeoutDuration).asMilli();
        while (TIME_SOURCE.nowAsMilli() < timeoutTimeMs) {
            if (0 == runningHandlerCount.get()) return true;
            if (forcedTerminate.get()) return true;
            Spinner.powerNap(pollInterval);
        }
        return false;
    }

    private final class DecrementRunningHandlerCountFun implements Function0 {
        private final AtomicInteger runningHandlerCount;

        private DecrementRunningHandlerCountFun(AtomicInteger runningHandlerCount) {
            this.runningHandlerCount = runningHandlerCount;
        }

        @Override
        public Object apply() {
            runningHandlerCount.decrementAndGet();
            return null;
        }
    }

    private static class HandlerRetriever {
        private final Iterator<Operation<?>> gctReadOperations;
        private final Iterator<Operation<?>> gctWriteOperations;
        private final Db db;
        private final LocalCompletionTimeWriter localCompletionTimeWriter;
        private final GlobalCompletionTimeReader globalCompletionTimeReader;
        private final Map<Class<? extends Operation>, OperationClassification> operationClassifications;
        private final Spinner spinner;
        private final TimeSource timeSource;
        private final ConcurrentErrorReporter errorReporter;
        private final ConcurrentMetricsService metricsService;
        OperationHandler<?> nextGctReadHandler;
        OperationHandler<?> nextGctWriteHandler;

        private HandlerRetriever(Iterator<Operation<?>> gctReadOperations,
                                 Iterator<Operation<?>> gctWriteOperations,
                                 Db db,
                                 LocalCompletionTimeWriter localCompletionTimeWriter,
                                 GlobalCompletionTimeReader globalCompletionTimeReader,
                                 Map<Class<? extends Operation>, OperationClassification> operationClassifications,
                                 Spinner spinner,
                                 TimeSource timeSource,
                                 ConcurrentErrorReporter errorReporter,
                                 ConcurrentMetricsService metricsService) {
            this.gctReadOperations = gctReadOperations;
            this.gctWriteOperations = gctWriteOperations;
            this.db = db;
            this.localCompletionTimeWriter = localCompletionTimeWriter;
            this.globalCompletionTimeReader = globalCompletionTimeReader;
            this.operationClassifications = operationClassifications;
            this.spinner = spinner;
            this.timeSource = timeSource;
            this.errorReporter = errorReporter;
            this.metricsService = metricsService;
            this.nextGctReadHandler = null;
            this.nextGctWriteHandler = null;
        }

        public boolean hasNextHandler() {
            return gctReadOperations.hasNext() || gctWriteOperations.hasNext();
        }

        public OperationHandler<?> nextHandler() throws OperationHandlerExecutorException, CompletionTimeException {
            if (gctWriteOperations.hasNext() && null == nextGctWriteHandler) {
                Operation<?> nextGctWriteOperation = gctWriteOperations.next();
                nextGctWriteHandler = getAndInitializeHandler(nextGctWriteOperation, localCompletionTimeWriter);
                // submit initiated time as soon as possible so GCT/dependencies can advance as soon as possible
                nextGctWriteHandler.localCompletionTimeWriter().submitLocalInitiatedTime(nextGctWriteHandler.operation().scheduledStartTime());
                if (false == gctWriteOperations.hasNext()) {
                    // after last write operation, submit highest possible initiated time to ensure that GCT progresses to time of highest LCT write
                    nextGctWriteHandler.localCompletionTimeWriter().submitLocalInitiatedTime(Time.fromNano(Long.MAX_VALUE));
                }
            }
            if (gctReadOperations.hasNext() && null == nextGctReadHandler) {
                Operation<?> nextGctReadOperation = gctReadOperations.next();
                nextGctReadHandler = getAndInitializeHandler(nextGctReadOperation, DUMMY_LOCAL_COMPLETION_TIME_WRITER);
                // no need to submit initiated time for an operation that should not write to GCT
            }
            if (null != nextGctWriteHandler && null != nextGctReadHandler) {
                long nextGctWriteHandlerStartTime = nextGctWriteHandler.operation().scheduledStartTime().asNano();
                long nextGctReadHandlerStartTime = nextGctReadHandler.operation().scheduledStartTime().asNano();
                OperationHandler<?> nextHandler;
                if (nextGctReadHandlerStartTime < nextGctWriteHandlerStartTime) {
                    nextHandler = nextGctReadHandler;
                    nextGctReadHandler = null;
                } else {
                    nextHandler = nextGctWriteHandler;
                    nextGctWriteHandler = null;
                }
                return nextHandler;
            } else if (null == nextGctWriteHandler && null != nextGctReadHandler) {
                OperationHandler<?> nextHandler = nextGctReadHandler;
                nextGctReadHandler = null;
                return nextHandler;
            } else if (null != nextGctWriteHandler && null == nextGctReadHandler) {
                OperationHandler<?> nextHandler = nextGctWriteHandler;
                nextGctWriteHandler = null;
                return nextHandler;
            } else {
                throw new OperationHandlerExecutorException("Unexpected error in " + getClass().getSimpleName());
            }
        }

        private OperationHandler<?> getAndInitializeHandler(Operation<?> operation, LocalCompletionTimeWriter localCompletionTimeWriterForHandler) throws OperationHandlerExecutorException {
            OperationHandler<?> operationHandler;
            try {
                operationHandler = db.getOperationHandler(operation);
            } catch (DbException e) {
                throw new OperationHandlerExecutorException(String.format("Error while retrieving handler for operation\nOperation: %s", operation));
            }

            OperationClassification.DependencyMode operationDependencyMode = operationClassifications.get(operation.getClass()).dependencyMode();
            try {
                operationHandler.init(timeSource, spinner, operation, localCompletionTimeWriterForHandler, errorReporter, metricsService);
            } catch (OperationException e) {
                throw new OperationHandlerExecutorException(String.format("Error while initializing handler for operation\nOperation: %s", operation));
            }

            if (isDependencyReadingOperation(operationDependencyMode))
                operationHandler.addBeforeExecuteCheck(new GctDependencyCheck(globalCompletionTimeReader, operation, errorReporter));

            return operationHandler;
        }

        private boolean isDependencyReadingOperation(OperationClassification.DependencyMode operationDependencyMode) {
            return operationDependencyMode.equals(OperationClassification.DependencyMode.READ_WRITE) ||
                    operationDependencyMode.equals(OperationClassification.DependencyMode.READ);
        }
    }
}