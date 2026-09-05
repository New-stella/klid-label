package kr.co.cudo.authoring.aiserver.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 서버 식별자 형식 판정의 <b>단일 진실원</b> 검증. [@design ADR-057]
 *
 * <p>이 값은 뒤에서 서킷브레이커 이름 {@code ai-batch-{SRVR_ID}} 와 메트릭 라벨로 조립된다.
 * 하이픈이 섞이면 실제 호스트명(예: {@code klid-ai-gpu-01})이 그대로 들어가 <b>어디서 갈리는지
 * 파싱으로 복원되지 않고</b> 라벨이 조용히 어긋난다 — 그래서 형식을 입구에서 막는다.
 */
class AiSrvrIdPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "gpu01", "default01", "0", "abcdefghij0123456789"})
    @DisplayName("소문자와_숫자로만_이루어진_스무자_이하_아이디는_통과한다")
    void 소문자와_숫자로만_이루어진_스무자_이하_아이디는_통과한다(String srvrId) {
        assertThat(AiSrvrIdPolicy.isValid(srvrId)).isTrue();
    }

    @Test
    @DisplayName("하이픈이_있으면_거부한다")
    void 하이픈이_있으면_거부한다() {
        // 서킷 이름 ai-batch-{SRVR_ID} 의 구분자와 충돌한다.
        assertThat(AiSrvrIdPolicy.isValid("gpu-01")).isFalse();
        assertThat(AiSrvrIdPolicy.isValid("klid-ai-gpu-01")).isFalse();
    }

    @Test
    @DisplayName("대문자가_있으면_거부한다")
    void 대문자가_있으면_거부한다() {
        assertThat(AiSrvrIdPolicy.isValid("GPU01")).isFalse();
        assertThat(AiSrvrIdPolicy.isValid("gpU01")).isFalse();
    }

    @Test
    @DisplayName("빈값과_널은_거부한다")
    void 빈값과_널은_거부한다() {
        assertThat(AiSrvrIdPolicy.isValid(null)).isFalse();
        assertThat(AiSrvrIdPolicy.isValid("")).isFalse();
        assertThat(AiSrvrIdPolicy.isValid("   ")).isFalse();
    }

    @Test
    @DisplayName("컬럼_폭을_넘는_스물한자는_거부한다")
    void 컬럼_폭을_넘는_스물한자는_거부한다() {
        // 입구에서 막지 않으면 INSERT 시점 DB 오류(500)가 된다.
        assertThat(AiSrvrIdPolicy.isValid("a".repeat(AiSrvrIdPolicy.SRVR_ID_MAX_LENGTH))).isTrue();
        assertThat(AiSrvrIdPolicy.isValid("a".repeat(AiSrvrIdPolicy.SRVR_ID_MAX_LENGTH + 1))).isFalse();
    }

    @Test
    @DisplayName("공백과_제어문자가_섞이면_거부한다")
    void 공백과_제어문자가_섞이면_거부한다() {
        // 이 값은 로그와 메트릭 라벨에 그대로 실린다 — 개행이 섞이면 로그 인젝션(CWE-117)이다.
        assertThat(AiSrvrIdPolicy.isValid("gpu 01")).isFalse();
        assertThat(AiSrvrIdPolicy.isValid("gpu\n01")).isFalse();
        assertThat(AiSrvrIdPolicy.isValid("gpu\t01")).isFalse();
        assertThat(AiSrvrIdPolicy.isValid("gpu\r\n01")).isFalse();
    }

    @Test
    @DisplayName("한글과_특수문자는_거부한다")
    void 한글과_특수문자는_거부한다() {
        assertThat(AiSrvrIdPolicy.isValid("추론01")).isFalse();
        assertThat(AiSrvrIdPolicy.isValid("gpu_01")).isFalse();
        assertThat(AiSrvrIdPolicy.isValid("gpu.01")).isFalse();
    }

    @Test
    @DisplayName("정규식_상수와_최대길이_상수가_같은_규칙을_가리킨다")
    void 정규식_상수와_최대길이_상수가_같은_규칙을_가리킨다() {
        // DB CHECK 제약이 이 문자열을 그대로 쓰므로 둘이 갈리면 두 번째 진실원이 생긴다.
        assertThat(AiSrvrIdPolicy.SRVR_ID_REGEX)
                .isEqualTo("^[a-z0-9]{1," + AiSrvrIdPolicy.SRVR_ID_MAX_LENGTH + "}$");
    }
}
