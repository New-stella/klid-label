package kr.co.cudo.authoring.common.config;

import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link KpstWebClientConfig} 의 스키마 분기(http/https) + ca-cert 조건부 강제 단위 테스트.
 *
 * <h3>★ 축이 옮겨졌다 — 무르게 한 것이 아니다 (2026-09-03 확정, 구속)</h3>
 * <p>구 기대값은 <b>"위반이면 빈 생성 실패"</b>였다. 「외부 연동 주소로 기동을 막지 않는다」가
 * 확정되면서 <b>같은 위반을 같은 규칙으로 판정하되 전송 시점에</b> 막는다. 그래서 이 파일의
 * 단언은 두 벌이다:
 * <ol>
 *   <li><b>그 상태로 기동한다</b> — 2개 빈이 예외 없이 만들어진다.</li>
 *   <li><b>그 주소로 나가려 하면 실패한다</b> — 판정이 거부로 기록되고 요청이 전송되지 않는다.</li>
 * </ol>
 * <p>판정 규칙 자체(대역 분류·스킴·placeholder)는 {@link ExternalUrlPolicy} 가 그대로 소유하므로,
 * 아래 대역 케이스는 <b>정책 판정</b>과 <b>설정 반영</b> 두 층에서 함께 고정한다 — 한 층만 보면
 * "판정은 맞는데 배선이 빠진" 형태를 놓친다.
 *
 * <p>UC018 — KPST 비식별 전송은 내부망 평문 http(IP) 또는 https+자체CA 둘 다 지원한다.
 * https 인데 자체 CA 가 없으면 <b>검증 없이 보내지 않는다</b>(CWE-295) — 그 fail-closed 는
 * 그대로이고, 다만 그것이 기동이 아니라 <b>전송</b>을 막는다.
 */
class KpstWebClientConfigTest {

    private static final String VALID_CA = "src/test/resources/kpst/test-ca.crt";

    private final KpstWebClientConfig cfg = new KpstWebClientConfig();

    /** KPST 와 <b>같은 팩토리</b>로 만든 판정기 — 규칙을 복제하지 않고 그대로 부른다. */
    private static final ExternalUrlPolicy KPST_POLICY =
            ExternalUrlPolicy.internalNetwork("kpst.deid.base-url");

    private ExternalEndpointAddress addr(String url, String caCertPath) {
        return cfg.kpstDeidEndpointAddress(url, caCertPath, null);
    }

    /** 「기동은 된다」 — 2개 빈이 예외 없이 만들어진다. */
    private ExternalEndpointAddress assertBootsWith(String url, String caCertPath) {
        ExternalEndpointAddress address = addr(url, caCertPath);
        assertThatCode(() -> {
            assertThat(cfg.kpstDeidWebClient(address, caCertPath, null)).isNotNull();
            assertThat(cfg.kpstDeidProgressHttpClient(address, caCertPath)).isNotNull();
        }).as("연동 주소가 어떤 상태여도 기동은 막히지 않는다").doesNotThrowAnyException();
        return address;
    }

    /** 「그 주소로 나가려 하면 실패한다」 — 소켓을 열지 않고 즉시 거부된다. */
    private void assertNothingIsSent(ExternalEndpointAddress address, String caCertPath) {
        WebClient client = cfg.kpstDeidWebClient(address, caCertPath, null);
        assertThatThrownBy(() -> client.post().uri("/project").bodyValue("{}").retrieve()
                .bodyToMono(String.class).block(Duration.ofSeconds(5)))
                .as("연결 거부(ConnectException)가 나면 이미 전송을 시도했다는 뜻이다")
                .isInstanceOf(NonRetryableExternalException.class);
    }

    /**
     * 거부 축의 두 벌을 한 번에 — 기동 성공 + 전송 차단 + 사유 보존.
     *
     * @param expectedLabel 기대 사유. <b>미설정(빈값)은 위반이 아니라 상태</b>라 {@code null} 이다
     *                      — 전송 가드가 "설정되지 않았다" 문구를 따로 낸다.
     */
    private void assertBootsButNeverSends(String url, String caCertPath, String expectedLabel) {
        ExternalEndpointAddress address = assertBootsWith(url, caCertPath);
        assertThat(address.usable()).as("설정값은 거부로 기록돼야 한다 — " + url).isFalse();
        assertThat(address.rejectionLabel()).as(url).isEqualTo(expectedLabel);
        assertNothingIsSent(address, caCertPath);
    }

