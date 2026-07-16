package kr.co.cudo.authoring.common.exception;

import kr.co.cudo.authoring.common.response.ApiResponse;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2 — {@link GlobalExceptionHandler#handleDataIntegrityViolation} 원인별 분기 단위 테스트.
 *
 * <p>작업락 partial unique index(V69) 위반만 409 CONFLICT 로 유지하고, 그 외(FK/NOT NULL/제약명 불명)는
 * fail-closed 500 INTERNAL(A10:2025 예외 안전처리)로 노출하는지 검증한다. Spring 컨텍스트 없이 합성 예외로
 * 핸들러를 직접 호출한다(빠름·결정적). 응답 body 에 제약명/SQL/SQLState 가 노출되지 않는지도 검증(CWE-209).
 */
class GlobalExceptionHandlerDataIntegrityTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** PostgreSQL 은 unquoted identifier 를 소문자로 저장하므로 실제 제약명은 소문자다(V69 UX_LS_AUTH_WORK_LOCK_RAW_ACTIVE). */
    private static final String WORK_LOCK_INDEX = "ux_ls_auth_work_lock_raw_active";
    private static final String FK_CONSTRAINT = "fk_ls_data_lbl_src";

    static DataIntegrityViolationException workLockUniqueViolation() {
        SQLException sql = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"" + WORK_LOCK_INDEX + "\"\n"
                        + "  Detail: Key (data_raw_sn)=(42) already exists.", "23505");
        ConstraintViolationException hib = new ConstraintViolationException(
                "could not execute statement [ERROR: duplicate key ...]", sql, WORK_LOCK_INDEX);
        return new DataIntegrityViolationException("could not execute statement", hib);
    }

    static DataIntegrityViolationException fkViolation() {
        SQLException sql = new SQLException(
                "ERROR: insert or update on table \"ls_data_lbl\" violates foreign key constraint \""
                        + FK_CONSTRAINT + "\"", "23503");
        ConstraintViolationException hib = new ConstraintViolationException(
                "could not execute statement", sql, FK_CONSTRAINT);
        return new DataIntegrityViolationException("could not execute statement", hib);
    }

    static DataIntegrityViolationException nullConstraintName() {
        // NOT NULL 위반 — Hibernate 가 제약명을 추출하지 못하는(null) 대표 사례.
        SQLException sql = new SQLException(
                "ERROR: null value in column \"lck_id\" of relation \"ls_auth_work_lock\" "
                        + "violates not-null constraint", "23502");
        ConstraintViolationException hib = new ConstraintViolationException(
                "could not execute statement", sql, (String) null);
        return new DataIntegrityViolationException("could not execute statement", hib);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("work_lock_unique_위반은_409", workLockUniqueViolation(),
                        HttpStatus.CONFLICT, "CONFLICT"),
                Arguments.of("FK위반은_409아닌_500", fkViolation(),
                        HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"),
                Arguments.of("제약명_null이면_500", nullConstraintName(),
                        HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("DataIntegrityViolation_cause_분기_workLock만_409_그외_fail_closed_500")
    void dataIntegrityBranching(String name, DataIntegrityViolationException ex,
                                HttpStatus expected, String expectedCode) {
        ResponseEntity<ApiResponse<Void>> res = handler.handleDataIntegrityViolation(ex);

        assertThat(res.getStatusCode().value()).isEqualTo(expected.value());
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().errorCode()).isEqualTo(expectedCode);
        assertThat(res.getBody().success()).isFalse();
    }

    @Test
    @DisplayName("DataIntegrity_응답에_제약명_SQL_미포함")
    void responseHasNoConstraintNameOrSql() {
        List<DataIntegrityViolationException> all =
                List.of(workLockUniqueViolation(), fkViolation(), nullConstraintName());
        for (DataIntegrityViolationException ex : all) {
            ResponseEntity<ApiResponse<Void>> res = handler.handleDataIntegrityViolation(ex);
            String msg = res.getBody().message();

            assertThat(msg)
                    .as("응답 메시지에 제약명/SQL/SQLState/원문이 노출되면 안 된다 (CWE-209)")
                    .doesNotContain(WORK_LOCK_INDEX)
                    .doesNotContain(FK_CONSTRAINT)
                    .doesNotContainIgnoringCase("constraint")
                    .doesNotContainIgnoringCase("sql")
                    .doesNotContain("23505")
                    .doesNotContain("23503")
                    .doesNotContain("23502")
                    .doesNotContain("duplicate key")
                    .doesNotContain("null value in column");
        }
    }
}
