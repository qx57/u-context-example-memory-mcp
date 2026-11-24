package local.memory;

import java.util.UUID;

enum ErrorCode {
    INVALID_INPUT(400, "Проверьте входные данные.", false),
    SENSITIVE_INPUT(400, "Уберите конфиденциальные данные.", false),
    UNAUTHENTICATED(401, "Предъявите учётные данные оператора.", false),
    FORBIDDEN(403, "Доступ к операции запрещён.", false),
    MEMORY_NOT_FOUND(404, "Наблюдение не найдено.", false),
    IDEMPOTENCY_CONFLICT(409, "UUID уже связан с другим наблюдением.", false),
    REVISION_CONFLICT(412, "Перечитайте карточку наблюдения.", false),
    REVISION_REQUIRED(428, "Укажите текущую ревизию в If-Match.", false),
    BUSY(429, "Сервис занят. Повторите с задержкой.", true),
    DEPENDENCY_UNAVAILABLE(503, "Зависимость временно недоступна.", true),
    CONFIGURATION_ERROR(503, "Проверьте конфигурацию сервиса и зависимостей.", false);

    final int status;
    final String message;
    final boolean retryable;

    ErrorCode(int status, String message, boolean retryable) {
        this.status = status;
        this.message = message;
        this.retryable = retryable;
    }
}

final class MemoryError extends RuntimeException {
    final ErrorCode code;
    final String requestId;

    MemoryError(ErrorCode code) {
        this(code, UUID.randomUUID().toString());
    }

    MemoryError(ErrorCode code, String requestId) {
        super(code.message);
        this.code = code;
        this.requestId = requestId;
    }

    Body body() {
        return new Body(code.name(), code.message, code.retryable, requestId);
    }

    record Body(String code, String message, boolean retryable, String requestId) {}
}