    @Test
    @DisplayName("Kpst_http_내부망IP_ca_cert_빈값_시_2개빈_생성성공_SSL미적용")
    void httpWithoutCaCertCreatesBeans() {
        ExternalEndpointAddress address = addr("http://10.20.30.40:9989", "");
        assertThat(address.usable()).isTrue();
        assertThat(cfg.kpstDeidWebClient(address, "", null)).isNotNull();
        assertThat(cfg.kpstDeidProgressHttpClient(address, "")).isNotNull();
    }

    @Test
    @DisplayName("Kpst_https_유효한_ca_cert_시_2개빈_생성성공_SSL적용")
    void httpsWithValidCaCertCreatesBeans() {
        ExternalEndpointAddress address = addr("https://kpst-host:9201", VALID_CA);
        assertThat(address.usable()).isTrue();
        assertThat(address.https()).isTrue();
        assertThat(cfg.kpstDeidWebClient(address, VALID_CA, null)).isNotNull();
        assertThat(cfg.kpstDeidProgressHttpClient(address, VALID_CA)).isNotNull();
    }

    @Test
    @DisplayName("★https_인데_ca_cert가_없으면_기동은_되고_위탁만_거부된다_CWE295_보존")
    void httpsWithoutCaCertBlocksTransportNotBoot() {
        // 구 기대값은 "빈 생성 실패" 였다. 검증 없이 보내지 않는다는 fail-closed 는 그대로이고,
        //   막는 자리만 기동 → 전송으로 옮겼다.
        assertBootsButNeverSends("https://kpst-host:9201", "", "TLS 인증서 미비");
        assertBootsButNeverSends("https://kpst-host:9201", "   ", "TLS 인증서 미비");
    }

    @Test
    @DisplayName("★https_읽을수없는_ca_cert_경로도_기동은_되고_위탁만_거부된다")
    void httpsWithUnreadableCaCertBlocksTransportNotBoot() {
        assertBootsButNeverSends(
                "https://kpst-host:9201", "src/test/resources/kpst/does-not-exist.crt",
                "TLS 인증서 미비");
    }

    @Test
    @DisplayName("★거부_사유에는_주소도_인증서_경로도_실리지_않는다_CWE209")
    void rejectionLabelCarriesNoInput() {
        ExternalEndpointAddress address =
                addr("https://kpst-host:9201", "src/test/resources/kpst/does-not-exist.crt");
        assertThat(address.rejectionLabel())
                .doesNotContain("kpst-host")
                .doesNotContain("does-not-exist.crt");

        WebClient client = cfg.kpstDeidWebClient(address, "", null);
        assertThatThrownBy(() -> client.get().uri("/").retrieve().bodyToMono(String.class)
                .block(Duration.ofSeconds(5)))
                .hasMessageNotContaining("kpst-host")
                .hasMessageNotContaining("does-not-exist.crt")
                .hasMessageNotContaining("kpst.deid.base-url");
    }

    @Test
    @DisplayName("★지원하지않는_스키마_ftp_는_기동을_막지_않고_전송을_막는다")
    void unsupportedSchemeBlocksTransportNotBoot() {
        assertThat(KPST_POLICY.inspect("ftp://kpst-host:21").violation())
                .isEqualTo(ExternalUrlPolicy.Violation.SCHEME_NOT_ALLOWED);
        assertBootsButNeverSends("ftp://kpst-host:21", "", "허용되지 않는 스킴");
        assertBootsButNeverSends("gopher://kpst-host", "", "허용되지 않는 스킴");
    }

    @Test
    @DisplayName("★스키마없음_빈값_파싱불가_도_기동을_막지_않고_전송을_막는다")
    void missingSchemeOrBlankBlocksTransportNotBoot() {
        assertBootsButNeverSends("kpst-host:9201", "", "허용되지 않는 스킴");
        // 미설정은 위반이 아니라 상태다 — 사유 없이 "설정되지 않았다" 로 끝난다.
        assertBootsButNeverSends("", "", null);
        assertBootsButNeverSends("   ", "", null);
        assertBootsButNeverSends("http://kpst host:9201", "", "주소 형식 오류");
    }

    @Test
    @DisplayName("★예시_placeholder_호스트도_기동을_막지_않고_전송을_막는다")
    void placeholderHostBlocksTransportNotBoot() {
        assertThat(KPST_POLICY.inspect("https://example.com").violation())
                .isEqualTo(ExternalUrlPolicy.Violation.PLACEHOLDER_HOST);
        assertBootsButNeverSends("https://example.com", VALID_CA, "예시·미설정 호스트");
    }

