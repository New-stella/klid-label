package kr.co.cudo.authoring.sysconfig.endpoint;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운영자 입력 연동 주소 검증 (R11).
 *
 * <p>검증 범위는 <b>스키마와 형식뿐</b>이다. <b>IP 대역 차단은 폐지</b>됐으므로(2026-08-10 사용자 확정)
 * 아래 대역 케이스들은 <b>삭제하지 않고 기대값을 뒤집어</b> "대역으로 막지 않는다"를 계약으로 고정한다 —
 * 지우면 다음 사람이 방어가 원래 없었다고 오해하고 되살린다.
 */
class IntegrationEndpointUrlValidatorTest {

    private final IntegrationEndpointUrlValidator validator = new IntegrationEndpointUrlValidator();

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "file:///etc/passwd",
            "ftp://vendor.example.net/",
            "javascript:alert(1)",
            "gopher://8.8.8.8/",
            "//8.8.8.8/no-scheme",
            "8.8.8.8"
    })
    @DisplayName("http_https_가_아닌_스킴은_400")
    void rejectsNonHttpSchemes(String url) {
        assertThatThrownBy(() -> validator.validateForSave(IntegrationEndpoint.AI_SERVER, url))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    /**
     * ★ <b>기대값이 뒤집힌 케이스</b> — 구 동작은 400 이었다(대역 차단). 2026-08-10 사용자 확정으로
     * <b>대역 차단이 폐지</b>되어 이제 전부 통과한다.
     *
     * <p>근거: 연동 4종은 실제로 <b>내부망의 별도 서버</b>에 있을 가능성이 높아 대역으로 막으면
     * 정당한 대상을 막는다. 망 통제는 인프라 계층 책임이다.
     *
     * <p>이 케이스를 지우지 않는 이유 — 지우면 다음 사람이 "원래 대역 방어가 없었다"고 오해하고
     * 되살린다. <b>"막지 않는다"가 계약</b>임을 여기서 못박는다.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "http://127.0.0.1:9200",
            "http://127.10.20.30/",
            "https://10.0.0.5",
            "https://10.255.255.254",
            "https://172.16.0.1",
            "https://172.31.255.254",
            "https://192.168.1.10",
            "http://169.254.169.254/latest/meta-data/",   // 클라우드 메타데이터
            "https://169.254.0.1"
    })
    @DisplayName("★내부망_대역_IP도_통과한다 — 대역_차단_폐지(구 400 → 통과)")
    void doesNotBlockByNetworkRange(String url) {
        assertThatCode(() -> validator.validateForSave(IntegrationEndpoint.CONTROL_NOTIFY, url))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★내부_IP로_해석되는_도메인도_통과한다 — 이름_해석을_하지_않는다(구 400 → 통과)")
    void doesNotResolveHostNames() {
        // 구 동작은 localhost 를 해석해 loopback 이라 400 이었다. 이제 해석 자체를 하지 않는다
        // (그래서 DNS 재해석에 의존하던 전송 직전 재검증도 함께 폐지됐다).
        assertThatCode(() -> validator.validateForSave(IntegrationEndpoint.VLM, "https://localhost:9400"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★거부_사유에_입력_원문·호스트를_싣지_않는다 (CWE-117/209)")
    void doesNotEchoInput() {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> validator.validateForSave(IntegrationEndpoint.VLM, "ftp://secret-internal-host.example/x"));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage())
                .doesNotContain("secret-internal-host")
                .doesNotContain("ftp://")
                .doesNotContain("vlm.client.url");
    }

    @Test
    @DisplayName("남은_사유는_형식·스킴_한_갈래다 — 화면_안내가_두_분류로_줄었다")
    void schemeAndFormatShareOneCategory() {
        String scheme = org.assertj.core.api.Assertions.catchThrowable(
                () -> validator.validateForSave(IntegrationEndpoint.AI_SERVER, "ftp://vendor.example.net/"))
                .getMessage();

        assertThat(scheme).isEqualTo(IntegrationEndpointUrlValidator.MSG_SCHEME);
        // 대역 차단 문구는 더 이상 존재하지 않는다.
        assertThat(scheme).doesNotContain("내부망");
    }

    @Test
    @DisplayName("공인_주소는_통과한다 — http_와_https_모두")
    void acceptsPublicAddresses() {
        assertThatCode(() -> validator.validateForSave(IntegrationEndpoint.AI_SERVER, "https://8.8.8.8/"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateForSave(IntegrationEndpoint.AI_SERVER, "http://8.8.4.4:9300"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("빈값은_400")
    void rejectsBlank() {
        assertThatThrownBy(() -> validator.validateForSave(IntegrationEndpoint.AI_SERVER, "  "))
                .isInstanceOf(CustomException.class);
    }

    /**
     * ★ MED-3 — {@code http://user:pass@host} 를 거부한다.
     *
     * <p>이 값은 감사 로그에 원문으로 기록되는데 로그 마스킹은 키워드({@code password=}) 기반이라
     * 이 형태를 <b>잡지 못한다</b>(실측). 즉 자격증명이 평문으로 남는다(CWE-532).
     *
     * <p>⚠ <b>대역 차단 부활이 아니다</b> — 주소가 어디를 가리키는지는 여전히 보지 않는다.
     * 막는 것은 "주소에 자격증명을 끼워 넣는 것" 하나다.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "http://admin:s3cr3t@vendor.example/",
            "https://admin@vendor.example:9989/",
            "http://user:pw@10.0.0.5:9300",
            "http://user:pw@my_host:9400"   // 언더스코어 호스트(비표준 authority)도 놓치지 않는다
    })
    @DisplayName("★주소에_자격증명(userinfo)이_실려_있으면_400 (CWE-532)")
    void rejectsUserInfo(String url) {
        assertThatThrownBy(() -> validator.validateForSave(IntegrationEndpoint.DEIDENTIFY, url))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("★자격증명_거부_문구는_고정이고_입력을_되돌려주지_않는다")
    void userInfoRejectionDoesNotEchoCredentials() {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> validator.validateForSave(IntegrationEndpoint.VLM,
                        "http://admin:s3cr3t@vendor.example/"));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).isEqualTo(IntegrationEndpointUrlValidator.MSG_USERINFO);
        assertThat(thrown.getMessage()).doesNotContain("s3cr3t").doesNotContain("admin");
    }

    /**
     * ★ LOW-10 — {@code URI#getHost()} 는 언더스코어 호스트에 {@code null} 을 준다. 그것만 보고
     * 400 을 내면 도커 컴포즈 서비스명({@code klid_mock_server} 등)을 주소로 넣을 수 없다.
     * {@code DeidentifyEndpointTrustGuard} 가 이미 쓰던 authority 폴백을 같은 판정기에서 공유한다.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "http://my_host:9400",
            "http://ai_server:9300/",
            "https://kpst_deid:9989/api"
    })
    @DisplayName("★언더스코어_호스트도_통과한다 — 도커_컴포즈_서비스명(구 400 → 통과)")
    void acceptsUnderscoreHosts(String url) {
        assertThatCode(() -> validator.validateForSave(IntegrationEndpoint.AI_SERVER, url))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("호스트가_아예_없으면_여전히_400")
    void rejectsMissingHost() {
        assertThatThrownBy(() -> validator.validateForSave(IntegrationEndpoint.AI_SERVER, "http:///path"))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("과대_길이_입력은_400 — 응답에_입력_원문을_되돌려주지_않는다 (CWE-117/770)")
    void rejectsOverlongInput() {
        String overlong = "https://8.8.8.8/" + "a".repeat(IntegrationEndpointUrlValidator.MAX_URL_LENGTH);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> validator.validateForSave(IntegrationEndpoint.AI_SERVER, overlong));

        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(thrown.getMessage()).doesNotContain("aaaa");
    }
}
