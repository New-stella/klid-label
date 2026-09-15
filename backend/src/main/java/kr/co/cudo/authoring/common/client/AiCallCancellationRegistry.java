package kr.co.cudo.authoring.common.client;

import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 진행 중인 <b>온디맨드</b> AI 추론을 취소 식별자로 찾아 끊기 위한 등록소.
 *
 * <h2>왜 «끊김 감지» 가 아니라 «명시적 취소» 인가 (실험으로 확인한 사실)</h2>
 * <p>원래 요구는 "클라이언트가 끊으면 진행 중 추론도 끊는다"였다. 그러나 이 스택(Tomcat)은
 * <b>클라이언트 연결 끊김을 알려주지 않는다</b> — 비동기 처리 중 유휴 구간에서 클라이언트가 소켓을
 * 정상 종료해도 {@code AsyncListener} 의 어떤 콜백도 불리지 않는다(실험:
 * {@code ClientDisconnectObservabilityProbeTest}, {@code Connection: close}/keep-alive 두 형태 모두).
 * 동기 처리 중에는 관측 수단 자체가 없다.
 *
 * <p>그래서 취소는 <b>화면이 보내는 별도 요청</b>으로 구현한다. 화면은 추론 요청에 취소 식별자를
 * 실어 보내고, 취소 버튼을 누르면 그 식별자로 취소 API 를 부른다.
 *
 * <h2>범위 — 사람이 기다리는 온디맨드 경로뿐</h2>
 * <p>배치 파이프라인은 이 등록소를 <b>거치지 않는다</b>. 두 축은 같은 클라이언트·같은 서킷 브레이커를
 * 공유하므로 공유 지점을 건드리면 배치까지 영향을 받는다. 여기서 하는 일은 «요청 스레드에 스코프를
 * 매어 두는 것» 뿐이고, 배치 스레드에는 스코프가 매이지 않으므로 {@link CancellableAiCall} 이
 * <b>기존 블로킹 동작을 그대로</b> 수행한다(회귀 고정: {@code AiCallCancellationTest}).
 *
 * <h2>한계 (인지·수용)</h2>
 * <ul>
 *   <li><b>노드 로컬</b>이다. 2노드 Active-Active 에서 취소 요청이 다른 노드로 가면 찾지 못한다
 *       (그때는 «취소하지 못했다» 를 응답으로 정직하게 알린다). 공유 저장소나 고정 라우팅이 필요한데
 *       둘 다 별도 결정이라 여기서 만들지 않았다.</li>
 *   <li>취소는 <b>우리 쪽 연결을 끊는 것</b>까지다. 그 연결을 받은 추론 서버가 계산을 즉시 멈추는지는
 *       그쪽 구현에 달렸다.</li>
 * </ul>
 */
@Slf4j
@Component
public class AiCallCancellationRegistry {

    /**
     * 취소 식별자 형식 — 영문·숫자·{@code -}·{@code _} 만, 1~64자.
     *
     * <p>이 값은 클라이언트가 정하고 <b>로그에 남는다</b>. 제약이 없으면 개행이 섞여 로그 위조가 되고
     * (CWE-117), 길이 제한이 없으면 메모리를 무제한으로 잡는다(CWE-770).
     */
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * 동시에 추적하는 요청 수 상한.
     *
     * <p>스코프는 요청이 끝날 때 {@code finally} 로 지워지므로 정상 경로에서는 쌓이지 않는다. 이
     * 상한은 «비정상 종료가 반복되는 상황» 에서 맵이 무한정 커지는 것을 막는 안전선이다. 상한을
     * 넘으면 요청을 <b>거부하지 않고 추적만 포기</b>한다 — 취소 기능은 편의이고, 그것 때문에 추론
     * 자체가 실패하면 안 된다(fail-open은 기능, fail-closed는 자원).
     */
    static final int MAX_TRACKED = 512;

    /**
     * 요청 스레드에 매인 스코프.
     *
     * <p>온디맨드 경로는 요청 스레드에서 그대로 추론을 기다리므로(블로킹) 스레드 로컬로 충분하다.
     * ⚠ 다른 스레드로 넘겨 실행하는 경로가 생기면 이 배선은 따라가지 않는다 — 그때는 스코프를
     * 명시적으로 전달해야 한다.
     */
    private static final ThreadLocal<AiCallScope> CURRENT = new ThreadLocal<>();

    private final Map<String, AiCallScope> scopes = new ConcurrentHashMap<>();

