package kr.co.cudo.authoring.common.client;

import java.net.URI;
import java.util.Optional;

/**
 * <b>「이 기준 주소로 절대 목적지를 만들 수 있는가」</b> 하나만 답하는 술어 — 노드 분산의 단일 판정 지점.
 * [@design ADR-057]
 *
 * <h3>왜 한 곳이어야 하나 (이것이 갈리면 원장이 거짓말을 한다)</h3>
 * <p>노드 분산에는 판정이 <b>두 지점</b>에 필요하다: ①고를 때(선택기 — 이 장비로 보낼 수 있는가)
 * ②보낼 때(클라이언트 — 이 주소를 절대 URI 로 바꾼다). 두 지점이 <b>서로 다른 기준</b>을 쓰면
 * 「고를 수는 있는데 보낼 수는 없는 장비」가 생기고, 그러면 위탁 원장에는 「A 로 보냈다」가 남는데
 * 요청은 배포 기본 주소로 나간다 — <b>오류가 아니라 조용한 어긋남</b>이라 로그에도 남지 않고, 그
 * 원장은 다음 배분의 <b>입력</b>이라 어긋남이 누적된다(요청을 받지 않은 장비의 부하가 올라가고
 * 배분이 그 장비를 피하기 시작하는 자기강화).
 *
 * <p>실제로 그 구멍이 있었다 — 주소가 공백인 장비를 선택기가 후보로 내주면 스텝은 그 장비를
 * 원장에 적고, 클라이언트는 공백을 「장비 미선택」으로 읽어 상대 경로로 보냈다.
 *
 * <h3>★ 이것은 주소 <b>형식 검증기가 아니다</b> — 만들지 말 것</h3>
 * <p>스킴({@code http}/{@code https})·placeholder·예약 대역 판정은 <b>연동 주소 정책</b>
 * ({@code VlmUrlPolicy})의 소관이고, 선택 단계가 그 정책을 직접 부른다. 여기에 그 규칙을 얹으면
 * 그것이 <b>두 번째 진실원</b>이 되어 정책과 어긋난다. 이 클래스가 답하는 것은 오직
 * 「그 주소에 창구 경로를 붙여 <b>그 경로 그대로</b> 절대 목적지를 만들 수 있는가」이며, 그 답이
 * 거짓인 주소는 <b>애초에 그 창구로 보낼 수 없다</b> — 형식 취향이 아니라 물리적 불가능이다.
 *
 * <p>대역 차단(SSRF)도 하지 않는다 — 연동 4종은 내부망 별도 장비에 있을 수 있고 아웃바운드 통제는
 * 인프라 계층의 몫이라는 것이 이 저장소의 확정 정책이다.
 *
 * <h3>★ 「절대 URI + host 추출」만으로는 답이 틀린다 (2026-09-01 정정)</h3>
 * <p>세 가지 주소가 그 둘을 통과하면서도 <b>창구 경로를 잃는다</b>:
 * <ul>
 *   <li>{@code http://host?x=1} — 이어붙인 경로가 통째로 <b>질의로 흡수</b>돼 {@code POST /} 로 나간다.</li>
 *   <li>{@code http://host/#z} — 경로가 <b>프래그먼트로 흡수</b>돼 역시 {@code POST /} 로 나간다.</li>
 *   <li>{@code http://u:p@host} — 목적지가 아니라 <b>자격증명</b>이 URI 에 실린다. 그 주소로 만든
 *       목적지는 「그 장비로 가는 목적지」가 아니라 「그 자격으로 붙는 목적지」이고, 값이 예외·로그·
 *       원장으로 새면 자격증명 노출이다(CWE-522/532).</li>
 * </ul>
 * <p>셋 다 <b>원장은 거짓말하지 않는다</b>(요청은 그 장비로 간다). 그런데 창구 경로가 사라져 벤더가
 * 404/405 를 돌려주고 그것은 <b>비재시도 확정 실패</b>라 재개해도 같은 주소로 같은 결과가 나온다 —
 * 결정적 무한 재실패다. 게다가 운영자가 보는 것은 실패 사유 상수뿐이라(주소는 로그에 싣지 않는다)
 * 「주소에 {@code ?}·{@code #} 가 있다」는 원인에 도달할 수 없다. 그래서 <b>술어가 처음부터 「아니오」</b>
 * 라고 답해야 그 장비가 후보에서 빠진다.
 *
 * <p>⚠ <b>빠진 뒤의 결과는 「후보가 남는가」로 갈린다 (2026-09-07 정정)</b> — 여기 「위탁이 배포 기본
 * 주소로 정상 진행된다」고만 적혀 있었으나 그것은 <b>다른 후보가 남을 때</b>의 이야기다. 걸러 낸 결과
 * <b>쓸 수 있는 후보가 0</b>이 되면 선택기는 사유를 가리지 않고 <b>위탁을 거부</b>하며 어떤 주소로도
 * 폴백하지 않는다. [@design AC-1093]
 */
