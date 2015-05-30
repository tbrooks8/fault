package fault;

import fault.circuit.BreakerConfig;
import fault.circuit.CircuitBreaker;
import fault.circuit.DefaultCircuitBreaker;
import fault.metrics.ActionMetrics;
import fault.metrics.SingleWriterActionMetrics;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by timbrooks on 12/23/14.
 */
public class BlockingExecutor extends AbstractServiceExecutor {

    private static final int MAX_CONCURRENCY_LEVEL = Integer.MAX_VALUE / 2;
    private final ExecutorService service;
    private final ExecutorService managingService = Executors.newFixedThreadPool(2);
    private final DelayQueue<ActionTimeout> timeoutQueue = new DelayQueue<>();
    private final BlockingQueue<Enum<?>> metricsQueue = new LinkedBlockingQueue<>();
    private final String name;
    private final Semaphore semaphore;

    public BlockingExecutor(int poolSize, int concurrencyLevel) {
        this(poolSize, concurrencyLevel, null);
    }

    public BlockingExecutor(int poolSize, int concurrencyLevel, String name) {
        this(poolSize, concurrencyLevel, name, new SingleWriterActionMetrics(3600));
    }

    public BlockingExecutor(int poolSize, int concurrencyLevel, String name, ActionMetrics actionMetrics) {
        this(poolSize, concurrencyLevel, name, actionMetrics, new DefaultCircuitBreaker(actionMetrics, new BreakerConfig
                .BreakerConfigBuilder().failureThreshold(20).timePeriodInMillis(5000).build()));
    }

    public BlockingExecutor(int poolSize, int concurrencyLevel, String name, ActionMetrics actionMetrics, CircuitBreaker
            circuitBreaker) {
        super(circuitBreaker, actionMetrics);
        if (concurrencyLevel > MAX_CONCURRENCY_LEVEL) {
            throw new RuntimeException("Concurrency Level \"" + concurrencyLevel + "\" is greater than the allowed " +
                    "maximum: " + MAX_CONCURRENCY_LEVEL + ".");
        }

        if (name == null) {
            this.name = this.toString();
        } else {
            this.name = name;
        }
        this.semaphore = new Semaphore(concurrencyLevel);
        this.service = new ThreadPoolExecutor(poolSize, poolSize, Long.MAX_VALUE, TimeUnit.DAYS,
                new ArrayBlockingQueue<Runnable>(concurrencyLevel * 2), new ServiceThreadFactory());
        startTimeoutAndMetrics();
    }

    @Override
    public <T> ResilientFuture<T> submitAction(ResilientAction<T> action, long millisTimeout) {
        return submitAction(action, new MultipleWriterResilientPromise<T>(), millisTimeout);
    }

    @Override
    public <T> ResilientFuture<T> submitAction(ResilientAction<T> action, ResilientPromise<T> promise, long
            millisTimeout) {
        return submitAction(action, promise, null, millisTimeout);
    }

    @Override
    public <T> ResilientFuture<T> submitAction(ResilientAction<T> action, ResilientCallback<T> callback, long
            millisTimeout) {
        return submitAction(action, new MultipleWriterResilientPromise<T>(), callback, millisTimeout);
    }

