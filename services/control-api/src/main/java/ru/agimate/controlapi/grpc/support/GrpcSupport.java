package ru.agimate.controlapi.grpc.support;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.ValidationErrorStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.service.llm.LlmProviderDisabledException;
import ru.agimate.controlapi.service.llm.QuotaExceededException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Cross-cutting helpers of the worker's gRPC boundary: parsing the request's scalars, converting to
 * proto types and mapping domain exceptions onto gRPC {@link Status}. The domain proto↔entity mappers
 * live in {@code grpc.mapper}.
 */
@Slf4j
@UtilityClass
public class GrpcSupport {

    /** A mandatory UUID argument; empty or invalid → {@code INVALID_ARGUMENT}. */
    public static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw Status.INVALID_ARGUMENT.withDescription(field + " is required").asRuntimeException();
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw Status.INVALID_ARGUMENT.withDescription(field + " is not a valid UUID").asRuntimeException();
        }
    }

    /** An optional UUID argument; empty → {@code null}, invalid → {@code INVALID_ARGUMENT}. */
    public static UUID parseOptionalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw Status.INVALID_ARGUMENT.withDescription(field + " is not a valid UUID").asRuntimeException();
        }
    }

    public static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static Timestamp toProtoTimestamp(LocalDateTime ldt) {
        var instant = ldt.toInstant(ZoneOffset.UTC);
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    public static ByteString toJsonBytes(Object value) {
        return ByteString.copyFrom(JsonUtils.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
    }

    /** The single exception-to-gRPC mapping for every worker RPC. */
    public static void handleError(Exception e, StreamObserver<?> observer) {
        handleError(e, observer, null);
    }

    /**
     * The single exception-to-gRPC mapping. Expected domain outcomes (deny/quota/not-found/…) are
     * logged at DEBUG with no stack trace; an unknown error → {@code INTERNAL} with ERROR plus the stack
     * trace.
     *
     * @param context a short description of the RPC and its arguments for the failure log (may be {@code null})
     */
    public static void handleError(Exception e, StreamObserver<?> observer, String context) {
        if (e instanceof io.grpc.StatusRuntimeException sre) {
            observer.onError(sre);
            return;
        }
        String ctx = context == null ? "" : " [" + context + "]";
        Status domain = domainStatus(e);
        if (domain != null) {
            log.debug("gRPC domain outcome {}{}: {}", domain.getCode(), ctx, e.getMessage());
            observer.onError(domain.withDescription(e.getMessage()).asRuntimeException());
            return;
        }
        log.error("gRPC RPC failed{}", ctx, e);
        observer.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
    }

    /** A domain exception → the gRPC status of an expected outcome; {@code null} — an unknown error (INTERNAL). */
    private static Status domainStatus(Exception e) {
        if (e instanceof NotFoundStatusException) {
            return Status.NOT_FOUND;
        }
        if (e instanceof ForbiddenStatusException) {
            return Status.PERMISSION_DENIED;
        }
        if (e instanceof ConflictStatusException) {
            return Status.ABORTED;
        }
        if (e instanceof BadRequestStatusException || e instanceof ValidationErrorStatusException) {
            return Status.INVALID_ARGUMENT;
        }
        if (e instanceof QuotaExceededException) {
            return Status.RESOURCE_EXHAUSTED;
        }
        if (e instanceof LlmProviderDisabledException) {
            return Status.FAILED_PRECONDITION;
        }
        return null;
    }
}
