package kr.co.cudo.authoring.common.exception;

import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Object>> handleCustom(CustomException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("[Exception] custom code={} message={}", code.name(), e.getMessage());
        Object details = e.getDetails();
        ApiResponse<Object> body = details != null
                ? ApiResponse.error(code, e.getMessage(), details)
                : ApiResponse.error(code, e.getMessage());
        return ResponseEntity.status(code.status()).body(body);
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

    /**
     * 본문 역직렬화 실패 (잘못된 JSON, 매핑 불가 enum 값 등) 시 400 으로 정규화한다.
     * 이 핸들러가 없으면 Exception.class 가 잡아 500 으로 응답되어 FE 가 입력 오류를 구별할 수 없다.
     * (CWE-209 Information Leak 가드 — 메시지에 파서 내부 상세를 노출하지 않는다.)
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("[Exception] message not readable cause={}", e.getMostSpecificCause().getClass().getSimpleName());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.status())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, "요청 본문이 올바르지 않습니다."));
    }

    /**
     * 필수 @RequestParam 누락 시 400 으로 정규화한다 (R16).
     * 이 핸들러가 없으면 Exception.class 가 잡아 500 으로 응답되어 FE 가 입력 누락을 장애로 오인한다.
     * (CWE-209 가드 — 파라미터명만 노출, 내부 상세 비노출.)
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("[Exception] missing request param name={}", e.getParameterName());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.status())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, "필수 파라미터가 누락되었습니다: " + e.getParameterName()));
    }

    /**
     * @RequestParam / @PathVariable 타입 불일치 (예: Long 자리에 문자열) 시 400 으로 정규화한다 (R16).
     * 이 핸들러가 없으면 Exception.class 가 잡아 500 으로 응답된다.
     * (CWE-209 가드 — 사용자 입력 원문/타입 내부 상세를 메시지에 노출하지 않는다.)
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("[Exception] type mismatch param={}", e.getName());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.status())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, "파라미터 형식이 올바르지 않습니다: " + e.getName()));
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
    /**
     * Spring multipart 한도 초과 시 발생하는 예외를 413 으로 정규화한다.
     * (CWE-770 Resource Consumption 가드 — 사용자에게는 메시지만 노출, 내부 한도 수치 비노출.)
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.warn("[Exception] max upload size exceeded");
        return ResponseEntity.status(ErrorCode.PAYLOAD_TOO_LARGE.status())
                .body(ApiResponse.error(ErrorCode.PAYLOAD_TOO_LARGE));
    }

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
