package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 노드 원장 조회 + <b>짧은 캐시</b>. DB 를 매 호출 때리지 않게 하는 유일한 지점. [@design ADR-057]
 *
 * <h3>왜 캐시하고, 왜 하필 5초인가</h3>
 * <p>노드 선택은 배정마다 원장을 본다. 캐시가 없으면 그 조회가 그대로 DB 왕복이 된다. TTL 은
 * <b>추론 계통</b>의 상태점검 주기와 같은 5초로 둔다 — 그보다 짧게 잡아도 <b>원장 값 자체가 그
 * 주기로만 갱신되므로</b> 더 새로운 값이 나오지 않는다.
 *
 * <p>⚠ <b>구 서술 정정(2026-09-08)</b> — 여기 「<b>상태점검 주기</b>와 같은 5초」라고만 적혀 있었다.
 * 주기가 <b>계통마다 갈렸으므로</b>(추론 5초 / 시계열 10초 · [@design AC-1099]) 「그 주기」가 하나가
 * 아니다. <b>TTL 을 두 주기에 맞출 필요는 없다</b> — 이 캐시가 막는 것은 배정마다의 DB 왕복이고,
 * 주기가 더 긴 계통은 그만큼 더 낡은 값을 볼 뿐이며 그것은 그 계통이 주기를 길게 잡아 <b>이미
 * 받아들인 대가</b>다. TTL 을 계통마다 가르면 같은 스냅샷을 공유하지 못해 두 목록이 서로 다른
 * 시점을 보게 된다(이 클래스가 애초에 막는 것).
 *
 * <p>5초 낡은 목록으로 배정해도 되는 이유: 배정은 자주 일어나지 않고, 그 사이 노드가 실제로
 * 죽었다면 <b>호출 시점의 서킷브레이커가 즉시</b> 잡는다. 원장은 "어디로 보낼지"를 정할 뿐
 * "살아 있는지"를 최종 판정하지 않는다.
 *
 * <p>관리자가 노드를 바꾸면 TTL 을 기다리지 않도록 {@link #invalidate()} 로 즉시 비운다.
 *
 * <h3>돌려주는 목록은 수정할 수 없다</h3>
 * <p>같은 스냅샷을 여러 호출자가 공유하므로, 한 호출자가 목록을 고치면 다음 호출자가 오염된
 * 목록을 본다. 엔티티는 트랜잭션 밖의 <b>읽기 전용 스냅샷</b>으로만 쓴다(여기서 받은 엔티티를
 * 수정해 저장하지 말 것).
 */
@Component
public class AiSrvrRegistry {

    /** 캐시 수명 — 상태점검 주기와 같다. */
    static final Duration TTL = Duration.ofSeconds(5);

    private final LsAiSrvrRepository repository;
    private final LongSupplier nanoClock;

    private volatile Snapshot snapshot;

    @Autowired
    public AiSrvrRegistry(LsAiSrvrRepository repository) {
        this(repository, System::nanoTime);
    }

    /** 시험용 — 시간 원천을 갈아 끼워 TTL 경계를 대기 없이 검증한다. */
    AiSrvrRegistry(LsAiSrvrRepository repository, LongSupplier nanoClock) {
        this.repository = repository;
        this.nanoClock = nanoClock;
    }

    /** 원장 전체(수정 불가 목록). */
    public List<LsAiSrvr> findAll() {
        Snapshot current = snapshot;
        long now = nanoClock.getAsLong();
        if (current != null && now - current.loadedAtNanos() < TTL.toNanos()) {
            return current.servers();
        }
        // 경합 시 두 스레드가 같이 읽을 수 있다 — 같은 결과를 두 번 읽을 뿐이라 잠그지 않는다.
        List<LsAiSrvr> loaded = List.copyOf(repository.findAll());
        snapshot = new Snapshot(loaded, now);
        return loaded;
    }

    /**
     * 가용 노드만 — <b>같은 스냅샷에서 거른다</b>.
     *
     * <p>상태별로 따로 질의하면 캐시를 두 번 우회하게 되고, 두 목록이 서로 다른 시점을 보게 된다.
     */
    public List<LsAiSrvr> findAvailable() {
        return findAll().stream()
                .filter(srvr -> srvr.getSrvrSttsCd() == AiSrvrStatus.AVAILABLE)
                .toList();
    }

    /** 캐시를 버린다 — 관리자가 원장을 바꿨을 때 즉시 반영되도록. */
    public void invalidate() {
        snapshot = null;
    }

    private record Snapshot(List<LsAiSrvr> servers, long loadedAtNanos) {
    }
}
