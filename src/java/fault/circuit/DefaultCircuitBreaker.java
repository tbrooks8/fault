package fault.circuit;

import fault.metrics.ActionMetrics;
import fault.metrics.Metric;
import fault.utils.SystemTime;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by timbrooks on 11/5/14.
 */
public class DefaultCircuitBreaker implements CircuitBreaker {

    private static final int CLOSED = 0;
    private static final int OPEN = 1;
    private static final int FORCED_OPEN = 2;

    private final SystemTime systemTime;
    private final ActionMetrics actionMetrics;
    private final AtomicInteger state = new AtomicInteger(0);
    private final AtomicLong lastTestedTime = new AtomicLong(0);
    private final AtomicReference<BreakerConfig> breakerConfig;

    public DefaultCircuitBreaker(ActionMetrics actionMetrics, BreakerConfig breakerConfig) {
        this(actionMetrics, breakerConfig, new SystemTime());
    }

    public DefaultCircuitBreaker(ActionMetrics actionMetrics, BreakerConfig breakerConfig, SystemTime systemTime) {
        this.systemTime = systemTime;
        this.actionMetrics = actionMetrics;
        this.breakerConfig = new AtomicReference<>(breakerConfig);
    }

    @Override
    public boolean isOpen() {
        return state.get() != CLOSED;
    }

    @Override
    public boolean allowAction() {
        int state = this.state.get();
        if (state == OPEN) {
            long backOffTimeMillis = breakerConfig.get().backOffTimeMillis;
            long currentTime = systemTime.currentTimeMillis();
            // This potentially allows a couple of tests through. Should think about this decision
            if (currentTime < backOffTimeMillis + lastTestedTime.get()) {
                return false;
            }
            lastTestedTime.set(currentTime);
        }
        return state != FORCED_OPEN;
    }

    @Override
    public void informBreakerOfResult(boolean successful) {
        if (successful) {
            if (state.get() == OPEN) {
                // This can get stuck in a loop with open and closing
                state.compareAndSet(OPEN, CLOSED);
            }
        } else {
            if (state.get() == CLOSED) {
                BreakerConfig config = this.breakerConfig.get();
                long timePeriod = config.trailingPeriodMillis / 1000;
                long errorsForTimePeriod = actionMetrics.getMetricCountForTimePeriod(Metric.ERROR, timePeriod, TimeUnit.SECONDS);
                long timeoutsForTimePeriod = actionMetrics.getMetricCountForTimePeriod(Metric.TIMEOUT, timePeriod, TimeUnit.SECONDS);
                long failuresForTimePeriod = errorsForTimePeriod + timeoutsForTimePeriod;
                if (config.failureThreshold < failuresForTimePeriod) {
                    lastTestedTime.set(systemTime.currentTimeMillis());
                    state.compareAndSet(CLOSED, OPEN);
                }
            }
        }
    }

    @Override
    public BreakerConfig getBreakerConfig() {
        return breakerConfig.get();
    }

    @Override
    public void setBreakerConfig(BreakerConfig breakerConfig) {
        this.breakerConfig.set(breakerConfig);
    }

    @Override
    public void forceOpen() {
        state.set(FORCED_OPEN);
    }

    @Override
    public void forceClosed() {
        state.set(CLOSED);
    }
}
