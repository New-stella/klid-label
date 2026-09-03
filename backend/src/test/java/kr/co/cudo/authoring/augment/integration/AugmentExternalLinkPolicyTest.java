package kr.co.cudo.authoring.augment.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 증강 연동 여부 판정 — 축은 <b>위탁 주소 주입</b> 하나다 (2026-09-03 확정).
 *
 * <p>이 판정은 <b>위탁 WebClient 의 base-url 과 짝</b>이다. 두 자리가 다른 프로퍼티를 보면
 * "판정은 연동인데 클라이언트가 보는 주소는 비었다" 같은 어긋남이 열린다.
 */
class AugmentExternalLinkPolicyTest {

    @ParameterizedTest(name = "주소=[{0}] → 미연동")
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("위탁_주소가_비면_미연동이다")
    void blankBaseUrlIsNotLinked(String baseUrl) {
        assertThat(new AugmentExternalLinkPolicy(baseUrl).isNotLinked()).isTrue();
    }

    @Test
    @DisplayName("위탁_주소가_null_이어도_미연동이다")
    void nullBaseUrlIsNotLinked() {
        assertThat(new AugmentExternalLinkPolicy(null).isNotLinked()).isTrue();
    }

    @ParameterizedTest(name = "주소=[{0}] → 연동")
    @ValueSource(strings = {
            "http://klid-mock-server:9400",
            "https://augment.vendor.internal",
            "http://10.0.0.5:9400"})
    @DisplayName("위탁_주소가_주입되면_연동이다")
    void injectedBaseUrlIsLinked(String baseUrl) {
        assertThat(new AugmentExternalLinkPolicy(baseUrl).isNotLinked()).isFalse();
    }

    @Test
    @DisplayName("★판정_키는_위탁_WebClient_가_읽는_키와_같다")
    void judgementKeyMatchesClientKey() {
        // 두 자리가 다른 키를 보면 판정과 배선이 갈린다 — 같은 키임을 상수로 고정한다.
        assertThat(AugmentExternalLinkPolicy.KEY_BASE_URL)
                .isEqualTo("authoring.augment.external.base-url");
    }
}
