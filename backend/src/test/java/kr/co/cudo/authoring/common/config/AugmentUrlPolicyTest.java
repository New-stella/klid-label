package kr.co.cudo.authoring.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AugmentUrlPolicy} — 증강 위탁 주소 검증의 계약.
 *
 * <h3>★ 이 시험은 기대값이 뒤집힌 자리다 — 지우지 말고 읽을 것 (2026-09-01)</h3>
 * <p>구 기대값은 <b>"운영 프로파일이면 HTTPS 전용 + 사설망 차단"</b> 이었다. 그 두 조항은
 * <b>확정으로 폐기</b>됐고(연동 주소는 스킴·형식만 본다 — 대상에 증강이 뒤늦게 포함됐다), 이 시험은
 * 그 확정을 코드에 고정한다. 아래 {@code 운영_프로파일_상당_조건에서도_평문_http_주소가_통과한다} 가
 * 그 축의 회귀 가드이며, <b>이 시험이 실패하면 운영에서 위탁을 켜는 순간 평문 벤더 주소에서 기동이
 * 막힌다</b>.
 *
 * <p>구 시험이 함께 검증하던 것들 — 프로파일 allowlist·배포 표식({@code ENV})·완화 플래그
 * ({@code authoring.augment.external.allow-insecure-url}) 기동 assert — 는 <b>그 장치들이 사라져</b>
 * 검증 대상이 없다. 폐기된 조항을 검증하던 시험을 남겨 두면 다음 사람이 그것을 현행 사양으로 읽는다.
 *
 * <p>계속 유효한 것: 비허용 스킴·빈값·파싱 불가·placeholder 호스트 거부와, <b>정상 위탁 대상이 될 수
 * 없는 예약 대역</b>(메타데이터·링크로컬·ULA·CGNAT·멀티캐스트 등) 거부. 판정기가 VLM·KPST·증강
 * 3연동 공용({@link ExternalUrlPolicy})이므로, 그 판정기가 갈라져 증강 축만 뚫리는 상태를 여기서 잡는다.
 */
class AugmentUrlPolicyTest {

    private static AugmentUrlPolicy policy() {
        return new AugmentUrlPolicy();
    }

