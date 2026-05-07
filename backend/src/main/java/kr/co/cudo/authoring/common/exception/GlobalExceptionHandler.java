package kr.co.cudo.authoring.common.exception;

import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustom(CustomException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("[Exception] custom code={} message={}", code.name(), e.getMessage());
        return ResponseEntity.status(code.status())
                .body(ApiResponse.error(code, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("[Exception] validation failed message={}", msg);
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.status())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, msg));
    }

    /**
     * @RequestParam / @PathVariable 등 메서드 파라미터 레벨 @Min/@Max/@NotBlank 위반 (jakarta.validation).
     * 컨트롤러 클래스에 @Validated 가 있을 때 발생.
     */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(jakarta.validation.ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining(", "));
        log.warn("[Exception] constraint violation message={}", msg);
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.status())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, msg));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("[Exception] access denied message={}", e.getMessage());
        return ResponseEntity.status(ErrorCode.FORBIDDEN.status())
                .body(ApiResponse.error(ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuth(AuthenticationException e) {
        log.warn("[Exception] auth failed message={}", e.getMessage());
        return ResponseEntity.status(ErrorCode.UNAUTHORIZED.status())
                .body(ApiResponse.error(ErrorCode.UNAUTHORIZED));
    }

    /**
     * 매핑되지 않은 경로 요청 시 Spring 이 던지는 예외를 404 로 정규화한다.
     * 이 핸들러가 없으면 Exception.class 가 잡아 500 INTERNAL_ERROR 로 응답되어
     * FE 가 미구현 endpoint 를 서버 장애로 오인하게 된다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("[Exception] no resource found path={}", e.getResourcePath());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(ApiResponse.error(ErrorCode.NOT_FOUND, "요청한 API를 찾을 수 없습니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        log.error("[Exception] unhandled exception", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }
}
