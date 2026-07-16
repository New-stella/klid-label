package kr.co.cudo.authoring.common.exception;

import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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

    /**
     * V69 배타 작업락 partial unique index 명. PostgreSQL 은 unquoted identifier 를 소문자로 저장하므로
     * 실제 제약명은 소문자(`ux_ls_auth_work_lock_raw_active`)로 관측된다(정합성은 WorkLockDataIntegrityIT 가 실증).
     * <p>common 레이어가 auth 엔티티에 역참조하지 않도록 마이그레이션 상수를 여기 로컬로 고정한다
     * (마이그레이션 파일은 불변이라 drift 위험 없음).
     */
    private static final String WORK_LOCK_RAW_ACTIVE_UNIQUE_INDEX = "ux_ls_auth_work_lock_raw_active";

    private static final java.util.regex.Pattern CONSTRAINT_IN_MESSAGE =
            java.util.regex.Pattern.compile("constraint\\s+\"([^\"]+)\"");

    /**
     * DB 무결성 제약 위반을 <b>원인별로 분기</b>한다 (R2, A10:2025 예외 안전처리).
     * <ul>
     *   <li><b>작업락 partial unique index(V69) 위반만 409 CONFLICT</b> — 배타 작업락 check-then-act 경합에서
     *       패자의 락 INSERT 가 원자적으로 거부되는 정상적 동시성 충돌(트랙 병합·재비식별 동시 요청).</li>
     *   <li><b>그 외(FK/NOT NULL/기타/제약명 판별 불가): fail-closed 500 INTERNAL</b> — 알 수 없는 무결성
     *       위반을 조용히 409 로 흡수하면 실제 결함을 은폐하므로, 판별 실패는 500 으로 노출한다.</li>
     * </ul>
     * (CWE-209 가드 — 제약명/SQL/SQLState/스택은 응답 body 에 노출하지 않고 {@link ErrorCode} 고정 메시지만
     * 반환한다. 서버 로그에는 제약명까지만 남기고 SQL 원문·스택은 남기지 않는다.)
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        String constraintName = extractConstraintName(e);
        if (isWorkLockRawActiveUnique(constraintName)) {
            log.warn("[Exception] work-lock unique violation constraint={}", constraintName);
            return ResponseEntity.status(ErrorCode.CONFLICT.status())
                    .body(ApiResponse.error(ErrorCode.CONFLICT, "이미 처리 중이거나 충돌하는 요청입니다."));
        }
        // fail-closed: 판별 실패/기타 제약 위반은 500 으로 노출 (조용한 409 흡수 금지).
        log.warn("[Exception] unclassified data integrity violation constraint={} cause={}",
                constraintName, e.getMostSpecificCause().getClass().getSimpleName());
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }

    private boolean isWorkLockRawActiveUnique(String constraintName) {
        return constraintName != null
                && WORK_LOCK_RAW_ACTIVE_UNIQUE_INDEX.equalsIgnoreCase(constraintName);
    }

    /**
     * 원인 체인에서 제약명을 정확 추출한다. Hibernate
     * {@link org.hibernate.exception.ConstraintViolationException#getConstraintName()} 를 우선 사용하고,
     * null 이면 최심 원인(SQLException) 메시지의 {@code constraint "..."} 토큰에서 파싱한다.
     */
    private String extractConstraintName(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException hib) {
                String name = hib.getConstraintName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        }
        Throwable root = e.getMostSpecificCause();
        String msg = root != null ? root.getMessage() : null;
        if (msg == null) {
            return null;
        }
        var m = CONSTRAINT_IN_MESSAGE.matcher(msg);
        return m.find() ? m.group(1) : null;
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