    @Test
    @DisplayName("★운영_프로파일_상당_조건에서도_평문_http_주소가_통과한다 — 이 축이 깨지면 운영 기동이 막힌다")
    void 운영_프로파일_상당_조건에서도_평문_http_주소가_통과한다() {
        // given — 프로파일·환경 표식이라는 축 자체가 없다(정책 인스턴스가 하나뿐이다).
        AugmentUrlPolicy p = policy();

        // when / then — 실 연동 형태(평문 http + 내부 호스트 · 사설 대역 · loopback)가 전부 통과한다.
        assertThatCode(() -> p.validate("http://genai.vendor.io:9400")).doesNotThrowAnyException();
        assertThatCode(() -> p.validate("http://10.0.0.5:9400")).doesNotThrowAnyException();
        assertThatCode(() -> p.validate("http://192.168.1.10:9400")).doesNotThrowAnyException();
        assertThatCode(() -> p.validate("http://172.16.0.1:9400")).doesNotThrowAnyException();
        assertThatCode(() -> p.validate("http://127.0.0.1:9400")).doesNotThrowAnyException();
        assertThatCode(() -> p.validate("http://localhost:9400")).doesNotThrowAnyException();
        assertThatCode(() -> p.validate("http://klid-mock-server:9400")).doesNotThrowAnyException();
        // https 사설 대역도 마찬가지다 — 막는 축이 전송이 아니라 스킴/형식이기 때문이다.
        assertThatCode(() -> p.validate("https://10.0.0.5")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★https_주소도_그대로_통과한다 — 나중에 TLS 로 전환해도 코드 변경이 없다")
    void https_주소도_그대로_통과한다() {
        AugmentUrlPolicy p = policy();

        // 통과 여부뿐 아니라 <반환값>도 고정한다 — 호출자가 이 값으로 TLS 구성을 가른다.
        assertThat(p.check("https://8.8.8.8")).isTrue();
        assertThat(p.check("https://1.1.1.1:8443")).isTrue();
        assertThat(p.check("https://[2001:4860:4860::8888]")).isTrue();
        assertThat(p.check("http://8.8.8.8:9400")).isFalse();
    }

    @Test
    @DisplayName("빈값_스키마없음_비허용스키마는_계속_차단된다 — 남은 검증축")
    void 비허용_스킴은_계속_차단된다() {
        AugmentUrlPolicy p = policy();
        assertThatThrownBy(() -> p.validate(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.validate("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.validate("   ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.validate("klid-mock-server:9400"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.validate("ftp://klid-mock-server:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http/https");
        assertThatThrownBy(() -> p.validate("file:///etc/passwd"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.validate("ws://klid-mock-server:9400"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("placeholder_호스트는_계속_차단된다 — 환경변수 미설정 배포 방지")
    void placeholder_호스트는_계속_차단된다() {
        AugmentUrlPolicy p = policy();
        assertThatThrownBy(() -> p.validate("http://your-service.example.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.validate("http://changeme:9400"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.validate("http://placeholder:9400"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.validate("https://example.com"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("링크로컬_메타데이터_대역은_계속_차단된다 — IMDS 는 어떤 환경에서도 위탁 대상이 아니다")
    void 메타데이터_대역은_계속_차단된다() {
        AugmentUrlPolicy p = policy();
        assertThatThrownBy(() -> p.validate("http://169.254.169.254"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("메타데이터");
        assertThatThrownBy(() -> p.validate("http://169.254.1.1:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("메타데이터");
        assertThatThrownBy(() -> p.validate("http://[fe80::1]:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("링크로컬");
        // IPv6 ULA(fc00::/7 — AWS IPv6 IMDS 포함) · CGNAT(100.64/10 — Alibaba 메타데이터 포함)
        assertThatThrownBy(() -> p.validate("http://[fd00:ec2::254]:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ULA");
        assertThatThrownBy(() -> p.validate("http://100.100.100.200:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CGNAT");
        // NAT64 well-known prefix 로 위장한 IMDS 도 언랩 후 거부
        assertThatThrownBy(() -> p.validate("http://[64:ff9b::a9fe:a9fe]:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("메타데이터");
        // IANA 특수목적 대역(방어심도)
        assertThatThrownBy(() -> p.validate("http://192.0.0.1:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("프로토콜 할당");
        assertThatThrownBy(() -> p.validate("http://[ff02::1]:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("멀티캐스트");
        assertThatThrownBy(() -> p.validate("http://0.0.0.0:9400"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("사설_대역은_예약대역_차단_대상이_아니다 — 과차단 회귀 방지")
    void 사설_대역은_예약대역_차단_대상이_아니다() throws UnknownHostException {
        // given — 해석 결과를 직접 주입해 실 DNS 없이 대역 판정만 본다(다중 A/AAAA 재현).
        InetAddress publicV4 = InetAddress.getByName("8.8.8.8");
        InetAddress privateV4 = InetAddress.getByName("10.0.0.5");
        InetAddress imds = InetAddress.getByName("169.254.169.254");
        InetAddress ulaV6 = InetAddress.getByName("fd00:ec2::254");
        AugmentUrlPolicy p = policy();

        // when / then — 사설은 통과, 메타데이터·ULA 는 순서와 무관하게 거부(하나라도 걸리면 거부)
        assertThatCode(() -> p.verifyResolvedAddresses("multi.genai.io", publicV4, privateV4))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> p.verifyResolvedAddresses("multi.genai.io", publicV4, imds))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.verifyResolvedAddresses("multi.genai.io", publicV4, ulaV6))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("해석되지_않는_컨테이너명은_계속_허용된다 — 해석 실패를 거부로 바꾸면 기동이 막힌다")
    void 해석되지_않는_컨테이너명은_계속_허용된다() {
        String unresolvable = "http://genai-mock-does-not-resolve-" + System.nanoTime() + ":9400";
        assertThatCode(() -> policy().validate(unresolvable)).doesNotThrowAnyException();
    }
}