public final class PinnedTarget {

    private PinnedTarget() {
    }

    /**
     * 이 기준 주소로 절대 목적지를 만들 수 있는가 — <b>선택 단계가 쓰는 얼굴</b>.
     *
     * <p>거짓이면 그 장비는 후보에서 빠져야 한다. 후보로 두면 원장과 목적지가 갈린다(위 클래스 주석).
     * 경로는 판정에 영향을 주지 않으므로(창구 경로는 전부 {@code /} 로 시작하는 상수) 루트로 본다.
     */
    public static boolean canPin(String baseAddr) {
        return resolve(baseAddr, "/").isPresent();
    }

    /**
     * 기준 주소 + 창구 경로 → <b>절대 목적지</b>. 만들 수 없으면 {@code empty}.
     *
     * <p>후행 슬래시는 떼어 경로와 이어붙일 때 세그먼트가 겹치지 않게 한다. 절대 URI 가 아니거나
     * 호스트를 뽑을 수 없으면 목적지를 만들 수 없는 것으로 본다 — 그런 값은 상대 URI 가 되어
     * loopback 으로 나가지, 그 주소로 나가지 않는다.
     *
     * <p><b>질의·프래그먼트·자격증명이 붙은 기준 주소도 「만들 수 없다」로 답한다</b>(위 클래스 주석
     * §「절대 URI + host 추출」만으로는). 판정은 두 겹이다 — ①그 세 구성요소의 유무를 직접 보고
     * ②만들어진 목적지의 경로가 <b>요청한 창구 경로로 끝나는지</b> 되확인한다. ②는 술어의 문장을
     * 그대로 옮긴 자기검사라, 앞으로 새로운 흡수 형태가 나와도 함께 걸린다.
     *
     * <p><b>예외를 던지지 않는다</b> — 던지면 예외 메시지에 주소 원문이 실려 그대로 영속·기록될 수
     * 있다(CWE-497 · 내부 토폴로지 노출). 판정 결과만 돌려주고, 「시끄럽게 실패할지」는 호출자가 정한다.
     */
    public static Optional<URI> resolve(String baseAddr, String path) {
        if (baseAddr == null || baseAddr.isBlank()) {
            return Optional.empty();
        }
        String base = baseAddr.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(base + (path == null ? "" : path));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!uri.isAbsolute()) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        // ① 경로를 삼키거나(질의·프래그먼트) 목적지가 아닌 것을 싣는(자격증명) 기준 주소.
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            return Optional.empty();
        }
        // ② 자기검사 — 요청한 창구 경로가 목적지에 그대로 남았는가.
        String requested = path == null ? "" : path;
        String resolvedPath = uri.getRawPath();
        if (!requested.isEmpty() && (resolvedPath == null || !resolvedPath.endsWith(requested))) {
            return Optional.empty();
        }
        return Optional.of(uri);
    }
}
