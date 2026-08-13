package kr.co.cudo.authoring.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link KpstWebClientConfig} 의 스키마 분기(http/https) + ca-cert 조건부 강제 단위 테스트.
 *
 * <p>UC018 — KPST 비식별 전송은 내부망 평문 http(IP) 또는 https+자체CA 둘 다 지원한다.
 * <ul>
 *   <li>http → SSL 미적용, ca-cert 불요(빈 기본값 OK).</li>
 *   <li>https → 자체 CA(ca.crt) 필수. 비었거나 못 읽으면 fail-closed 예외(CWE-295 보존).</li>
 *   <li>그 외 스키마(ftp 등)·스키마 없음·빈값 → 거부 예외.</li>
 * </ul>
 * 2개 빈(kpstDeidWebClient · kpstDeidProgressHttpClient)이 동일하게 분기한다.
 */
class KpstWebClientConfigTest {

    private static final String VALID_CA = "src/test/resources/kpst/test-ca.crt";

    private final KpstWebClientConfig cfg = new KpstWebClientConfig();

    @Test
    @DisplayName("Kpst_http_내부망IP_ca_cert_빈값_시_2개빈_생성성공_SSL미적용")
    void httpWithoutCaCertCreatesBeans() {
        // given — 내부망 평문 http base-url + ca-cert 빈값
        String httpUrl = "http://10.20.30.40:9989";
        // when / then — SSL/ca-cert 불요로 2개 빈 모두 예외 없이 생성
        assertThat(cfg.kpstDeidWebClient(httpUrl, "", null)).isNotNull();
        assertThat(cfg.kpstDeidProgressHttpClient(httpUrl, "")).isNotNull();
    }

    @Test
    @DisplayName("Kpst_https_유효한_ca_cert_시_2개빈_생성성공_SSL적용")
    void httpsWithValidCaCertCreatesBeans() {
        // given — https base-url + 유효 ca-cert
        String httpsUrl = "https://kpst-host:9201";
        // when / then
        assertThat(cfg.kpstDeidWebClient(httpsUrl, VALID_CA, null)).isNotNull();
        assertThat(cfg.kpstDeidProgressHttpClient(httpsUrl, VALID_CA)).isNotNull();
    }