    @Override
    public <T> ResilientFuture<T> submitAction(final ResilientAction<T> action, final ResilientPromise<T> promise,
                                               final ResilientCallback<T> callback, long millisTimeout) {
        final Semaphore.Permit permit = acquirePermitOrRejectIfActionNotAllowed();
        try {
            final Future<Void> f = service.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    try {
                        T result = action.run();
                        if (promise.deliverResult(result)) {
                            promise.setCompletedBy(uuid);
                            metricsQueue.offer(Status.SUCCESS);
                            if (callback != null) {
                                callback.run(promise);
                            }
                        } else if (!uuid.equals(promise.getCompletedBy())) {
                            metricsQueue.offer(Status.SUCCESS);
                        }
                    } catch (InterruptedException e) {
                        // This needs to be documented. Or create a work around. The reason for ignoring this
                        // exception is to prevent the metrics from being updated twice in the scenario where: the
                        // action timed-out. But the promise was already completed.
                        return null;
                    } catch (Exception e) {
                        if (promise.deliverError(e)) {
                            promise.setCompletedBy(uuid);
                            metricsQueue.offer(Status.ERROR);
                            if (callback != null) {
                                callback.run(promise);
                            }
                        } else if (!uuid.equals(promise.getCompletedBy())) {
                            metricsQueue.offer(Status.ERROR);
                        }
                    } finally {
                        semaphore.releasePermit(permit);
                    }
                    return null;
                }
            });
            if (millisTimeout > MAX_TIMEOUT_MILLIS) {
                timeoutQueue.offer(new ActionTimeout(permit, promise, MAX_TIMEOUT_MILLIS, f, callback));
            } else {
                timeoutQueue.offer(new ActionTimeout(permit, promise, millisTimeout, f, callback));
            }
        } catch (RejectedExecutionException e) {
            metricsQueue.add(RejectionReason.QUEUE_FULL);
            semaphore.releasePermit(permit);
            throw new RejectedActionException(RejectionReason.QUEUE_FULL);
        }

        return new ResilientFuture<>(promise);
    }

    @Override
    public <T> ResilientPromise<T> performAction(final ResilientAction<T> action) {
        ResilientPromise<T> promise = new SingleWriterResilientPromise<>();
        Semaphore.Permit permit = acquirePermitOrRejectIfActionNotAllowed();
        try {
            T result = action.run();
            promise.deliverResult(result);
        } catch (ActionTimeoutException e) {
            promise.setTimedOut();
        } catch (Exception e) {
            promise.deliverError(e);
        }

        metricsQueue.offer(promise.getStatus());
        semaphore.releasePermit(permit);

        return promise;
    }

    @Override
    public void shutdown() {
        service.shutdown();
        managingService.shutdown();
    }

    private Semaphore.Permit acquirePermitOrRejectIfActionNotAllowed() {
        Semaphore.Permit permit = semaphore.acquirePermit();
        if (permit == null) {
            metricsQueue.add(RejectionReason.MAX_CONCURRENCY_LEVEL_EXCEEDED);
            throw new RejectedActionException(RejectionReason.MAX_CONCURRENCY_LEVEL_EXCEEDED);
        }
        if (!circuitBreaker.allowAction()) {
            metricsQueue.add(RejectionReason.CIRCUIT_OPEN);
            semaphore.releasePermit(permit);
            throw new RejectedActionException(RejectionReason.CIRCUIT_OPEN);
        }
        return permit;
    }

    private void startTimeoutAndMetrics() {
        Thread metrics = new Thread(new Runnable() {
            @Override
            public void run() {
                for (; ; ) {
                    try {
                        Enum result = metricsQueue.take();
                        if (result instanceof Status) {
                            actionMetrics.reportActionResult((Status) result);
                            circuitBreaker.informBreakerOfResult(result == Status.SUCCESS);
                        } else {
                            actionMetrics.reportRejectionAction((RejectionReason) result);
                        }
                    } catch (InterruptedException e) {
                        break;
                    }

                }
            }
        });

        metrics.setName("Metrics thread");
        metrics.start();

        Thread timeouts = new Thread(new Runnable() {
            @Override
            public void run() {
                for (; ; ) {
                    try {
                        ActionTimeout timeout = timeoutQueue.take();
                        @SuppressWarnings("unchecked")
                        ResilientPromise<Object> promise = (ResilientPromise<Object>) timeout.promise;
                        if (promise.setTimedOut()) {
                            promise.setCompletedBy(uuid);
                            timeout.future.cancel(true);
                            metricsQueue.offer(Status.TIMED_OUT);
                            circuitBreaker.informBreakerOfResult(promise.isSuccessful());

                            @SuppressWarnings("unchecked")
                            ResilientCallback<Object> callback = (ResilientCallback<Object>) timeout.callback;
                            if (callback != null) {
                                callback.run(promise);
                            }
                        } else if (!uuid.equals(promise.getCompletedBy())) {
                            timeout.future.cancel(true);
                            metricsQueue.offer(Status.TIMED_OUT);
                        }
                        semaphore.releasePermit(timeout.permit);

                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        });
        timeouts.setName("Timeout thread");

        timeouts.start();
    }

    private class ActionTimeout implements Delayed {

        private final long millisAbsoluteTimeout;
        private final Semaphore.Permit permit;
        private final ResilientPromise<?> promise;
        private final ResilientCallback<?> callback;
        private final Future<Void> future;

        public ActionTimeout(Semaphore.Permit permit, ResilientPromise<?> promise, long millisRelativeTimeout,
                             Future<Void> future, ResilientCallback<?> callback) {
            this.permit = permit;
            this.promise = promise;
            this.callback = callback;
            this.millisAbsoluteTimeout = millisRelativeTimeout + System.currentTimeMillis();
            this.future = future;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(millisAbsoluteTimeout - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            if (o instanceof ActionTimeout) {
                return Long.compare(millisAbsoluteTimeout, ((ActionTimeout) o).millisAbsoluteTimeout);
            }
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS));
        }
    }

    private class ServiceThreadFactory implements ThreadFactory {

        public final AtomicInteger count = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, name + "-" + count.getAndIncrement());
        }
    }
}