    /**
     * 취소 소유자 키 — <b>채널 + 토큰 subject</b>. 등록({@link AiCallCancellationInterceptor})과 취소
     * (내부·포털 취소 창구)가 <b>이 한 함수</b>로 키를 만든다.
     *
     * <p>subject 만 비교하면 포털 사용자 식별자와 내부 사용자 식별자가 <b>같은 문자열</b>일 때 서로의 요청을
     * 끊을 수 있다(두 채널은 식별자 발급 주체가 다르다 — CWE-639). 같은 채널 안의 판정 결과는 subject 비교와
     * 같다.
     *
     * @return 주체·subject 가 없으면 {@code null}(= 추적하지 않음 / 끊지 못함)
     * @design API-204
     */
    public static String ownerKey(TokenClaims claims) {
        if (claims == null || claims.sub() == null || claims.sub().isBlank()) {
            return null;
        }
        return (claims.channel() == null ? "-" : claims.channel().name()) + ":" + claims.sub();
    }

    /**
     * 이 스레드에 스코프를 매고 등록소에 올린다.
     *
     * @param requestId 클라이언트가 준 취소 식별자. {@code null}·형식 위반·상한 초과면 <b>추적하지
     *                  않는 스코프</b>를 돌려준다(기존 동작 그대로).
     * @param ownerId   취소를 허용할 주체 — {@link #ownerKey}(채널 + 토큰 subject)
     */
    public AiCallScope open(String requestId, String ownerId) {
        AiCallScope scope = create(requestId, ownerId);
        CURRENT.set(scope);
        return scope;
    }

    private AiCallScope create(String requestId, String ownerId) {
        if (requestId == null || ownerId == null || !REQUEST_ID.matcher(requestId).matches()) {
            return AiCallScope.untracked();
        }
        if (scopes.size() >= MAX_TRACKED) {
            log.warn("[AiCancel] 추적 상한({})에 도달해 이 요청은 취소할 수 없다 — 추론은 그대로 진행한다",
                    MAX_TRACKED);
            return AiCallScope.untracked();
        }
        AiCallScope scope = new AiCallScope(requestId, ownerId, this::release);
        AiCallScope previous = scopes.putIfAbsent(requestId, scope);
        if (previous != null) {
            // 같은 식별자를 동시에 두 번 쓰면 «어느 쪽이 취소되는지» 가 불확실해진다.
            // 뒤에 온 쪽을 추적하지 않는 것으로 두어, 취소가 엉뚱한 요청을 끊지 않게 한다.
            log.warn("[AiCancel] 이미 사용 중인 취소 식별자라 추적하지 않는다 id={}",
                    LogSanitizer.sanitize(requestId));
            return AiCallScope.untracked();
        }
        return scope;
    }

    /** 스레드 결속을 푼다 — 요청 처리 끝에서 <b>반드시</b> 부른다(스레드는 재사용된다). */
    public void unbind() {
        CURRENT.remove();
    }

    /** 현재 스레드에 매인 스코프. 없으면 {@code null}(= 취소 대상이 아닌 경로). */
    static AiCallScope current() {
        return CURRENT.get();
    }

    private void release(AiCallScope scope) {
        if (scope.isTracked()) {
            scopes.remove(scope.requestId(), scope);
        }
    }

    /**
     * 그 식별자의 진행 중 추론을 끊는다.
     *
     * @param ownerId 요청자 — <b>자기 것만</b> 끊을 수 있다(CWE-639). 남의 것이면 «없음» 과 똑같이
     *                다뤄, 응답이 «그 식별자가 존재하는가» 를 알려주는 오라클이 되지 않게 한다.
     * @return 실제로 끊었으면 {@code true}
     */
    public boolean cancel(String requestId, String ownerId) {
        if (requestId == null || ownerId == null || !REQUEST_ID.matcher(requestId).matches()) {
            return false;
        }
        AiCallScope scope = scopes.get(requestId);
        if (scope == null || !scope.isOwnedBy(ownerId)) {
            return false;
        }
        boolean cancelled = scope.cancel();
        if (cancelled) {
            log.info("[AiCancel] 사용자 취소로 진행 중 추론을 끊었다 id={}", LogSanitizer.sanitize(requestId));
        }
        return cancelled;
    }

    /** 테스트 관측용 — 추적 중인 요청 수. */
    int trackedCount() {
        return scopes.size();
    }
}
