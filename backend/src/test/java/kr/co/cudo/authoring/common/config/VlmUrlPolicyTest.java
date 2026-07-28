package kr.co.cudo.authoring.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link VlmUrlPolicy} — VLM base-url 검증 정책의 <b>프로파일 분리 + prd 누수 차단</b> 단위 테스트.
 *
 * <h3>왜 인라인 분기가 아니라 별도 정책 빈인가</h3>
 * <p>{@code if (profile == local) skip} 형태의 인라인 완화는 프로파일 오지정 한 번으로 운영에서 그대로
 * 샌다. 본 정책은 ① 완화를 <b>전용 프로퍼티</b>({@code vlm.client.allow-insecure-url})로 격리하고
 * ② 완화를 <b>허용 프로파일 allowlist</b>(local/dev)에서만 인정하며 ③ 그 밖의 프로파일에서 플래그가
 * 켜져 있으면 <b>기동 자체를 실패</b>시킨다. 따라서 "설정만 바꿔서 prd 를 뚫는" 경로가 없다.
 */
class VlmUrlPolicyTest {

    private static VlmUrlPolicy policy(String activeProfile, boolean allowInsecure) {
        MockEnvironment env = new MockEnvironment();
        if (activeProfile != null) {
            env.setActiveProfiles(activeProfile.split(","));
        }
        return new VlmUrlPolicy(env, allowInsecure);
    }

    @Test
    @DisplayName("VLM_클라이언트가_평문_HTTP_사설IP_목업_URL_로_local_에서_정상_기동한다")
    void localAcceptsPlaintextPrivateMockUrl() {
        // given — local 프로파일 + 완화 플래그 ON (docker 목업: 평문 http + 사설 IP 로 resolve)
        VlmUrlPolicy p = policy("local", true);
        // when / then — 기동 검증 통과 + URL 검증 통과 (부팅 크래시 없음)
        assertThatCode(p::verifyRelaxationScope).doesNotThrowAnyException();
        assertThatCode(() -> p.validate("http://klid-mock-server:9400")).doesNotThrowAnyException();
        assertThatCode(() -> p.validate("http://127.0.0.1:9400")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("local_프로파일에서는_평문_HTTP_가_WARN_과_함께_허용된다")
    void localAllowsPlaintextHttp() {
        VlmUrlPolicy p = policy("local", true);
        assertThatCode(() -> p.validate("http://10.20.30.40:9400")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prd_프로파일에서_평문_HTTP_VLM_URL_이면_기동이_실패한다")
    void prdRejectsPlaintextHttp() {
        VlmUrlPolicy p = policy("prd", false);
        assertThatThrownBy(() -> p.validate("http://vlm.vendor.io"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("prd_프로파일에서_사설IP_VLM_URL_이면_기동이_실패한다")
    void prdRejectsPrivateNetwork() {
        VlmUrlPolicy p = policy("prd", false);
        assertThatThrownBy(() -> p.validate("https://10.0.0.5"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("내부");
        assertThatThrownBy(() -> p.validate("https://127.0.0.1:9400"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.validate("https://169.254.169.254"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("prd_프로파일에서_완화_플래그가_켜져있으면_기동이_실패한다")
    void prdRejectsRelaxationFlag() {
        VlmUrlPolicy p = policy("prd", true);
        assertThatThrownBy(p::verifyRelaxationScope)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allow-insecure-url");
    }

    @Test
    @DisplayName("완화플래그가_켜져도_prd_에서는_URL_검증이_엄격정책으로_유지된다")
    void prdKeepsStrictValidationEvenWithFlag() {
        // 기동 assert 를 우회하더라도(플래그만 조작) 검증 자체가 완화되지 않는다 — 이중 방어.
        VlmUrlPolicy p = policy("prd", true);
        assertThatThrownBy(() -> p.validate("http://klid-mock-server:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("완화는_local_dev_외_프로파일_에서는_인정되지_않는다_allowlist_fail_closed")
    void relaxationOnlyForAllowlistedProfiles() {
        // stg/미지정/오타 프로파일 모두 엄격 — denylist(prd 만 차단) 가 아니라 allowlist 라 새지 않는다.
        assertThatThrownBy(() -> policy("stg", true).verifyRelaxationScope())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy(null, true).verifyRelaxationScope())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy("locall", true).verifyRelaxationScope())
                .isInstanceOf(IllegalStateException.class);
        // local 과 prd 가 동시에 활성이면 완화 불인정(엄격 + 기동 실패).
        assertThatThrownBy(() -> policy("local,prd", true).verifyRelaxationScope())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("완화_플래그가_꺼져있으면_local_에서도_엄격정책이_적용된다")
    void flagOffKeepsStrictEvenInLocal() {
        VlmUrlPolicy p = policy("local", false);
        assertThatCode(p::verifyRelaxationScope).doesNotThrowAnyException();
        assertThatThrownBy(() -> p.validate("http://klid-mock-server:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("placeholder_호스트는_모든_프로파일에서_차단된다")
    void placeholderBlockedInEveryProfile() {
        VlmUrlPolicy relaxed = policy("local", true);
        assertThatThrownBy(() -> relaxed.validate("http://your-vlm-service:9400"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> relaxed.validate("http://vlm.example.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> relaxed.validate("http://changeme:9400"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy("prd", false).validate("https://example.com"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("빈값_스키마없음_비허용스키마는_모든_프로파일에서_차단된다")
    void malformedUrlBlocked() {
        VlmUrlPolicy relaxed = policy("local", true);
        assertThatThrownBy(() -> relaxed.validate("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> relaxed.validate("klid-mock-server:9400"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> relaxed.validate("ftp://klid-mock-server:9400"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> relaxed.validate("file:///etc/passwd"))
                .isInstanceOf(IllegalStateException.class);
    }
}
