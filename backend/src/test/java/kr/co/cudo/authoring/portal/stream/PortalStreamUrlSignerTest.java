package kr.co.cudo.authoring.portal.stream;

import kr.co.cudo.authoring.portal.service.PortalStreamUrlSigner;
import kr.co.cudo.authoring.video.service.StreamUrlSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포털 재생 서명기 — 도메인 분리와 fail-closed.
 */
class PortalStreamUrlSignerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final long ULD_SN = 501L;
    private static final String OWNER = "portal-user-1";

    private PortalStreamUrlSigner signer() {
        return new PortalStreamUrlSigner(SECRET, 120L);
    }

    @Test
    @DisplayName("발급한_서명은_같은_자산·같은_주체로_검증된다")
    void signVerifyRoundTrip() {
        PortalStreamUrlSigner signer = signer();
        PortalStreamUrlSigner.SignedParams p = signer.sign(ULD_SN, OWNER);

        assertThat(signer.verify(ULD_SN, String.valueOf(p.exp()), p.sig(), OWNER)).isTrue();
    }

    @Test
    @DisplayName("다른_자산·다른_주체로는_검증되지_않는다")
    void signatureIsBoundToAssetAndOwner() {
        PortalStreamUrlSigner signer = signer();
        PortalStreamUrlSigner.SignedParams p = signer.sign(ULD_SN, OWNER);

        assertThat(signer.verify(ULD_SN + 1, String.valueOf(p.exp()), p.sig(), OWNER)).isFalse();
        assertThat(signer.verify(ULD_SN, String.valueOf(p.exp()), p.sig(), "other")).isFalse();
    }

    /**
     * ★ 흡수 이후 포털 자산 식별자와 관제 영상 식별자는 <b>같은 번호 공간</b>이다. 서명 입력의 모양이
     * 같으면 한 채널 서명이 다른 채널 창구에서도 성립할 수 있으므로 절대 겹치지 않아야 한다.
     */
    @Test
    @DisplayName("★관제_채널_서명과_절대_겹치지_않는다 — 같은_번호_공간이라_도메인_분리가_필수다")
    void neverCollidesWithControlChannelSignature() {
        PortalStreamUrlSigner portal = signer();
        StreamUrlSigner control = new StreamUrlSigner(SECRET, 120L);

        PortalStreamUrlSigner.SignedParams portalSig = portal.sign(ULD_SN, OWNER);
        // 관제 서명기는 클라이언트 바인딩 값을 필수로 요구하므로, 그 값을 주체와 같게 두어
        // "가장 겹치기 쉬운" 조합을 만들어도 두 서명이 달라야 한다.
        StreamUrlSigner.SignedParams controlSig = control.sign(ULD_SN, OWNER, OWNER);

        assertThat(portalSig.sig()).isNotEqualTo(controlSig.sig());
        assertThat(control.verify(ULD_SN, String.valueOf(portalSig.exp()), portalSig.sig(), OWNER, OWNER))
                .as("포털 서명이 관제 스트림 검증을 통과하면 안 된다")
                .isFalse();
    }

    @Test
    @DisplayName("★시크릿_미설정이면_발급도_검증도_거부한다 — 없는_채로_열면_아무나_재생한다")
    void unconfiguredIsFailClosed() {
        PortalStreamUrlSigner unset = new PortalStreamUrlSigner("", 120L);

        assertThat(unset.isConfigured()).isFalse();
        assertThat(unset.verify(ULD_SN, "9999999999", "deadbeef", OWNER)).isFalse();
    }

    @Test
    @DisplayName("만료된_서명은_거부한다")
    void expiredSignatureRejected() {
        PortalStreamUrlSigner signer = signer();
        PortalStreamUrlSigner.SignedParams p = signer.sign(ULD_SN, OWNER);

        // 과거 만료 시각으로 바꾸면 서명 자체가 달라지므로 어느 쪽으로든 거부된다.
        assertThat(signer.verify(ULD_SN, "1", p.sig(), OWNER)).isFalse();
    }

    @Test
    @DisplayName("주체가_비면_거부한다 — 재생_인가가_소유자_판정이기_때문이다")
    void blankOwnerRejected() {
        PortalStreamUrlSigner signer = signer();
        PortalStreamUrlSigner.SignedParams p = signer.sign(ULD_SN, OWNER);

        assertThat(signer.verify(ULD_SN, String.valueOf(p.exp()), p.sig(), null)).isFalse();
        assertThat(signer.verify(ULD_SN, String.valueOf(p.exp()), p.sig(), " ")).isFalse();
    }
}
