package kr.co.cudo.authoring.assignment.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Phase 7a-1 — <b>검수 승인 상태 판정 + 재검토 표시</b> 단일 원천.
 *
 * <h3>왜 별도 컴포넌트인가 (Critical)</h3>
 * <p>"영상이 검수 완료(APPROVED)인가"를 판정하는 <b>완전히 동일한 쿼리</b>
 * ({@code LsRawDataStatusRepository.findByRawDataIdIn(List.of(rawSn))} → {@code STTS_APPROVED} 비교 →
 * 행 없으면 {@code false})가 14개 클래스에 자체 {@code private} 메서드로 복제돼 있었다. 이 저장소는
 * "게이트를 호출처마다 배선하면 샌다"는 결함을 반복해 왔고({@link kr.co.cudo.authoring.video.service.DeidentReportGate}
 * 가 그 해법의 선례다), 판정 축을 바꾸는 반전 작업(CLAUDE.md "재생성·통지의 트리거는 「검수 승인」
 * 한 곳이다")이 14곳을 전부 찾아 고쳐야 하는 상태로는 다음 단계(7a-2)가 다시 새어나간다. 그래서
 * 판정과 "재검토 필요로 표시"를 <b>이 컴포넌트 하나</b>로 모은다 — 둘 다 같은 행({@code LS_RAW_DATA_STATUS})을
 * 다루는 자연스러운 짝이다.
 *
 * <h3>7a-1 — 표시를 세우고 지우기만 하는 순수 가산 단계였다</h3>
 * <p>{@link #markNeedsRecheck}/{@link #clearNeedsRecheck}/{@link #needsRecheck} 자체는 그때 이미 갖춰졌다.
 *
 * <h3>7a-2 — 표시를 읽어 흐름을 바꾼다 (완료)</h3>
 * <p>보류(디바운스 flush 가 {@code REVLT_YN='Y'} 인 영상의 윈도우를 거른다 —
 * {@code LsMonNotiAcmlRepository#findFlushableAnchors})·재승인 허용({@code ReviewService#approve} 의
 * {@code APPROVED→APPROVED} short-circuit)이 이제 이 표시를 소비한다.
 *
 * <p><b>왜 {@code ReviewService#approve} 는 {@link #clearNeedsRecheck(Long)}(REQUIRES_NEW) 를 호출하지
 * 않고 {@code LsRawDataStatus#clearNeedsRecheck()} 를 직접 호출하는가</b>: 그 메서드는 이미 같은
 * {@code approve()} 트랜잭션 안에서 관리 중인 {@code LsRawDataStatus} 엔티티를 갖고 있다. 거기서
 * {@link #clearNeedsRecheck(Long)} 를 부르면 {@link #markNeedsRecheck} 문서가 설명한 것과 같은 부류의
 * 문제가 <b>반대 방향으로</b> 발생한다 — REQUIRES_NEW 가 <b>다른 커넥션</b>으로 같은 행을 UPDATE 하려
 * 시도하는데, {@code approve()} 가 그 행에 아직 커밋 전 변경을 갖고 있다면(예: 최초 승인의
 * {@code transitionTo}) 서로 다른 커넥션 간 행 잠금 대기로 사실상 멈춘다(자기 자신을 기다리는 hang).
 * 이미 로드된 엔티티가 있는 트랜잭션 내부에서는 <b>엔티티 메서드를 직접</b> 호출하고, {@link #markNeedsRecheck}/
 * {@link #clearNeedsRecheck}(REQUIRES_NEW)는 엔티티를 갖고 있지 않은 호출부(AFTER_COMMIT 리스너 등)
 * 전용으로 남긴다.
 */
@Component
@RequiredArgsConstructor
public class ReviewApprovalGate {

    private final LsRawDataStatusRepository rawDataStatusRepository;
    /** P2b — 승인 동결 스냅샷(append-only)이 "한번이라도 승인" 판정의 1순위 근거다. */
    private final LsDatasetVideoMetaRepository datasetVideoMetaRepository;
    /** P2b — 승인 감사 로그(append-only)가 그 판정의 2순위 근거다(V97 이전 영상 보강). */
    private final LsTaskEventLogRepository taskEventLogRepository;

    /**
     * 이 영상의 검수 상태가 APPROVED(검수 완료) 인지 판정. 상태 row 가 없으면 미검수로 간주하여
     * {@code false}(매직스트링 금지 — {@link LsRawDataStatus#STTS_APPROVED} 상수 비교).
     */
    public boolean isApproved(Long rawSn) {
        return rawDataStatusRepository.findByRawDataIdIn(List.of(rawSn)).stream()
                .findFirst()
                .map(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()))
                .orElse(false);
    }

    /**
     * P2b — 이 영상이 <b>한번이라도</b> 검수 완료된 적이 있는가 (지금 상태가 아니라 <b>이력</b>).
     *
     * <h3>왜 현재 상태로는 부족한가 (실증된 구멍)</h3>
     * {@link #isApproved(Long)} 는 <b>지금</b>의 {@code DATA_STTS_CD} 만 본다. 그런데
     * {@code ReviewStateMachine} 이 {@code APPROVED → PENDING}(WORKER 재검수 재제출)을 허용하므로,
     * {@code POST /v1/reviews/{videoId}/submit} 한 번으로 상태를 내린 뒤에는 승인 영상에 걸어 둔
     * 게이트가 그대로 뚫린다.
     *
     * <h3>왜 막아야 하나 — 데이터마트 롤백 정합성</h3>
     * 프레임 이미지·JSON 은 회차별로 물리 분리돼 {@code v1} 폴더가 불변인데, <b>영상 파일은 회차별로
     * 분리되지 않아</b> 데이터마트 뷰가 항상 최신 비식별본을 가리킨다. 승인 후 재비식별이 일어나면
     * {@code v1} 이미지(옛 마스킹)와 뷰의 영상(새 마스킹)이 어긋나고, <b>데이터마트를 {@code v1} 으로
     * 되돌리면 신고로 걷어낸 개인정보가 되살아난다.</b> 프레임 폐기·복원도 같은 축이다 — 이미 산출되어
     * 외부로 나간 회차에서 프레임이 빠지거나 되살아나면 그 회차의 산출물과 어긋난다.
     *
     * <h3>판정 = 승인 동결 스냅샷 존재 <b>OR</b> 승인 감사 존재 (fail-closed)</h3>
     * 둘 다 <b>append-only</b> 라 "있었다"가 지워지지 않는다. OR 인 이유는 1순위 테이블
     * ({@code LS_DATASET_VIDEO_META})이 V97 신설이라 <b>그 이전에 승인되고 백필 전에 재제출된 영상은
     * 행이 0건</b>일 수 있고, 그 false negative 는 곧 게이트가 열리는 방향이기 때문이다.
     *
     * <h3>기각된 후보 (다시 검토하지 말 것)</h3>
     * <ul>
     *   <li>{@code LS_LABEL_VERSION} — {@code commitApproved} 가 프레임·라벨 0건이면 조기 반환해
     *       <b>라벨 0건 승인 영상은 스냅샷이 0건</b>이다(fail-open).</li>
     *   <li>{@code LS_DATASET_EXPORT} — 신고 구간·실패 시 행이 생기지 않고 비동기라 승인과 원자적이지 않다.</li>
     *   <li>{@code V_COMPLETED_VIDEO.RVW_CMPTN_DT} — 그 뷰가 라이브 {@code APPROVED} 로 게이트하므로
     *       바로 이 판정이 배제하려는 축이다.</li>
     *   <li>{@code REVLT_YN} — 재검토 표시는 다른 축이고 재승인 시 {@code N} 으로 돌아간다.</li>
     *   <li>신규 컬럼 — 불필요하다(위 두 축으로 판정이 성립한다).</li>
     * </ul>
     */
    public boolean hasEverApproved(Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        // 지금 승인 상태면 이력을 볼 필요도 없다(가장 흔한 경우를 먼저 끊어 조회를 줄인다).
        if (isApproved(rawSn)) {
            return true;
        }
        if (datasetVideoMetaRepository.existsByRawSn(rawSn)) {
            return true;
        }
        return taskEventLogRepository.existsByRawDataIdAndEventTypeCd(
                rawSn, LsTaskEventLog.EVENT_APPROVE);
    }

    /**
     * {@link #hasEverApproved(Long)} 를 <b>호출자 소유 캐시</b>로 감싼 변형 (F-2).
     *
     * <h3>왜 필요한가</h3>
     * 영상 단위 확정 저장은 <b>프레임마다</b> 이 판정을 부르고(최대 2000회) 한 번에 최대 3쿼리가 돈다 —
     * 최대 6,000 쿼리다. 게다가 그 루프는 {@code lockFramesByRawSn} 으로 <b>영상 전 프레임 행 락을 보유한
     * 상태</b>라 지연이 곧 락 보유 시간이고, 트랙 편집·병합·보간이 그만큼 대기한다.
     * 영상 1건당 {@code rawSn} 은 하나뿐이라 적중률은 100% 다.
     *
     * <p>캐시는 <b>호출자가 요청 스코프로</b> 들고 있어야 한다({@link #isApprovedCached} 와 동일 규약) —
     * 이 컴포넌트는 stateless 빈이라 요청 간 캐시를 공유하면 stale 판정이 된다.
     */
    public boolean hasEverApprovedCached(Long rawSn, Map<Long, Boolean> cache) {
        if (rawSn == null) {
            return false;
        }
        if (cache == null) {
            return hasEverApproved(rawSn);
        }
        return cache.computeIfAbsent(rawSn, this::hasEverApproved);
    }

    /**
     * {@link #isApproved(Long)} 를 호출자 소유 캐시로 감싼 변형 — 같은 rawSn 을 반복 판정하는 벌크
     * 경로(예: 프레임 벌크 저장)에서 N+1 조회를 피하기 위함. 캐시는 호출자가 요청 스코프로 들고 있어야
     * 하며(예: 벌크 메서드 로컬 {@code Map}), 이 컴포넌트는 상태를 갖지 않는다(stateless 빈이라
     * 요청 간 캐시를 공유하면 stale 판정이 된다).
     */
    public boolean isApprovedCached(Long rawSn, Map<Long, Boolean> cache) {
        return cache.computeIfAbsent(rawSn, this::isApproved);
    }

    /**
     * 재검토 필요로 표시(V177 {@code REVLT_YN='Y'}) — <b>멱등</b>({@link LsRawDataStatus#markNeedsRecheck}
     * 참조 — 이미 Y 면 dirty-checking 이 UPDATE 를 내지 않는다).
     *
     * <h3>왜 {@code REQUIRES_NEW} 인가 (Critical — 실측 버그로 확인됨)</h3>
     * <p>이 메서드의 주 호출처는 {@code TaskModifiedEvent} 를 소비하는 {@code AFTER_COMMIT} 리스너다.
     * Spring 문서가 {@code afterCommit} 규약으로 명시한 그대로 — "Use {@code PROPAGATION_REQUIRES_NEW}
     * for any transactional operation called from here"({@code AugmentResultService.handleInNewTransaction}
     * 이 이미 같은 근거로 채택한 선례) — 이다. {@code AFTER_COMMIT} 콜백은 원 트랜잭션이 물리적으로
     * 커밋된 뒤에도 {@code TransactionSynchronizationManager} 리소스가 정리(cleanup)되기 <b>전</b>에
     * 실행되므로, 기본 {@code REQUIRED} 전파는 새 트랜잭션을 여는 대신 <b>이미 끝나가는 그 트랜잭션에
     * 참여</b>해 버린다 — 그 결과 이 메서드 안의 UPDATE 가 뒤따르는 물리 커밋 없이 조용히 사라진다
     * (통합 테스트로 실측: {@code REQUIRED} 상태에서 {@code SELECT} 만 나가고 {@code UPDATE} 자체가
     * 발생하지 않았다). 행이 없으면(이례) 조용히 no-op — 이 표시는 <b>부가 신호</b>이지 존재를 강제하는
     * 대상이 아니다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markNeedsRecheck(Long rawSn) {
        if (rawSn == null) {
            return;
        }
        rawDataStatusRepository.findById(rawSn).ifPresent(LsRawDataStatus::markNeedsRecheck);
    }

    /**
     * 재검토 표시 해제(V177 {@code REVLT_YN='N'}) — <b>멱등</b>. Phase 7a-2(재승인 경로)에서 사용 예정이며,
     * 이번 단계(7a-1)에는 호출처가 없다(순수 가산 — 표시를 세우기만 한다).
     *
     * <p>{@link #markNeedsRecheck(Long)} 와 동일 근거로 {@code REQUIRES_NEW} — 7a-2 의 재승인 경로도
     * AFTER_COMMIT 또는 그에 준하는 비동기 완료 콜백에서 호출될 가능성이 높아, 지금 정해 둔다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void clearNeedsRecheck(Long rawSn) {
        if (rawSn == null) {
            return;
        }
        rawDataStatusRepository.findById(rawSn).ifPresent(LsRawDataStatus::clearNeedsRecheck);
    }

    /** 이 영상이 현재 재검토 표시 상태(REVLT_YN='Y')인가. 상태 row 가 없으면 {@code false}. */
    public boolean needsRecheck(Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        return rawDataStatusRepository.findById(rawSn)
                .map(LsRawDataStatus::needsRecheck)
                .orElse(false);
    }
}
