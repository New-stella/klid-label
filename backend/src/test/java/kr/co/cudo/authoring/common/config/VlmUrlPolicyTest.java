package kr.co.cudo.authoring.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetAddress;
import java.net.UnknownHostException;

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

    /** 프로파일 + 배포 환경 표식({@code ENV}) 을 함께 지정한 정책. */
    private static VlmUrlPolicy markerPolicy(String activeProfile, boolean allowInsecure, String envMarker) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(activeProfile.split(","));
        env.setProperty("ENV", envMarker);
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
    @DisplayName("배포표식_ENV_가_stg_prd_면_local_dev_프로파일이어도_완화가_인정되지_않는다")
    void deployedEnvMarkerOverridesProfileRelaxation() {
        // given — 프로파일은 dev/local 인데 배포 서버 표식(ENV)이 붙은 오배포 상황.
        //   프로파일 축만 보면 잡히지 않는다(SPRING_PROFILES_ACTIVE 를 dev 로 둔 채 stg/prd 서버에 올림).
        //   DevProfileGuard.DEPLOYED_ENVS 와 동일 기준으로 "배포 쪽이 이긴다".
        // when / then — 기동 assert 도 실패하고, URL 검증도 엄격으로 유지된다(이중 방어)
        assertThatThrownBy(() -> markerPolicy("local", true, "prd").verifyRelaxationScope())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allow-insecure-url");
        assertThatThrownBy(() -> markerPolicy("local", true, "prd").validate("http://klid-mock-server:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
        // 대소문자/공백 무시 + stg 표식도 동일하게 배포로 취급
        assertThatThrownBy(() -> markerPolicy("dev", true, " STG ").validate("http://klid-mock-server:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
        // 배포 표식이 아닌 라벨(qa 등)·빈 값은 완화를 막지 않는다(사내 임시 환경 기동 보장)
        assertThatCode(() -> markerPolicy("local", true, "qa").validate("http://klid-mock-server:9400"))
                .doesNotThrowAnyException();
        assertThatCode(() -> markerPolicy("local", true, "").validate("http://klid-mock-server:9400"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("링크로컬_메타데이터_대역은_완화_프로파일에서도_차단된다")
    void metadataRangeRejectedEvenWhenRelaxed() {
        // given — dev 에 잘못된 VLM_SERVICE_URL 이 주입돼 클라우드 메타데이터(IMDS) 대역을 향하는 상황.
        //   완화는 "평문 http + 사설 IP" 까지이며 IMDS 는 어떤 환경에서도 정상 위탁 대상이 아니다.
        VlmUrlPolicy relaxed = policy("dev", true);
        assertThatThrownBy(() -> relaxed.validate("http://169.254.169.254"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("메타데이터");
        assertThatThrownBy(() -> relaxed.validate("http://169.254.1.1:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("메타데이터");
        // IPv6 링크로컬(fe80::/10)도 동일하게 차단 — URI#getHost 가 대괄호를 포함해 돌려주므로 정규화 필요
        assertThatThrownBy(() -> relaxed.validate("http://[fe80::1]:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("링크로컬");
    }

    @Test
    @DisplayName("IPv6_ULA_주소_fc00으로_시작하면_relaxed_strict_모두_거부된다")
    void ipv6UlaRejectedInBothPolicies() {
        // given — G-ISSUE-21. Inet6Address#isSiteLocalAddress 는 deprecated 된 fec0::/10 만 판정하므로
        //   실제 IPv6 사설 대역인 ULA(fc00::/7)가 두 정책 모두에서 통과하고 있었다.
        //   AWS 의 IPv6 IMDS(fd00:ec2::254)가 바로 이 대역이라 자격증명 탈취로 직결된다.
        VlmUrlPolicy relaxed = policy("local", true);
        VlmUrlPolicy strict = policy("prd", false);
        // when / then — relaxed(개발 완화)에서도 IMDS 는 정상 위탁 대상이 아니다
        assertThatThrownBy(() -> relaxed.validate("http://[fd00:ec2::254]:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ULA");
        assertThatThrownBy(() -> relaxed.validate("http://[fc00::1]:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ULA");
        // strict(운영)에서도 동일하게 거부
        assertThatThrownBy(() -> strict.validate("https://[fd00:ec2::254]"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> strict.validate("https://[fc00::1]"))
                .isInstanceOf(IllegalStateException.class);
        // deprecated 사이트로컬(fec0::/10)도 방어 목록에 유지
        assertThatThrownBy(() -> relaxed.validate("http://[fec0::1]:9400"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("CGNAT_대역_100_64_0_0_10은_relaxed_strict_모두_거부된다")
    void cgnatRangeRejectedInBothPolicies() {
        // given — G-ISSUE-21. isSiteLocalAddress 는 RFC1918 만 보므로 CGNAT(100.64/10)가 통과했다.
        //   100.100.100.200 은 Alibaba Cloud 메타데이터 서버 주소로 이 대역에 속한다.
        VlmUrlPolicy relaxed = policy("local", true);
        VlmUrlPolicy strict = policy("prd", false);
        // when / then — 경계 포함(100.64.0.0 ~ 100.127.255.255)
        assertThatThrownBy(() -> relaxed.validate("http://100.100.100.200:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CGNAT");
        assertThatThrownBy(() -> strict.validate("https://100.100.100.200"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> strict.validate("https://100.64.0.1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> strict.validate("https://100.127.255.254"))
                .isInstanceOf(IllegalStateException.class);
        // 경계 밖(100.63.x·100.128.x)은 공인 대역이라 계속 통과해야 한다(과차단 회귀 방지)
        assertThatCode(() -> strict.validate("https://100.63.255.254")).doesNotThrowAnyException();
        assertThatCode(() -> strict.validate("https://100.128.0.1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("호스트명이_여러_주소로_해석될_때_그중_하나라도_사설대역이면_거부된다")
    void anyResolvedAddressInReservedRangeRejected() throws UnknownHostException {
        // given — G-ISSUE-22. DNS 가 여러 A/AAAA 를 돌려줄 때 첫 주소만 검사하면, 실제 커넥션이 향하는
        //   주소(JDK/OS 가 고르는)와 검사 대상이 달라진다. 실 DNS 를 조작할 수 없으므로 해석 결과를 직접 주입한다.
        InetAddress publicV4 = InetAddress.getByName("8.8.8.8");
        InetAddress anotherPublicV4 = InetAddress.getByName("1.1.1.1");
        InetAddress privateV4 = InetAddress.getByName("10.0.0.5");
        InetAddress imds = InetAddress.getByName("169.254.169.254");
        InetAddress ulaV6 = InetAddress.getByName("fd00:ec2::254");
        VlmUrlPolicy strict = policy("prd", false);
        VlmUrlPolicy relaxed = policy("local", true);

        // when / then — strict: 첫 주소가 공인이어도 뒤에 사설이 섞이면 거부
        assertThatThrownBy(() -> strict.verifyResolvedAddresses("multi.vendor.io", publicV4, privateV4))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> strict.verifyResolvedAddresses("multi.vendor.io", publicV4, imds))
                .isInstanceOf(IllegalStateException.class);
        // relaxed: 사설(RFC1918)은 허용이지만 메타데이터·ULA 는 순서와 무관하게 거부
        assertThatThrownBy(() -> relaxed.verifyResolvedAddresses("multi.vendor.io", publicV4, imds))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> relaxed.verifyResolvedAddresses("multi.vendor.io", publicV4, ulaV6))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> relaxed.verifyResolvedAddresses("multi.vendor.io", publicV4, privateV4))
                .doesNotThrowAnyException();
        // 전부 공인이면 통과(과차단 회귀 방지)
        assertThatCode(() -> strict.verifyResolvedAddresses("multi.vendor.io", publicV4, anotherPublicV4))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("기존_링크로컬_169_254_판정은_회귀없이_계속_거부된다")
    void legacyLinkLocalStillRejected() {
        // given — 대역 판정을 명시적 CIDR 비교로 교체하면서 기존 방어가 사라지지 않았는지 고정한다.
        VlmUrlPolicy relaxed = policy("dev", true);
        VlmUrlPolicy strict = policy("prd", false);
        // when / then
        assertThatThrownBy(() -> relaxed.validate("http://169.254.169.254"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("메타데이터");
        assertThatThrownBy(() -> relaxed.validate("http://[fe80::1]:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("링크로컬");
        assertThatThrownBy(() -> strict.validate("https://169.254.169.254"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> strict.validate("https://10.0.0.5"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("내부");
        assertThatThrownBy(() -> strict.validate("https://172.16.0.1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> strict.validate("https://192.168.0.1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> strict.validate("https://127.0.0.1:9400"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> strict.validate("https://0.0.0.0"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> strict.validate("https://[::1]:9400"))
                .isInstanceOf(IllegalStateException.class);
        // IPv4-mapped IPv6 로 위장해도 IPv4 규칙이 적용된다
        assertThatThrownBy(() -> strict.validate("https://[::ffff:10.0.0.5]"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("NAT64_웰노운_프리픽스에_IMDS_주소를_임베드해도_거부된다")
    void nat64WellKnownPrefixUnwrappedAndRejected() {
        // given — DEV_FIX HIGH-2. 64:ff9b::/96(RFC 6052 NAT64 well-known prefix)은 하위 32비트에
        //   IPv4 를 임베드한다. 언랩하지 않으면 IPv6 표기로 위장해 IPv4 대역 규칙(IMDS·사설)을
        //   통째로 우회할 수 있다 — 64:ff9b::a9fe:a9fe = 169.254.169.254(AWS IMDS).
        VlmUrlPolicy relaxed = policy("local", true);
        VlmUrlPolicy strict = policy("prd", false);
        // when / then — 언랩 후 IPv4 규칙이 적용돼 링크로컬/메타데이터로 거부
        assertThatThrownBy(() -> relaxed.validate("http://[64:ff9b::a9fe:a9fe]:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("메타데이터");
        assertThatThrownBy(() -> strict.validate("https://[64:ff9b::a9fe:a9fe]"))
                .isInstanceOf(IllegalStateException.class);
        // 사설(RFC1918) 임베드도 strict 에서 거부 — 10.0.0.5 를 NAT64 로 감싼 형태
        assertThatThrownBy(() -> strict.validate("https://[64:ff9b::a00:5]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("내부");
        // CGNAT(100.100.100.200 = Alibaba 메타데이터) 임베드도 relaxed 에서 거부
        assertThatThrownBy(() -> relaxed.validate("http://[64:ff9b::6464:64c8]:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CGNAT");
        // 공인 IPv4(8.8.8.8) 임베드는 통과 — NAT64 경유 공인 목적지는 정상(과차단 회귀 방지)
        assertThatCode(() -> strict.validate("https://[64:ff9b::808:808]")).doesNotThrowAnyException();
        // 프리픽스가 다른 IPv6(64:ff9c::)는 언랩 대상이 아니라 일반 IPv6 로 판정 — 공인이라 통과
        assertThatCode(() -> strict.validate("https://[64:ff9c::a9fe:a9fe]")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("IANA_특수목적_대역_프로토콜할당_벤치마킹_멀티캐스트_ClassE_는_거부된다")
    void ianaSpecialPurposeRangesRejected() {
        // given — DEV_FIX MEDIUM. 정상 위탁 대상이 될 수 없는 대역을 방어심도로 추가 차단한다
        //   (과차단 방향이라 안전 — 도커/사내 목업이 쓰는 RFC1918·loopback 은 여기에 없다).
        VlmUrlPolicy strict = policy("prd", false);
        VlmUrlPolicy relaxed = policy("local", true);
        // when / then — 192.0.0.0/24 IETF 프로토콜 할당(NAT64/DS-Lite anycast)
        assertThatThrownBy(() -> strict.validate("https://192.0.0.170"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> relaxed.validate("http://192.0.0.1:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("프로토콜 할당");
        // 198.18.0.0/15 벤치마킹 (경계 포함: 198.18 ~ 198.19)
        assertThatThrownBy(() -> strict.validate("https://198.18.0.1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> strict.validate("https://198.19.255.254"))
                .isInstanceOf(IllegalStateException.class);
        // 224.0.0.0/4 멀티캐스트 · ff00::/8 (v4/v6 대칭)
        assertThatThrownBy(() -> strict.validate("https://224.0.0.1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> relaxed.validate("http://[ff02::1]:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("멀티캐스트");
        // 240.0.0.0/4 예약(Class E) — 브로드캐스트 255.255.255.255 포함
        assertThatThrownBy(() -> strict.validate("https://240.0.0.1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> strict.validate("https://255.255.255.255"))
                .isInstanceOf(IllegalStateException.class);
        // 경계 밖은 공인 대역이라 계속 통과(과차단 회귀 방지)
        assertThatCode(() -> strict.validate("https://192.0.1.1")).doesNotThrowAnyException();
        assertThatCode(() -> strict.validate("https://198.17.255.254")).doesNotThrowAnyException();
        assertThatCode(() -> strict.validate("https://198.20.0.1")).doesNotThrowAnyException();
        assertThatCode(() -> strict.validate("https://223.255.255.254")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("정상_공인_도메인은_계속_통과한다")
    void publicAddressStillAllowed() {
        // given — 과차단 회귀 방지. 오프라인 결정성을 위해 DNS 없이 해석되는 공인 IP 리터럴을 쓴다
        //   (도메인을 쓰면 CI 네트워크 유무로 결과가 흔들린다).
        VlmUrlPolicy strict = policy("prd", false);
        VlmUrlPolicy relaxed = policy("local", true);
        // when / then
        assertThatCode(() -> strict.validate("https://8.8.8.8")).doesNotThrowAnyException();
        assertThatCode(() -> strict.validate("https://1.1.1.1:8443")).doesNotThrowAnyException();
        assertThatCode(() -> relaxed.validate("http://8.8.8.8:9400")).doesNotThrowAnyException();
        // 공인 IPv6(2000::/3 문서용 대역)도 통과
        assertThatCode(() -> strict.validate("https://[2001:4860:4860::8888]")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("해석되지_않는_컨테이너명은_완화_프로파일에서_계속_허용된다")
    void unresolvableContainerNameStillAllowedWhenRelaxed() {
        // given — 도커 밖(네이티브 bootRun)에서는 컨테이너명이 해석되지 않는다.
        //   메타데이터 대역 검사를 넣으면서 "해석 실패 → 거부" 로 바뀌면 기동이 통째로 막히는 회귀가 된다.
        String unresolvable = "http://klid-mock-server-does-not-resolve-" + System.nanoTime() + ":9400";
        VlmUrlPolicy relaxed = policy("local", true);
        // when / then — 해석 실패는 통과(완화 유지)
        assertThatCode(() -> relaxed.validate(unresolvable)).doesNotThrowAnyException();
        assertThatCode(() -> relaxed.validate("http://localhost:9400")).doesNotThrowAnyException();
        assertThatCode(() -> relaxed.validate("http://127.0.0.1:9400")).doesNotThrowAnyException();
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
