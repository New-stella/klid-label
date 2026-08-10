package kr.co.cudo.authoring.common.util;

import kr.co.cudo.authoring.common.logging.LogMaskingPatterns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * URL 호스트 추출 · 자격증명 마스킹의 단일 판정기 테스트.
 *
 * <p>이 클래스는 <b>대역(사설/루프백/링크로컬)을 판정하지 않는다</b> — 대역 차단은 폐지된 정책이며
 * 여기서 되살리지 않는다. 아래 테스트는 <b>문자열 파싱</b>만 고정한다.
 */
class SafeUrlTest {

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({
            "http://vendor.example:9989/api, vendor.example",
            "https://VENDOR.Example/,        vendor.example",
            "http://my_host:9400,            my_host",          // URI#getHost() 가 null 인 경우
            "http://user:pw@my_host:9400,    my_host",
            "http://[::1]:9201,              ::1",              // IPv6 대괄호 제거
            "http://127.0.0.1:9201,          127.0.0.1"
    })
    @DisplayName("호스트를_정규화해_뽑는다 — 언더스코어·IPv6·대문자")
    void extractsHost(String url, String expected) {
        assertThat(SafeUrl.hostOf(url)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"  ", "not a url at all ::::", "vendor.example:9989/path"})
    @DisplayName("호스트를_확인할_수_없으면_null (fail-secure)")
    void returnsNullWhenHostUnknown(String url) {
        assertThat(SafeUrl.hostOf(url)).isNull();
    }

    @Test
    @DisplayName("★호스트_비교는_포트·경로·스킴_차이를_보지_않는다")
    void sameHostIgnoresPortAndPath() {
        assertThat(SafeUrl.sameHost("http://vendor.example:9989/a", "https://vendor.example/b")).isTrue();
        assertThat(SafeUrl.sameHost("http://vendor.example", "http://other.example")).isFalse();
        // 루프백의 다른 표기는 "다른 호스트"다 — 이름 해석을 하지 않는다(대역 판정 부활 아님).
        assertThat(SafeUrl.sameHost("http://localhost:1", "http://127.0.0.1:1")).isFalse();
    }

    @Test
    @DisplayName("판정_불가는_다르다로_낮춘다 (fail-secure)")
    void unknownHostIsNotSame() {
        assertThat(SafeUrl.sameHost("  ", "http://vendor.example")).isFalse();
        assertThat(SafeUrl.sameHost("http://vendor.example", null)).isFalse();
    }

    @Test
    @DisplayName("★userinfo_는_고정_표기로_가려진다 — 호스트·경로는_진단용으로_남긴다")
    void masksUserInfo() {
        assertThat(SafeUrl.maskUserInfo("http://admin:s3cr3t@vendor.example:9989/api"))
                .isEqualTo("http://***@vendor.example:9989/api");
        assertThat(SafeUrl.maskUserInfo("https://admin@vendor.example/"))
                .isEqualTo("https://***@vendor.example/");
        // 비표준 authority(언더스코어 호스트)도 놓치지 않는다.
        assertThat(SafeUrl.maskUserInfo("http://admin:pw@my_host:9400"))
                .isEqualTo("http://***@my_host:9400");
    }

    @Test
    @DisplayName("userinfo_가_없으면_원본_그대로다")
    void keepsUrlWithoutUserInfo() {
        assertThat(SafeUrl.maskUserInfo("http://vendor.example:9989/api"))
                .isEqualTo("http://vendor.example:9989/api");
    }

    /**
     * ★ 이 테스트가 마스킹 계층이 필요한 <b>이유</b>를 고정한다.
     *
     * <p>기존 로그 마스킹은 <b>키워드({@code password=}) 기반</b>이라 userinfo 형태를 겨냥한 규칙이
     * 없다. 도메인처럼 생긴 호스트에서는 <b>이메일 규칙에 우연히 걸려</b> 일부가 가려지지만
     * (그래도 첫 글자는 남는다), <b>IP·언더스코어 호스트</b>에서는 그 우연도 없어 비밀번호가
     * 통째로 평문 기록된다 — 아래가 그 실측이다.
     *
     * <p>이 단언이 깨지면(=마스커가 userinfo 를 직접 잡게 되면) 그때 이 계층을 재검토하면 된다.
     */
    @Test
    @DisplayName("★로그_마스킹_규칙_단독으로는_userinfo_를_못_잡는다 — 이_계층이_필요한_이유")
    void logMaskingAloneDoesNotCatchUserInfo() {
        // IP 호스트 — 이메일 규칙(도메인 라벨 + TLD)에 걸리지 않아 비밀번호가 통째로 남는다.
        assertThat(LogMaskingPatterns.mask("url=http://admin:s3cr3t@10.0.0.5:9300"))
                .as("키워드 기반 마스커는 userinfo 를 겨냥한 규칙이 없다")
                .contains("s3cr3t");
        // 언더스코어 호스트도 마찬가지다.
        assertThat(LogMaskingPatterns.mask("url=http://admin:s3cr3t@my_host:9400"))
                .contains("s3cr3t");
        // 도메인 호스트는 이메일 규칙에 우연히 걸려 일부만 가려진다 — 방어로 삼을 수 없다.
        assertThat(LogMaskingPatterns.mask("url=http://admin:s3cr3t@vendor.example/api"))
                .doesNotContain("s3cr3t")
                .contains("admin:s");

        // 이 계층을 통과시키면 세 경우 모두 남지 않는다.
        for (String url : new String[]{
                "http://admin:s3cr3t@10.0.0.5:9300",
                "http://admin:s3cr3t@my_host:9400",
                "http://admin:s3cr3t@vendor.example/api"}) {
            assertThat(LogMaskingPatterns.mask("url=" + SafeUrl.maskUserInfo(url)))
                    .doesNotContain("s3cr3t")
                    .doesNotContain("admin");
        }
    }

    @Test
    @DisplayName("userinfo_존재_판정 — 저장_거부의_근거")
    void detectsUserInfo() {
        assertThat(SafeUrl.hasUserInfo("http://admin:pw@vendor.example")).isTrue();
        assertThat(SafeUrl.hasUserInfo("http://admin@vendor.example")).isTrue();
        assertThat(SafeUrl.hasUserInfo("http://admin:pw@my_host:9400")).isTrue();
        assertThat(SafeUrl.hasUserInfo("http://vendor.example/path@notuserinfo")).isFalse();
        assertThat(SafeUrl.hasUserInfo(null)).isFalse();
    }
}
