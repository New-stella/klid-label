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

    /**
     * V119 프리셋 labelId 중복 방지 부분 유니크 인덱스명. PostgreSQL 은 unquoted identifier 를 소문자로
     * 저장하므로 실제 제약명은 소문자(`uk_ls_label_preset_code_lblid`)로 관측된다. 동일 프리셋에 대한 동시
     * PUT 경합에서 패자의 코드 INSERT 가 원자적으로 거부되는 정상적 동시성 충돌이다.
     */
    private static final String PRESET_CODE_LABELID_UNIQUE_INDEX = "uk_ls_label_preset_code_lblid";

    /**
     * V120 라벨 마스터 이름 대소문자/공백 무시 부분 유니크 인덱스명. PostgreSQL 은 unquoted identifier 를
     * 소문자로 저장하므로 실제 인덱스명은 소문자(`uk_ls_label_nm_ci`)로 관측된다. 동일 라벨명(정규화 기준)에
     * 대한 동시 생성 경합에서 패자의 INSERT 가 원자적으로 거부되는 정상적 동시성 충돌이다.
     */
    private static final String LABEL_NAME_CI_UNIQUE_INDEX = "uk_ls_label_nm_ci";

    /**
     * V129 라벨 마스터 검출유형(COCO 매핑) 부분 유니크 인덱스명. PostgreSQL 은 unquoted identifier 를
     * 소문자로 저장하므로 실제 인덱스명은 소문자(`uk_ls_label_dtct_type`)로 관측된다. 활성 라벨 중 동일
     * COCO 클래스 매핑 동시 생성 경합에서 패자의 INSERT/UPDATE 가 원자적으로 거부되는 정상적 동시성 충돌이다.
     */
    private static final String LABEL_DTCT_TYPE_UNIQUE_INDEX = "uk_ls_label_dtct_type";

    /**
     * V14 외부 분류 대응(LS_OTSD_CTGRY_MPNG) 종류+외부분류 유일 인덱스명. PostgreSQL 은 unquoted identifier 를
     * 소문자로 저장하므로 실제 인덱스명은 소문자(`uk_ls_otsd_ctgry_mpng`)로 관측된다. 대응 확정은 선조회 후
     * INSERT(check-then-act)라 2노드 동시 요청에서 패자의 INSERT 가 원자적으로 거부되는 정상적 동시성 충돌이며,
     * 단독 요청에서 같은 상황은 이미 서비스가 409 로 답한다 — 여기서 500 으로 떨어지면 같은 사유에 두 코드가 난다.
     */
    private static final String IMPORT_CATEGORY_MAPPING_UNIQUE_INDEX = "uk_ls_otsd_ctgry_mpng";

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
        if (isPresetCodeLabelIdUnique(constraintName)) {
            log.warn("[Exception] preset labelId unique violation constraint={}", constraintName);
            return ResponseEntity.status(ErrorCode.CONFLICT.status())
                    .body(ApiResponse.error(ErrorCode.CONFLICT, "이미 처리 중이거나 충돌하는 요청입니다."));
        }
        if (isLabelNameCiUnique(constraintName)) {
            log.warn("[Exception] label name ci-unique violation constraint={}", constraintName);
            return ResponseEntity.status(ErrorCode.CONFLICT.status())
                    .body(ApiResponse.error(ErrorCode.CONFLICT, "이미 사용 중인 라벨 이름입니다."));
        }
        if (isLabelDtctTypeUnique(constraintName)) {
            log.warn("[Exception] label dtct-type unique violation constraint={}", constraintName);
            return ResponseEntity.status(ErrorCode.CONFLICT.status())
                    .body(ApiResponse.error(ErrorCode.CONFLICT, "이미 사용 중인 검출 클래스 매핑입니다."));
        }
        if (isImportCategoryMappingUnique(constraintName)) {
            log.warn("[Exception] import category mapping unique violation constraint={}", constraintName);
            return ResponseEntity.status(ErrorCode.CONFLICT.status())
                    .body(ApiResponse.error(ErrorCode.CONFLICT, "같은 종류·같은 이름의 대응이 이미 있습니다."));
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

    private boolean isPresetCodeLabelIdUnique(String constraintName) {
        return constraintName != null
                && PRESET_CODE_LABELID_UNIQUE_INDEX.equalsIgnoreCase(constraintName);
    }

    private boolean isLabelNameCiUnique(String constraintName) {
        return constraintName != null
                && LABEL_NAME_CI_UNIQUE_INDEX.equalsIgnoreCase(constraintName);
    }

    private boolean isLabelDtctTypeUnique(String constraintName) {
        return constraintName != null
                && LABEL_DTCT_TYPE_UNIQUE_INDEX.equalsIgnoreCase(constraintName);
    }

    private boolean isImportCategoryMappingUnique(String constraintName) {
        return constraintName != null
                && IMPORT_CATEGORY_MAPPING_UNIQUE_INDEX.equalsIgnoreCase(constraintName);
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

    /**
     * 경로는 존재하나 HTTP 메서드가 매핑되지 않은 요청 — <b>405</b>.
     *
     * <p>이 핸들러가 없으면 {@code @ExceptionHandler(Exception.class)} 로 떨어져 <b>500 + ERROR 스택트레이스</b>
     * 가 된다(2026-07-30 배포 검증에서 실측). 클라이언트에는 "요청이 잘못됐다"가 아니라 "서버 장애"로 보이고,
     * 모니터링 로그에는 정상 오요청이 장애와 섞여 쌓인다. 형제 케이스인
     * {@link NoResourceFoundException}(경로 자체 부재 → 404)은 이미 전용 핸들러가 있는데 메서드 불일치만
     * 빠져 있었다.
     *
     * <p>{@code Allow} 헤더는 RFC 9110 이 405 응답에 <b>필수</b>로 요구한다 — 스프링이 예외에 담아 준
     * 지원 메서드 집합을 그대로 싣는다. 로그는 WARN(정상 오요청) 이며 메서드명만 남긴다 — 경로에는 식별자·
     * 쿼리스트링이 실릴 수 있어 원문을 남기지 않는다(CWE-117/209).
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        log.warn("[Exception] method not allowed method={}", e.getMethod());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.status());
        java.util.Set<org.springframework.http.HttpMethod> supported = e.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            builder.allow(supported.toArray(new org.springframework.http.HttpMethod[0]));
        }
        return builder.body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        log.error("[Exception] unhandled exception", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }
}
