package kr.co.cudo.authoring.dev.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AutolabelTestRequest} Bean Validation 검증 (CWE-20 / CWE-22).
 *
 * <p>① eventTypeCd — 관제 EV-코드 형식(EV + 숫자 8자리)만 허용(Phase 5). @Pattern 은 형식만 1차
 * 가드하고 실제 관제 등록 여부는 서비스({@code EventTypeService.categoryKeyOf})가 검증한다.
 *
 * <p>② Phase 3 관제 전체 컬럼 확장 — <b>신규 필드는 전부 optional</b> 이다. optional 이라고 검증이
 * 없으면 관제 컬럼 길이 초과·경로 순회가 그대로 통과하므로, 값이 있을 때의 타입·길이·패턴 상한을
 * 여기서 고정한다. 특히 {@code vmsClipId} 는 <b>저장 파일명이 되므로</b> allowlist 로만 통과시킨다.
 */
class AutolabelTestRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    // ------------------------------------------------------------------ eventTypeCd

    @ParameterizedTest
    @ValueSource(strings = {"EV02000201", "EV05000101", "EV03000101", "EV05000701", "EV01000101", "EV02000101"})
    @DisplayName("관제_EV코드_형식은_eventTypeCd_검증_통과")
    void allow_ev_codes(String code) {
        Set<ConstraintViolation<AutolabelTestRequest>> v = validator.validate(sample(code));
        assertThat(v).noneMatch(c -> c.getPropertyPath().toString().equals("eventTypeCd"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EVT_FALL", "EVT_ABNORMAL", "EV0200020", "EV020002012", "EV0200020A", "ev02000201", "FOO_BAR"})
    @DisplayName("구_EVT_코드와_형식위반은_eventTypeCd_검증_실패")
    void reject_invalid_format(String code) {
        Set<ConstraintViolation<AutolabelTestRequest>> v = validator.validate(sample(code));
        assertThat(v).anyMatch(c -> c.getPropertyPath().toString().equals("eventTypeCd"));
    }

    @Test
    @DisplayName("빈_문자열_eventTypeCd는_NotBlank_위반")
    void reject_blank() {
        Set<ConstraintViolation<AutolabelTestRequest>> v = validator.validate(sample(""));
        assertThat(v).anyMatch(c -> c.getPropertyPath().toString().equals("eventTypeCd"));
    }

    @Test
    @DisplayName("정상_필드_조합은_검증_통과_위반_없음")
    void valid_request_passes() {
        assertThat(validator.validate(sample("EV02000201"))).isEmpty();
    }

    // ------------------------------------------------------------------ Phase 3 신규 필드

    @Test
    @DisplayName("필수_6필드만_있으면_통과하고_신규필드는_optional")
    void minimalRequestIsValid() {
        assertThat(validator.validate(minimal("CLP-abc123"))).isEmpty();
        // clipId 도 optional — 미전송 시 서버가 생성한다.
        assertThat(validator.validate(minimal(null))).isEmpty();
    }

    @Test
    @DisplayName("clipId_형식위반이면_400을_반환한다")
    void rejectsMalformedClipId() {
        // 31자 (CLP- + 27자) — 상한 초과
        assertThat(validator.validate(minimal("CLP-" + "a".repeat(27)))).isNotEmpty();
        // 경로 순회 문자 (CWE-22 — clipId 가 파일명이 된다)
        assertThat(validator.validate(minimal("CLP-../../etc/passwd"))).isNotEmpty();
        assertThat(validator.validate(minimal("CLP-a/b"))).isNotEmpty();
        // CLP- 접두 없음
        assertThat(validator.validate(minimal("TEST-CLIP-001"))).isNotEmpty();
        // 경계값 — CLP- + 26자 = 30자는 허용
        assertThat(validator.validate(minimal("CLP-" + "a".repeat(26)))).isEmpty();
    }

    @Test
    @DisplayName("evntId_는_50자_초과나_경로문자면_거절된다")
    void rejectsMalformedEvntId() {
        assertThat(validator.validate(full("DEV-abc_123", null, null, null, null))).isEmpty();
        assertThat(validator.validate(full("a".repeat(51), null, null, null, null))).isNotEmpty();
        assertThat(validator.validate(full("../etc", null, null, null, null))).isNotEmpty();
    }

    @Test
    @DisplayName("YN_플래그_필드는_Y_또는_N_만_허용한다")
    void rejectsNonYnFlags() {
        assertThat(validator.validate(full(null, "Y", null, null, null))).isEmpty();
        assertThat(validator.validate(full(null, "N", null, null, null))).isEmpty();
        assertThat(validator.validate(full(null, "X", null, null, null))).isNotEmpty();
        assertThat(validator.validate(full(null, "YES", null, null, null))).isNotEmpty();
    }

    @Test
    @DisplayName("길이_상한_초과_문자열은_거절된다")
    void rejectsOverLengthStrings() {
        assertThat(validator.validate(full(null, null, "a".repeat(20), null, null))).isEmpty();
        assertThat(validator.validate(full(null, null, "a".repeat(21), null, null))).isNotEmpty();
    }

    @Test
    @DisplayName("음수_영상길이는_거절된다")
    void rejectsNegativeNumbers() {
        assertThat(validator.validate(full(null, null, null, -1, null))).isNotEmpty();
        assertThat(validator.validate(full(null, null, null, 0, null))).isEmpty();
    }

    @Test
    @DisplayName("crtType_은_0_또는_1_만_허용한다")
    void rejectsOutOfRangeCrtType() {
        assertThat(validator.validate(full(null, null, null, null, 0))).isEmpty();
        assertThat(validator.validate(full(null, null, null, null, 1))).isEmpty();
        assertThat(validator.validate(full(null, null, null, null, 2))).isNotEmpty();
        assertThat(validator.validate(full(null, null, null, null, -1))).isNotEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private AutolabelTestRequest sample(String eventTypeCd) {
        return new AutolabelTestRequest(
                "CLP-testclip001", "CCTV-001", eventTypeCd, "11680", "ANONY",
                Instant.parse("2026-05-01T12:00:00Z"));
    }

    private AutolabelTestRequest minimal(String clipId) {
        return new AutolabelTestRequest(clipId, "CCTV-001", "EV02000201", "1168000000",
                "ANONY", Instant.parse("2024-05-01T12:00:00Z"));
    }

    private AutolabelTestRequest full(String evntId, String jobDmndYn, String clipSttsCd,
                                      Integer vdoLenSec, Integer crtType) {
        return new AutolabelTestRequest(
                "CLP-abc123", "CCTV-001", "EV02000201", "1168000000", "ANONY",
                Instant.parse("2024-05-01T12:00:00Z"),
                evntId, null, null, null, null, vdoLenSec, clipSttsCd, jobDmndYn, null, crtType,
                null, null, null, null, null, null, null, null);
    }
}
