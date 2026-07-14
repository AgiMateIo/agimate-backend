package ru.agimate.controlapi.grpc.support;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.ValidationErrorStatusException;
import ru.agimate.controlapi.service.llm.QuotaExceededException;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GrpcSupport.handleError")
class GrpcSupportTest {

    private static Throwable mapped(Exception e) {
        AtomicReference<Throwable> ref = new AtomicReference<>();
        StreamObserver<Object> observer = new StreamObserver<>() {
            @Override
            public void onNext(Object value) {
            }

            @Override
            public void onError(Throwable t) {
                ref.set(t);
            }

            @Override
            public void onCompleted() {
            }
        };
        GrpcSupport.handleError(e, observer, "ctx");
        return ref.get();
    }

    private static Status.Code code(Exception e) {
        Throwable t = mapped(e);
        assertTrue(t instanceof StatusRuntimeException, "ожидался StatusRuntimeException, был " + t);
        return ((StatusRuntimeException) t).getStatus().getCode();
    }

    @Test
    @DisplayName("доменные исключения → ожидаемые gRPC-статусы (единое супермножество для всех RPC)")
    void mapsDomainExceptions() {
        assertEquals(Status.Code.NOT_FOUND, code(new NotFoundStatusException("x")));
        assertEquals(Status.Code.PERMISSION_DENIED, code(new ForbiddenStatusException("x")));
        assertEquals(Status.Code.ABORTED, code(new ConflictStatusException("x")));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(new BadRequestStatusException("x")));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(new ValidationErrorStatusException("f", "x")));
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, code(new QuotaExceededException("x")));
    }

    @Test
    @DisplayName("неизвестная ошибка → INTERNAL")
    void unknownToInternal() {
        assertEquals(Status.Code.INTERNAL, code(new IllegalStateException("boom")));
    }

    @Test
    @DisplayName("уже-gRPC StatusRuntimeException пробрасывается как есть")
    void passthroughStatusRuntimeException() {
        StatusRuntimeException sre = Status.UNAUTHENTICATED.withDescription("no").asRuntimeException();
        assertSame(sre, mapped(sre));
    }
}
