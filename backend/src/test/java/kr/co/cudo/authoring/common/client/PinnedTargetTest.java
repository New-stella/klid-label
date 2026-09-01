package kr.co.cudo.authoring.common.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>「이 주소로 절대 목적지를 만들 수 있는가」</b> 단일 술어의 계약. [@design ADR-057]
 *
 * <p>이 술어가 <b>선택</b>과 <b>전송</b> 두 지점에서 같은 답을 내야 위탁 원장이 정직해진다. 갈리면
 * 「고를 수는 있는데 보낼 수는 없는 장비」가 생기고, 그때 원장에는 「A 로 보냈다」가 남는데 요청은
 * 배포 기본 주소로 나간다(오류가 아니라 조용한 어긋남).
 */
class PinnedTargetTest {

    @Test
    @DisplayName("정상_주소는_창구_경로를_붙인_절대_목적지가_된다")
    void 정상_주소는_절대_목적지가_된다() {
        Optional<URI> target = PinnedTarget.resolve("http://ts02.internal:9500", "/v1/videovlm-klid/describe");

        assertThat(target).contains(URI.create("http://ts02.internal:9500/v1/videovlm-klid/describe"));
    }

    @Test
    @DisplayName("후행_슬래시는_떼고_붙인다 — 세그먼트가 겹치지 않는다")
    void 후행_슬래시는_떼고_붙인다() {
        assertThat(PinnedTarget.resolve("http://ts02:9500///", "/describe"))
                .contains(URI.create("http://ts02:9500/describe"));
    }

    @Test
    @DisplayName("★빈_주소는_핀할_수_없다 — NOT NULL 만으로는 빈 문자열이 통과한다")
    void 빈_주소는_핀할_수_없다() {
        // 원장 CHECK 제약은 NULL 만 막는다 — 운영자가 공백을 넣으면 여기까지 온다.
        assertThat(PinnedTarget.canPin(null)).isFalse();
        assertThat(PinnedTarget.canPin("")).isFalse();
        assertThat(PinnedTarget.canPin("   ")).isFalse();
    }

    @Test
    @DisplayName("★스킴이_없으면_핀할_수_없다 — URI.create 는 여기에 예외를 던지지 않는다")
    void 스킴이_없으면_핀할_수_없다() {
        // 구 서술("주소 꼴이 아니면 URI.create 가 예외를 던진다")이 부정확했음을 고정한다.
        assertThat(URI.create("ts02.internal:9500").isAbsolute())
                .as("스킴 없는 문자열도 예외 없이 URI 가 된다 — 그래서 판정이 따로 필요하다")
                .isTrue(); // "ts02.internal" 이 스킴으로 해석된다(호스트는 없다)
        assertThat(PinnedTarget.canPin("ts02.internal:9500")).isFalse();
        assertThat(PinnedTarget.canPin("/relative/path")).isFalse();
    }

    @Test
    @DisplayName("★파싱할_수_없는_주소도_예외가_아니라_핀_불가로_답한다")
    void 파싱할_수_없는_주소는_예외가_아니다() {
        // 예외를 던지면 그 메시지에 주소 원문이 실려 처리 이력에 그대로 영속된다(CWE-497).
        assertThat(PinnedTarget.canPin("http://호스트 이름:9500")).isFalse();
        assertThat(PinnedTarget.resolve("http://[bad", "/describe")).isEmpty();
    }

    @Test
    @DisplayName("호스트를_뽑을_수_없으면_핀할_수_없다")
    void 호스트를_뽑을_수_없으면_핀할_수_없다() {
        assertThat(PinnedTarget.canPin("http://")).isFalse();
    }