    @Test
    @DisplayName("Kpst_https_ca_cert_빈값_시_fail_closed_예외_CWE295보존")
    void httpsWithoutCaCertFailsClosed() {
        // given — https 인데 ca-cert 빈값 → 신뢰 우회 차단(fail-closed)
        String httpsUrl = "https://kpst-host:9201";
        // when / then
        assertThatThrownBy(() -> cfg.kpstDeidWebClient(httpsUrl, "", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ca-cert");
        assertThatThrownBy(() -> cfg.kpstDeidProgressHttpClient(httpsUrl, ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Kpst_https_없는_ca_cert_경로_시_fail_closed_예외")
    void httpsWithUnreadableCaCertFailsClosed() {
        // given — https + 존재하지 않는 ca-cert 경로
        String httpsUrl = "https://kpst-host:9201";
        String missing = "src/test/resources/kpst/does-not-exist.crt";
        // when / then — 절대경로는 예외 메시지에 노출되지 않아야 한다(CWE-209)
        assertThatThrownBy(() -> cfg.kpstDeidWebClient(httpsUrl, missing, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("does-not-exist.crt");
    }

    @Test
    @DisplayName("Kpst_지원하지않는_스키마_ftp_시_거부예외")
    void unsupportedSchemeRejected() {
        // given / when / then — ftp 등은 http/https 외 거부
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("ftp://kpst-host:21", "", null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.kpstDeidProgressHttpClient("gopher://x", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("KPST_자체_CA_검증은_약화되지_않았다")
    void kpstSelfSignedCaValidationNotWeakened() {
        // VLM 정책 일원화(ExternalUrlPolicy 공용화) 이후에도 KPST 의 CWE-295 fail-closed 가 그대로여야 한다.
        String httpsUrl = "https://kpst-host:9201";
        // ① https + ca-cert 미설정 → 신뢰 우회 없이 즉시 거부
        assertThatThrownBy(() -> cfg.kpstDeidWebClient(httpsUrl, "", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ca-cert");
        assertThatThrownBy(() -> cfg.kpstDeidProgressHttpClient(httpsUrl, "   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ca-cert");
        // ② https + 읽을 수 없는 ca-cert → 거부(경로 미노출)
        assertThatThrownBy(() -> cfg.kpstDeidProgressHttpClient(httpsUrl, "src/test/resources/kpst/none.crt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("none.crt");
        // ③ https + 유효 ca-cert → 정상 생성(정상 경로 보존)
        assertThat(cfg.kpstDeidWebClient(httpsUrl, VALID_CA, null)).isNotNull();
        // ④ 내부망 평문 http 는 계속 허용 — KPST 정책은 완화되지도 강화되지도 않았다.
        assertThat(cfg.kpstDeidWebClient("http://10.20.30.40:9989", "", null)).isNotNull();
    }

    // ── SSRF 대역 판정 (G-ISSUE-21 / G-ISSUE-22) — VLM·증강과 대칭 ────────────────────
    // 판정기(ExternalUrlPolicy)는 VLM/KPST/증강 3연동 공용인데, 회귀 가드는 VLM·증강 2연동에만
    // 있었다. 이 저장소가 반복해서 겪은 "한쪽만 갱신돼 비대칭 재발"(DEV_FIX HIGH-1) 패턴을 막기
    // 위해 KPST 축에도 동일 케이스를 고정한다. KPST 는 내부망 전제(relaxed) 정책이라
    // RFC1918·loopback 은 계속 허용되지만 메타데이터 계열은 relaxed 에서도 차단된다.

    @Test
    @DisplayName("Kpst_IPv6_ULA_fc00_대역_base_url_은_내부망정책에서도_거부된다")
    void kpstIpv6UlaRejected() {
        // given — AWS IPv6 IMDS(fd00:ec2::254)는 ULA(fc00::/7)라 deprecated isSiteLocalAddress 로는
        //   잡히지 않았다. KPST 는 내부망 전제 정책이지만 IMDS 는 어떤 환경에서도 위탁 대상이 아니다.
        // when / then — 2개 빈 모두 동일하게 거부(분기 비대칭 방지)
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("http://[fd00:ec2::254]:9989", "", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ULA");
        assertThatThrownBy(() -> cfg.kpstDeidProgressHttpClient("http://[fc00::1]:9989", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ULA");
        // deprecated 사이트로컬(fec0::/10)·IPv6 링크로컬(fe80::/10)도 동일
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("http://[fec0::1]:9989", "", null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("http://[fe80::1]:9989", "", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("링크로컬");
    }

    @Test
    @DisplayName("Kpst_CGNAT_100_64_대역_base_url_은_내부망정책에서도_거부된다")
    void kpstCgnatRejected() {
        // given — 100.100.100.200 = Alibaba Cloud 메타데이터(CGNAT 100.64.0.0/10).
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("http://100.100.100.200:9989", "", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CGNAT");
        assertThatThrownBy(() -> cfg.kpstDeidProgressHttpClient("http://100.64.0.1:9989", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("http://100.127.255.254:9989", "", null))
                .isInstanceOf(IllegalStateException.class);
        // 경계 밖은 공인 대역이라 계속 통과(과차단 회귀 방지)
        assertThat(cfg.kpstDeidWebClient("http://100.63.255.254:9989", "", null)).isNotNull();
        assertThat(cfg.kpstDeidWebClient("http://100.128.0.1:9989", "", null)).isNotNull();
    }

    @Test
    @DisplayName("Kpst_NAT64_웰노운_프리픽스에_IMDS_주소를_임베드해도_거부된다")
    void kpstNat64WellKnownPrefixRejected() {
        // given — 64:ff9b::a9fe:a9fe = NAT64(RFC 6052) 로 감싼 169.254.169.254(AWS IMDS).
        //   언랩하지 않으면 IPv6 표기로 위장해 IPv4 대역 규칙을 우회한다.
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("http://[64:ff9b::a9fe:a9fe]:9989", "", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("메타데이터");
        assertThatThrownBy(() -> cfg.kpstDeidProgressHttpClient("http://[64:ff9b::6464:64c8]:9989", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CGNAT");
        // 공인 IPv4(8.8.8.8) 임베드는 통과 — NAT64 경유 공인 목적지는 정상
        assertThat(cfg.kpstDeidWebClient("http://[64:ff9b::808:808]:9989", "", null)).isNotNull();
    }

    @Test
    @DisplayName("Kpst_IPv4_mapped_IPv6_로_위장한_메타데이터_주소도_거부된다")
    void kpstIpv4MappedRejected() {
        // given — ::ffff:169.254.169.254 로 위장해도 언랩 후 IPv4 규칙이 적용된다.
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("http://[::ffff:169.254.169.254]:9989", "", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("메타데이터");
        // 내부망 정책이므로 사설(RFC1918) 은 mapped 표기여도 계속 허용된다(개발/내부 기동 보장)
        assertThat(cfg.kpstDeidWebClient("http://[::ffff:10.20.30.40]:9989", "", null)).isNotNull();
    }

    @Test
    @DisplayName("Kpst_IANA_특수목적_대역_프로토콜할당_벤치마킹_멀티캐스트_ClassE_는_거부된다")
    void kpstIanaSpecialPurposeRangesRejected() {
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("http://192.0.0.170:9989", "", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("프로토콜 할당");
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("http://198.18.0.1:9989", "", null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.kpstDeidProgressHttpClient("http://224.0.0.1:9989", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("멀티캐스트");
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("http://[ff02::1]:9989", "", null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("http://240.0.0.1:9989", "", null))
                .isInstanceOf(IllegalStateException.class);
        // 경계 밖은 공인 — 과차단 회귀 방지
        assertThat(cfg.kpstDeidWebClient("http://192.0.1.1:9989", "", null)).isNotNull();
        assertThat(cfg.kpstDeidWebClient("http://223.255.255.254:9989", "", null)).isNotNull();
    }

    @Test
    @DisplayName("Kpst_호스트명이_여러_주소로_해석될_때_그중_하나라도_메타데이터_대역이면_거부된다")
    void kpstAnyResolvedAddressInReservedRangeRejected() throws UnknownHostException {
        // given — G-ISSUE-22. 실 DNS 를 조작할 수 없으므로 KPST 와 동일한 정책 인스턴스에
        //   해석 결과를 직접 주입한다(KpstWebClientConfig 는 같은 팩토리
        //   ExternalUrlPolicy.internalNetwork("kpst.deid.base-url") 를 쓰며, 그 배선은 위의
        //   base-url 리터럴 케이스들이 실제 빈 경로로 이미 검증한다).
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
        assertThat(cfg.kpstDeidWebClient("http://10.20.30.40:9989", "", null)).isNotNull();
        assertThat(cfg.kpstDeidWebClient("http://172.16.0.1:9989", "", null)).isNotNull();
        assertThat(cfg.kpstDeidWebClient("http://192.168.0.1:9989", "", null)).isNotNull();
        assertThat(cfg.kpstDeidProgressHttpClient("http://127.0.0.1:9989", "")).isNotNull();
        // 해석되지 않는 컨테이너 서비스명도 통과(도커 밖 기동 보장)
        assertThat(cfg.kpstDeidWebClient(
                "http://kpst-does-not-resolve-" + System.nanoTime() + ":9989", "", null)).isNotNull();
    }

    @Test
    @DisplayName("Kpst_스키마없음_또는_빈_base_url_시_거부예외")
    void missingSchemeOrBlankRejected() {
        // given / when / then
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("kpst-host:9201", "", null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("", "", null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.kpstDeidProgressHttpClient("   ", ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
