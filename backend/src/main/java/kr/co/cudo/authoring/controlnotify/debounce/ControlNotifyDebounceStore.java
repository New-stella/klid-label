package kr.co.cudo.authoring.controlnotify.debounce;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 관제 수정 통지 디바운스 윈도우 저장소 (Phase 9-C).
 *
 * <p>구 {@code ControlNotifyDebouncer} 는 윈도우를 {@code ConcurrentHashMap} 으로 <b>JVM 안</b>에 뒀다.
 * 배포 토폴로지가 2노드 Active-Active 라 그 구조에는 두 결함이 있었다:
 * <ol>
 *   <li><b>이중 flush</b> — 같은 영상의 수정이 양 노드에 나뉘어 축적되면 각자 자기 몫만 flush 해
 *       export 재생성·관제 통지가 <b>2회</b> 나갔다(관제가 같은 영상을 두 버전으로 픽업).</li>
 *   <li><b>축적분 유실</b> — 노드가 flush 전에 죽으면 그 노드에 쌓인 변경이 통째로 사라져
 *       재생성·통지가 무음 소실됐다.</li>
 * </ol>
 * 이 인터페이스는 윈도우의 <b>내용</b>과 <b>소유권</b>을 모두 노드 밖(공유 DB)으로 옮겨 두 결함을 닫는다.
 *
 * <p>구현({@link JpaControlNotifyDebounceStore})은 조건부 원자 UPDATE 로 클레임하므로 정확히 한
 * 노드만 flush 한다(CWE-362). 단위 테스트는 동일 계약의 인메모리 페이크로 대체한다.
 */
public interface ControlNotifyDebounceStore {

    /**
     * 변경 1건을 영상(rawSn)의 <b>열린 윈도우</b>에 누적한다. 열린 윈도우가 없으면 새로 연다.
     *
     * <p>{@code srcSn} 이 null 이면 영상 단위 변경으로 분리 축적한다(D-ISSUE-43). {@code changeType} 이
     * null 이면 축적할 내용이 없지만 <b>윈도우는 열린다</b> — 구 구현과 동일하게 flush 자체는 발생해야 한다.
     */
    void accumulate(Long rawSn, Long srcSn, String changeType, boolean exportRegenerated);

    /**
     * flush 후보 윈도우 PK 목록.
     *
     * @param windowCutoff 이 시각 이전에 열린 윈도우가 만료 대상(= now - 디바운스 윈도우)
     * @param leaseCutoff  이 시각 이전에 클레임된 flush 는 임차 만료(크래시 잔재)로 보고 재클레임한다
     * @param limit        한 번에 처리할 상한(무제한 조회 금지)
     */
    List<Long> findFlushableIds(LocalDateTime windowCutoff, LocalDateTime leaseCutoff, int limit);

    /**
     * 윈도우 소유권을 <b>원자적으로</b> 획득한다. 성공한 노드만 스냅샷을 받아 발송한다.
     *
     * @return 클레임에 성공했으면 그 시점의 누적 스냅샷, 다른 노드가 이미 가져갔으면 empty
     */
    Optional<DebounceWindow> claim(Long acmlSn, LocalDateTime windowCutoff, LocalDateTime leaseCutoff);

    /**
     * 발송/재산출 위임까지 마친 윈도우를 제거한다.
     *
     * <p>실패 시에는 호출하지 <b>않는다</b> — 행이 {@code FLUSHING} 으로 남아 임차 만료 후 재클레임되므로
     * 통지가 유실되지 않는다(구 인메모리 구현은 이 지점에서 실제로 소실됐다).
     */
    void complete(Long acmlSn);

    /**
     * 이 영상에 열린 축적 윈도우(PENDING 또는 FLUSHING)가 있는가 — Phase 7a-2b 재승인 폴백 판정 전용.
     *
     * <p>정상 경로라면 재검토 표시({@code REVLT_YN='Y'})를 세운 것과 축적 윈도우를 여는 것은 같은
     * {@code TaskModifiedEvent} 의 형제 {@code AFTER_COMMIT} 리스너({@code ReviewRecheckMarkListener}·
     * {@code TaskModifiedAccumulateListener})라 항상 짝이 맞는다. 짝이 깨지면(리스너 실패·수동 정리 등)
     * 표시만 있고 윈도우가 없어, 표시 해제에 의존하는 자연 flush 가 영원히 일어나지 않는다 — 호출부가
     * 이 판정으로 그 상황을 감지해 최초 승인과 동일한 경로로 폴백해야 한다.
     */
    boolean hasOpenWindow(Long rawSn);
}
