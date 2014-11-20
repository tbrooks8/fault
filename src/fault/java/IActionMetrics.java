package fault.java;

/**
 * Created by timbrooks on 11/20/14.
 */
public interface IActionMetrics {
    int getFailuresForTimePeriod(int milliseconds);

    void logActionResult(ResilientPromise promise);
}
