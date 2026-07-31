package kr.co.cudo.authoring.augment.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 상태조회 <b>창(window) 회전기</b> — 비종결 청크가 많아 한 번에 다 조회할 수 없을 때, 폴링마다
 * 다른 구간을 조회해 <b>모든 청크가 유한한 폴링 안에</b> 조회되게 한다 (DEV_FIX 2차 MED-1).
 *
 * <h3>왜 필요한가 — 회전이 없으면 뒤 청크는 <b>영원히</b> 조회되지 않는다</h3>
 * <p>구 구현은 {@code pending.subList(0, MAX_STATUS_QUERIES)} 로 <b>항상 앞 10개</b>만 조회했다.
 * 목록은 {@code JOB_SEQ} 오름차순 고정({@code findByDataAugSnOrderByJobSeqAsc})이라, 앞 10개가
 * 비종결로 머무는 동안 11번째 이후 청크는 <b>어떤 폴링에서도</b> 조회되지 않았다(폴링을 N 회
 * 반복해도 동일하다 — 구 주석의 "남은 청크는 다음 폴링에서 처리된다" 는 사실이 아니었다).
 * 그 결과 벤더가 이미 만들어 놓은 산출물이 회수되지 못하고 6시간 뒤 만료 스윕에서 FAILED 로
 * 폐기됐다 — HIGH-2 가 막으려던 바로 그 피해가 다른 경로로 남아 있었다.
 *
 * <h3>회전 상태를 어디에 두는가</h3>
 * <p>회전은 <b>폴링마다 전진</b>해야 하는데, 굶는 상황에서는 DB 상태가 폴링 사이에 전혀 바뀌지
 * 않는다(그것이 굶는다는 뜻이다). 따라서 "이미 있는 필드로 정렬 순서를 바꾸는" 무상태 방식으로는
 * 회전이 성립하지 않는다. 남는 선택지는 셋이고 각각 다음 이유로 탈락/채택했다:
 * <ul>
 *   <li><b>시간 슬라이스</b>(무상태): 폴링 주기와 슬라이스 주기가 배수 관계가 되면 특정 창만
 *       반복 선택되는 <b>에일리어싱 굶주림</b>이 생긴다(예: 창 2개 · 15초 슬라이스 · 30초 폴링).
 *       "유한 폴링 보장" 이 깨지므로 탈락.</li>
 *   <li><b>DB 컬럼</b>: 안전 메서드(GET)인 폴링마다 UPDATE 가 생긴다(쓰기 증폭). 게다가
 *       회전 커서는 <b>정확성이 아니라 공정성</b>만 좌우하는 값이라 영속·노드 공유가 필요 없다.</li>
 *   <li><b>프로세스 로컬 커서</b>(채택): 증강 PK 로 키를 좁힌 <b>유계</b> 캐시. 아래 참조.</li>
 * </ul>
 *
 * <h3>싱글턴 전역 오염을 만들지 않는 근거</h3>
 * <ul>
 *   <li><b>키가 증강 1건</b>({@code DATA_AUG_SN})이라 증강끼리 서로의 회전을 밀어내지 않는다.
 *       전역 카운터 하나였다면 여러 증강의 폴링이 교대로 커서를 밀어 특정 증강이 같은 창만
 *       반복해 받는 에일리어싱이 다시 생긴다.</li>
 *   <li><b>무한 증가 없음</b>({@value #MAX_TRACKED_AUGMENTS} 상한 + {@link #RETENTION} TTL,
 *       CWE-770). 게다가 창을 나눌 필요가 없는 증강(비종결 청크 ≤ 창 크기 = 정상 형상 대부분)은
 *       <b>엔트리를 만들지도, 남기지도 않는다</b> — 추적 대상은 초대형 영상뿐이다.</li>
 *   <li><b>값이 정확성에 관여하지 않는다</b>. 어떤 창을 고르든 응답은 유효하며(조회한 청크만
 *       회수·집계한다) 커서가 축출·리셋돼도 결과가 틀리지 않는다. 2노드 Active-Active 에서 노드마다
 *       커서가 달라도 각 노드의 폴링 계열이 독립적으로 전 창을 순회하므로 보장은 유지된다.</li>
 * </ul>
 *
 * <h3>보장</h3>
 * <p>비종결 청크 수 {@code n} · 창 크기 {@code w} 일 때 창은 {@code ceil(n/w)} 개의 <b>겹치지 않는</b>
 * 구간으로 나뉘고 폴링마다 다음 구간으로 넘어간다. 따라서 임의의 비종결 청크는 <b>최대
 * {@code ceil(n/w)} 번의 폴링 안에</b> 반드시 조회된다. (폴링 사이에 청크가 종결돼 {@code n} 이
 * 줄면 구간 경계가 옮겨져 한 바퀴에서 한 번 건너뛸 수 있으나, 순회는 계속되므로 유한 보장은
 * 유지된다.)
 */
@Component
class AugmentStatusWindowRotator {

    /** 커서 보존 기간 — 폴링이 끊긴 증강의 엔트리는 자동 회수된다(유한 보존). */
    static final Duration RETENTION = Duration.ofMinutes(30);

    /**
     * 동시에 추적하는 증강 수 상한 — 초과 시 Caffeine 이 오래된 엔트리부터 축출한다(메모리 상한 보장).
     *
     * <p>추적 대상은 <b>비종결 청크가 창 크기를 넘는 증강</b>(프레임 1,000장 이상)뿐이라 현실 형상에서
     * 이 상한에 닿지 않는다. 닿더라도 손실은 "회전이 처음부터 다시 시작" 뿐이다.
     */
    static final long MAX_TRACKED_AUGMENTS = 2_000L;

    private final Cache<Long, AtomicInteger> cursors;

    @Autowired
    AugmentStatusWindowRotator() {
        this(Ticker.systemTicker());
    }

    /** 테스트 전용 — TTL 회수 검증을 위해 가상 시계를 주입한다. */
    AugmentStatusWindowRotator(Ticker ticker) {
        this.cursors = Caffeine.newBuilder()
                .expireAfterWrite(RETENTION)
                .maximumSize(MAX_TRACKED_AUGMENTS)
                .ticker(ticker)
                .build();
    }

    /**
     * 이번 폴링이 조회할 구간을 돌려주고 커서를 다음 구간으로 전진시킨다.
     *
     * @param dataAugSn  증강 PK(회전 키)
     * @param pending    비종결 청크 — {@code JOB_SEQ} 오름차순(호출자 계약)
     * @param windowSize 한 폴링이 조회할 최대 건수
     * @return 조회 대상 구간(원본 목록의 뷰). 상한 이하면 전량 그대로.
     */
    <T> List<T> nextWindow(Long dataAugSn, List<T> pending, int windowSize) {
        int size = Math.max(1, windowSize);
        if (pending.size() <= size) {
            // 창을 나눌 필요가 없다 — 커서를 만들지도 남기지도 않는다(추적 대상 최소화).
            cursors.invalidate(dataAugSn);
            return pending;
        }
        int windowCount = (pending.size() + size - 1) / size;
        int start = Math.floorMod(nextIndex(dataAugSn), windowCount) * size;
        return pending.subList(start, Math.min(start + size, pending.size()));
    }

    /** 커서를 읽고 1 전진시킨다. 오버플로로 음수가 되지 않게 상한에서 접는다. */
    private int nextIndex(Long dataAugSn) {
        return cursors.get(dataAugSn, key -> new AtomicInteger())
                .getAndUpdate(value -> value == Integer.MAX_VALUE ? 0 : value + 1);
    }

    /** 테스트 전용 — 회수 후 남아 있는 커서 수. */
    long trackedCount() {
        cursors.cleanUp();
        return cursors.estimatedSize();
    }
}