    @Test
    @DisplayName("KPST_자체_CA_검증은_약화되지_않았다")
    void kpstSelfSignedCaValidationNotWeakened() {
        // ① https + ca-cert 미설정/불가 → 신뢰 우회 없이 위탁 거부(위 전용 테스트가 두 벌로 고정)
        assertThat(addr("https://kpst-host:9201", "").usable()).isFalse();
        assertThat(addr("https://kpst-host:9201", "src/test/resources/kpst/none.crt").usable()).isFalse();
        // ② https + 유효 ca-cert → 정상(정상 경로 보존)
        assertThat(addr("https://kpst-host:9201", VALID_CA).usable()).isTrue();
        // ③ 내부망 평문 http 는 계속 허용 — KPST 정책은 완화되지도 강화되지도 않았다.
        assertThat(addr("http://10.20.30.40:9989", "").usable()).isTrue();
    }

    // ── SSRF 대역 판정 (G-ISSUE-21 / G-ISSUE-22) — VLM·증강과 대칭 ────────────────────
    // 판정기(ExternalUrlPolicy)는 VLM/KPST/증강 3연동 공용인데, 회귀 가드는 VLM·증강 2연동에만
    // 있었다. 이 저장소가 반복해서 겪은 "한쪽만 갱신돼 비대칭 재발"(DEV_FIX HIGH-1) 패턴을 막기
    // 위해 KPST 축에도 동일 케이스를 고정한다. KPST 는 내부망 전제(relaxed) 정책이라
    // RFC1918·loopback 은 계속 허용되지만 메타데이터 계열은 relaxed 에서도 차단된다.
    //
    // ★ 판정 문구(어느 대역인지)는 <서버 로그 전용 상세>로 남는다 — 전송 실패 메시지에는 사유
    //   분류만 실린다(CWE-209). 그래서 대역 분류는 정책 층에서, 배선은 설정 층에서 고정한다.

    private void assertRangeRejected(String url, String detailFragment) {
        ExternalUrlPolicy.Verdict verdict = KPST_POLICY.inspect(url);
        assertThat(verdict.rejected()).as(url).isTrue();
        assertThat(verdict.violation()).isEqualTo(ExternalUrlPolicy.Violation.RESERVED_RANGE);
        assertThat(verdict.detail()).contains(detailFragment);
        assertBootsButNeverSends(url, "", "예약 대역");
    }

    @Test
    @DisplayName("Kpst_IPv6_ULA_fc00_대역_base_url_은_내부망정책에서도_거부된다")
    void kpstIpv6UlaRejected() {
        // given — AWS IPv6 IMDS(fd00:ec2::254)는 ULA(fc00::/7)라 deprecated isSiteLocalAddress 로는
        //   잡히지 않았다. KPST 는 내부망 전제 정책이지만 IMDS 는 어떤 환경에서도 위탁 대상이 아니다.
        assertRangeRejected("http://[fd00:ec2::254]:9989", "ULA");
        assertRangeRejected("http://[fc00::1]:9989", "ULA");
        // deprecated 사이트로컬(fec0::/10)·IPv6 링크로컬(fe80::/10)도 동일
        assertRangeRejected("http://[fec0::1]:9989", "사이트로컬");
        assertRangeRejected("http://[fe80::1]:9989", "링크로컬");
    }

    @Test
    @DisplayName("Kpst_CGNAT_100_64_대역_base_url_은_내부망정책에서도_거부된다")
    void kpstCgnatRejected() {
        // given — 100.100.100.200 = Alibaba Cloud 메타데이터(CGNAT 100.64.0.0/10).
        assertRangeRejected("http://100.100.100.200:9989", "CGNAT");
        assertRangeRejected("http://100.64.0.1:9989", "CGNAT");
        assertRangeRejected("http://100.127.255.254:9989", "CGNAT");
        // 경계 밖은 공인 대역이라 계속 통과(과차단 회귀 방지)
        assertThat(addr("http://100.63.255.254:9989", "").usable()).isTrue();
        assertThat(addr("http://100.128.0.1:9989", "").usable()).isTrue();
    }

