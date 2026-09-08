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

    /**
     * ⚠ <b>구 시험 폐기(2026-09-08)</b> — {@code 하이픈이_있으면_거부한다} 가 있었고 근거를
     * <i>「서킷 이름 ai-batch-&#123;SRVR_ID&#125; 의 구분자와 충돌한다」</i>로 적었다. 규칙이
     * <b>하이픈·밑줄 허용</b>으로 넓혀졌고(2026-09-08 사용자 확정 · V36), 그 근거도 성립하지 않는다 —
     * 서킷 이름에 노드 축이 실제로 붙지 않는다({@code AiWorkload#circuitName()} 은 용도 축만 조립).
     * 아래 시험이 그 자리를 대신한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"gpu-01", "klid-ai-gpu-01", "infer_gpu2", "vlm-01", "a-b_c",
            "-gpu01", "gpu01-", "_gpu01", "gpu01_", "-", "_"})
    @DisplayName("★하이픈과_밑줄은_맨앞_맨뒤에_와도_통과한다_구_거부_폐기")
    void 하이픈과_밑줄은_맨앞_맨뒤에_와도_통과한다(String srvrId) {
        // 자리로 제한하면 규칙과 오류 문구가 길어지는데 막아서 얻는 것이 없다 — 걸러야 할 것
        // (공백·개행·제어문자)은 아래 시험들이 그대로 막는다.
        assertThat(AiSrvrIdPolicy.isValid(srvrId)).isTrue();
    }

    @Test
    @DisplayName("★넓힌_뒤에도_기존_식별자는_전부_유효하다_상위집합이다")
    void 넓힌_뒤에도_기존_식별자는_전부_유효하다() {
        // 새 형식은 옛 형식의 상위집합이라 이 변경으로 못 쓰게 되는 식별자가 없다.
        assertThat(AiSrvrIdPolicy.isValid("gpu01")).isTrue();
        assertThat(AiSrvrIdPolicy.isValid("default01")).isTrue();
        assertThat(AiSrvrIdPolicy.isValid("timeseries01")).isTrue();
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
        // ⚠ gpu_01 은 2026-09-08 부터 <통과>한다(위 하이픈·밑줄 시험). 여기서 뺀 것이 의도다.
        assertThat(AiSrvrIdPolicy.isValid("gpu.01")).isFalse();
        assertThat(AiSrvrIdPolicy.isValid("gpu/01")).isFalse();
        assertThat(AiSrvrIdPolicy.isValid("gpu:01")).isFalse();
    }

    @Test
    @DisplayName("정규식_상수와_최대길이_상수가_같은_규칙을_가리킨다")
    void 정규식_상수와_최대길이_상수가_같은_규칙을_가리킨다() {
        // DB CHECK 제약이 이 문자열을 그대로 쓰므로 둘이 갈리면 두 번째 진실원이 생긴다.
        assertThat(AiSrvrIdPolicy.SRVR_ID_REGEX)
                .isEqualTo("^[a-z0-9_-]{1," + AiSrvrIdPolicy.SRVR_ID_MAX_LENGTH + "}$");
    }
}
