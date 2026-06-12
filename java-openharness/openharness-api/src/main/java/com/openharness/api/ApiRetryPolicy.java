package com.openharness.api;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Exponential backoff retry policy for LLM API calls.
 * Java equivalent of Python's API retry logic.
 */
public final class ApiRetryPolicy {

    private static final Logger LOG = Logger.getLogger(ApiRetryPolicy.class.getName());
    private static final int MAX_RETRIES = 3;
    private static final Duration BASE_DELAY = Duration.ofSeconds(1);

    private ApiRetryPolicy() {}

    @SuppressWarnings("unchecked")
    public static <T> T execute(Supplier<T> call) throws ApiError {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return call.get();
            } catch (ApiError.RateLimitError e) {
                Duration delay = e.retryAfter() != null
                        ? Duration.between(Instant.now(), e.retryAfter())
                        : BASE_DELAY.multipliedBy(1L << attempt);
                final long delayMs = delay.toMillis();
                final int attemptNum = attempt + 1;
                LOG.warning(() -> "Rate limited, retrying after " + delayMs + "ms (attempt " + attemptNum + ")");
                sleep(delay);
            } catch (ApiError.ServerError e) {
                if (attempt == MAX_RETRIES - 1) throw e;
                Duration delay = BASE_DELAY.multipliedBy(1L << attempt);
                final long delayMs = delay.toMillis();
                final int attemptNum = attempt + 1;
                LOG.warning(() -> "Server error, retrying after " + delayMs + "ms (attempt " + attemptNum + ")");
                sleep(delay);
            }
        }
        throw new ApiError.RequestError(-1, "Max retries exceeded");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ApiError.RequestError(-1, "Retry interrupted");
        }
    }
}
