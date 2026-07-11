package ru.agimate.agentworker.workers;

import com.openai.core.http.Headers;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmCallWorkflowImplTest {

    private static RateLimitException rateLimit(Headers headers) {
        return RateLimitException.builder().headers(headers).build();
    }

    @Test
    @DisplayName("транзиентные: 429/5xx/сетевые (и в cause-цепочке); терминальные: 401 и не-SDK ошибки")
    void classifiesTransientErrors() {
        assertTrue(LlmCallWorkflowImpl.transientProviderError(rateLimit(Headers.builder().build())));
        assertTrue(LlmCallWorkflowImpl.transientProviderError(
                InternalServerException.builder().statusCode(503).headers(Headers.builder().build()).build()));
        assertTrue(LlmCallWorkflowImpl.transientProviderError(new OpenAIIoException("connect timed out")));
        // Spring AI оборачивает исключения SDK — классификация ходит по cause-цепочке.
        assertTrue(LlmCallWorkflowImpl.transientProviderError(
                new RuntimeException(rateLimit(Headers.builder().build()))));

        assertFalse(LlmCallWorkflowImpl.transientProviderError(
                UnauthorizedException.builder().headers(Headers.builder().build()).build()));
        assertFalse(LlmCallWorkflowImpl.transientProviderError(
                new IllegalArgumentException("Unsupported provider_type")));
    }

    @Test
    @DisplayName("Retry-After уважается с потолком 30 с; отсутствие/мусор → 0")
    void parsesRetryAfter() {
        assertEquals(7_000, LlmCallWorkflowImpl.retryAfterMs(
                rateLimit(Headers.builder().put("retry-after", "7").build())));
        assertEquals(30_000, LlmCallWorkflowImpl.retryAfterMs(
                rateLimit(Headers.builder().put("retry-after", "3600").build())));
        assertEquals(0, LlmCallWorkflowImpl.retryAfterMs(rateLimit(Headers.builder().build())));
        assertEquals(0, LlmCallWorkflowImpl.retryAfterMs(
                rateLimit(Headers.builder().put("retry-after", "Wed, 21 Oct 2026").build())));
        assertEquals(0, LlmCallWorkflowImpl.retryAfterMs(new OpenAIIoException("io")));
    }
}