    /**
     * ★ <b>경로를 삼키는 주소는 「만들 수 있다」가 아니다</b> — 절대 URI + host 추출만 보던 구 판정의 구멍.
     *
     * <p>세 입력 모두 구 판정을 <b>통과</b>했고 요청도 그 장비로 <b>나갔다</b>(원장은 거짓말하지 않는다).
     * 틀어진 것은 창구 경로다 — 아래 대조 단언이 보여주듯 이어붙인 경로가 질의·프래그먼트로 흡수돼
     * 실제 요청은 {@code POST /} 가 된다. 벤더는 404/405 를 주고 그것은 비재시도 확정 실패라, 재개해도
     * 같은 주소로 같은 결과가 나온다(결정적 무한 재실패). 운영자가 보는 것은 사유 상수뿐이라 원인이
     * 「주소에 {@code ?}·{@code #} 가 있다」임에 도달할 수 없다.
     */
    @Test
    @DisplayName("★경로를_삼키는_주소는_핀할_수_없다 — 질의·프래그먼트로 창구 경로가 흡수된다")
    void 경로를_삼키는_주소는_핀할_수_없다() {
        String describe = "/v1/videovlm-klid/describe";

        // 대조 — 구 판정이 왜 통과시켰는지, 그리고 그 결과가 무엇인지를 그대로 보인다.
        URI swallowedByQuery = URI.create("http://ts02:9500?x=1" + describe);
        assertThat(swallowedByQuery.isAbsolute()).isTrue();
        assertThat(swallowedByQuery.getHost()).isEqualTo("ts02");
        assertThat(swallowedByQuery.getRawPath())
                .as("창구 경로가 질의로 흡수돼 실제 요청은 POST / 가 된다")
                .isEmpty();
        URI swallowedByFragment = URI.create("http://ts02:9500/#z" + describe);
        assertThat(swallowedByFragment.isAbsolute()).isTrue();
        assertThat(swallowedByFragment.getHost()).isEqualTo("ts02");
        assertThat(swallowedByFragment.getRawPath())
                .as("창구 경로가 프래그먼트로 흡수돼 실제 요청은 POST / 가 된다")
                .isEqualTo("/");

        // then — 술어는 「만들 수 없다」로 답해야 한다(그래야 그 장비가 후보에서 빠진다).
        assertThat(PinnedTarget.resolve("http://ts02:9500?x=1", describe)).isEmpty();
        assertThat(PinnedTarget.resolve("http://ts02:9500/#z", describe)).isEmpty();
        assertThat(PinnedTarget.canPin("http://ts02:9500?x=1")).isFalse();
        assertThat(PinnedTarget.canPin("http://ts02:9500/#z")).isFalse();
        assertThat(PinnedTarget.canPin("http://ts02:9500#z")).isFalse();
    }

    /**
     * ★ <b>자격증명이 실린 주소는 목적지가 아니다</b> — 그 URI 는 「그 장비로 가는 목적지」가 아니라
     * 「그 자격으로 붙는 목적지」다. 값이 예외·로그·원장으로 새면 그대로 자격증명 노출이고
     * (CWE-522/532), 표식이 붙은 요청은 자격증명 가드도 거치지 않는다.
     *
     * <p>연동 주소 등록 입구({@code IntegrationEndpointUrlValidator})는 이미 userinfo 를 거부하지만
     * <b>노드 원장 주소에는 그 입구가 없다</b> — 그래서 여기서 답이 「아니오」여야 한다.
     */
    @Test
    @DisplayName("★자격증명이_실린_주소는_핀할_수_없다 — 원장 주소에는 등록 입구가 없다")
    void 자격증명이_실린_주소는_핀할_수_없다() {
        assertThat(URI.create("http://u:p@ts02:9500/describe").getHost())
                .as("구 판정은 host 를 뽑을 수 있으니 통과시켰다")
                .isEqualTo("ts02");

        assertThat(PinnedTarget.canPin("http://u:p@ts02:9500")).isFalse();
        assertThat(PinnedTarget.resolve("http://u:p@ts02:9500", "/describe")).isEmpty();
        assertThat(PinnedTarget.canPin("http://admin@ts02:9500")).isFalse();
    }

    @Test
    @DisplayName("경로_있는_기준주소는_계속_핀할_수_있다 — 과차단 회귀 방지")
    void 경로_있는_기준주소는_계속_핀할_수_있다() {
        // 기준 주소에 경로 접두가 있는 형태는 정상이다(리버스 프록시 뒤 배치 등).
        assertThat(PinnedTarget.resolve("http://ts02:9500/vlm", "/describe"))
                .contains(URI.create("http://ts02:9500/vlm/describe"));
        assertThat(PinnedTarget.canPin("http://ts02:9500/vlm")).isTrue();
        assertThat(PinnedTarget.canPin("https://ts02.internal")).isTrue();
        // 스킴 판정은 이 술어의 일이 아니다 — 연동 주소 정책이 따로 본다(두 번째 진실원 금지).
        assertThat(PinnedTarget.canPin("ftp://ts02:9500")).isTrue();
    }
}
