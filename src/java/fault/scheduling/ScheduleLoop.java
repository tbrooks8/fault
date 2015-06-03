package fault.scheduling;

import fault.ActionTimeoutException;
import fault.Status;
import fault.concurrent.ResilientPromise;
import fault.messages.ResultMessage;
import fault.messages.ScheduleMessage;
import fault.metrics.Metric;

import java.util.concurrent.FutureTask;

/**
 * Created by timbrooks on 12/9/14.
 */
public class ScheduleLoop {

    public static boolean runLoop(ScheduleContext scheduleContext) {
        boolean didSomething = false;

        for (int i = 0; i < scheduleContext.poolSize; ++i) {
            if (handleScheduling(scheduleContext)) {
                didSomething = true;
            } else {
                break;
            }
        }

        for (int i = 0; i < scheduleContext.poolSize; ++i) {
            if (handleReturnResult(scheduleContext)) {
                didSomething = true;
            } else {
                break;
            }
        }

        TimeoutService.triggerTimeouts(scheduleContext);

        return didSomething;
    }

    private static boolean handleScheduling(ScheduleContext scheduleContext) {
        ScheduleMessage<Object> scheduleMessage = scheduleContext.toScheduleQueue.poll();
        if (scheduleMessage != null) {
            ActionCallable<Object> actionCallable = new ActionCallable<>(scheduleMessage.action, scheduleContext
                    .toReturnQueue);
            FutureTask<Void> futureTask = new FutureTask<>(actionCallable);
            ResilientTask<Object> resilientTask = new ResilientTask<>(futureTask, scheduleMessage.promise);
            ResultMessage<Object> resultMessage = actionCallable.resultMessage;
            scheduleContext.taskMap.put(resultMessage, resilientTask);

            scheduleContext.executorService.submit(resilientTask);
            TimeoutService.scheduleTimeout(scheduleContext.scheduled, scheduleMessage.absoluteTimeout, resultMessage);
            return true;
        }
        return false;
    }

    private static boolean handleReturnResult(ScheduleContext scheduleContext) {
        ResultMessage<Object> result = scheduleContext.toReturnQueue.poll();
        if (result != null) {
            if (ResultMessage.Type.ASYNC.equals(result.type)) {
                handleAsyncResult(scheduleContext, result);
                return true;
            } else {
                handleSyncResult(scheduleContext, result);
            }
        }
        return false;
    }

    private static void handleSyncResult(ScheduleContext scheduleContext, ResultMessage<Object> result) {
        if (result.result != null) {
            scheduleContext.actionMetrics.incrementMetric(Metric.statusToMetric(Status.SUCCESS));
        } else if (result.exception instanceof ActionTimeoutException) {
            TimeoutService.handleSyncTimeout(scheduleContext);
        } else {
            scheduleContext.actionMetrics.incrementMetric(Metric.statusToMetric(Status.ERROR));
        }

    }

    private static void handleAsyncResult(ScheduleContext scheduleContext, ResultMessage<Object> result) {
        ResilientTask<Object> resilientTask = scheduleContext.taskMap.remove(result);
        if (resilientTask != null) {

            ResilientPromise<Object> promise = resilientTask.resilientPromise;
            if (result.result != null) {
                promise.deliverResult(result.result);
            } else {
                promise.deliverError(result.exception);

            }
            scheduleContext.actionMetrics.incrementMetric(Metric.statusToMetric(promise.getStatus()));
            scheduleContext.circuitBreaker.informBreakerOfResult(result.exception == null);
        }
    }
}