    @Test
    @DisplayName("Kpst_NAT64_웰노운_프리픽스에_IMDS_주소를_임베드해도_거부된다")
    void kpstNat64WellKnownPrefixRejected() {
        // given — 64:ff9b::a9fe:a9fe = NAT64(RFC 6052) 로 감싼 169.254.169.254(AWS IMDS).
        //   언랩하지 않으면 IPv6 표기로 위장해 IPv4 대역 규칙을 우회한다.
        assertRangeRejected("http://[64:ff9b::a9fe:a9fe]:9989", "메타데이터");
        assertRangeRejected("http://[64:ff9b::6464:64c8]:9989", "CGNAT");
        // 공인 IPv4(8.8.8.8) 임베드는 통과 — NAT64 경유 공인 목적지는 정상
        assertThat(addr("http://[64:ff9b::808:808]:9989", "").usable()).isTrue();
    }

    @Test
    @DisplayName("Kpst_IPv4_mapped_IPv6_로_위장한_메타데이터_주소도_거부된다")
    void kpstIpv4MappedRejected() {
        assertRangeRejected("http://[::ffff:169.254.169.254]:9989", "메타데이터");
        // 내부망 정책이므로 사설(RFC1918) 은 mapped 표기여도 계속 허용된다(개발/내부 기동 보장)
        assertThat(addr("http://[::ffff:10.20.30.40]:9989", "").usable()).isTrue();
    }

    @Test
    @DisplayName("Kpst_IANA_특수목적_대역_프로토콜할당_벤치마킹_멀티캐스트_ClassE_는_거부된다")
    void kpstIanaSpecialPurposeRangesRejected() {
        assertRangeRejected("http://192.0.0.170:9989", "프로토콜 할당");
        assertRangeRejected("http://198.18.0.1:9989", "벤치마킹");
        assertRangeRejected("http://224.0.0.1:9989", "멀티캐스트");
        assertRangeRejected("http://[ff02::1]:9989", "멀티캐스트");
        assertRangeRejected("http://240.0.0.1:9989", "예약");
        // 경계 밖은 공인 — 과차단 회귀 방지
        assertThat(addr("http://192.0.1.1:9989", "").usable()).isTrue();
        assertThat(addr("http://223.255.255.254:9989", "").usable()).isTrue();
    }

    @Test
    @DisplayName("Kpst_호스트명이_여러_주소로_해석될_때_그중_하나라도_메타데이터_대역이면_거부된다")
    void kpstAnyResolvedAddressInReservedRangeRejected() throws UnknownHostException {
        // given — G-ISSUE-22. 실 DNS 를 조작할 수 없으므로 KPST 와 동일한 정책 인스턴스에
        //   해석 결과를 직접 주입한다.
        ExternalUrlPolicy kpstPolicy = ExternalUrlPolicy.internalNetwork("kpst.deid.base-url");
        InetAddress publicV4 = InetAddress.getByName("8.8.8.8");
        InetAddress privateV4 = InetAddress.getByName("10.20.30.40");
        InetAddress imds = InetAddress.getByName("169.254.169.254");
        InetAddress ulaV6 = InetAddress.getByName("fd00:ec2::254");
        InetAddress cgnat = InetAddress.getByName("100.100.100.200");

        // when / then — 첫 주소가 공인이어도 뒤에 메타데이터 계열이 섞이면 거부(순서 무관)
        assertThatThrownBy(() -> kpstPolicy.verifyResolvedAddresses("kpst.internal", publicV4, imds))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> kpstPolicy.verifyResolvedAddresses("kpst.internal", publicV4, ulaV6))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> kpstPolicy.verifyResolvedAddresses("kpst.internal", cgnat, publicV4))
                .isInstanceOf(IllegalStateException.class);
        // 내부망 정책이라 사설(RFC1918)이 섞인 경우는 계속 허용된다
        assertThatCode(() -> kpstPolicy.verifyResolvedAddresses("kpst.internal", publicV4, privateV4))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Kpst_내부망_사설IP_와_루프백은_대역판정_강화_후에도_계속_허용된다")
    void kpstPrivateAndLoopbackStillAllowed() {
        // given — 과차단 회귀 방지. KPST 는 내부망 격리 전제라 RFC1918·loopback 이 정상 형상이다.
        assertThat(addr("http://10.20.30.40:9989", "").usable()).isTrue();
        assertThat(addr("http://172.16.0.1:9989", "").usable()).isTrue();
        assertThat(addr("http://192.168.0.1:9989", "").usable()).isTrue();
        assertThat(addr("http://127.0.0.1:9989", "").usable()).isTrue();
        // 해석되지 않는 컨테이너 서비스명도 통과(도커 밖 기동 보장)
        assertThat(addr("http://kpst-does-not-resolve-" + System.nanoTime() + ":9989", "").usable())
                .isTrue();
    }
}
