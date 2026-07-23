package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.dataset.repository.BackfillTargetRow;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.evntanno.service.EvntAnnoReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 데이터마트 통합 메타 <b>백필</b> — 이 기능(스냅샷 기반 뷰) 배포 <b>이전</b>에 이미 검수 승인(APPROVED)
 * 됐던 모든 영상은 {@code LS_DATASET_VIDEO_META} 스냅샷이 없어 재정의된 {@code V_COMPLETED_VIDEO}
 * 에서 소실된다. 이를 막기 위해, 활성 스냅샷이 없는 APPROVED 영상을 소급 동결(materialize)한다.
 *
 * <p>구현 원칙(정확성 우선):
 * <ul>
 *   <li>SQL 재구현 금지 — {@link DatasetVideoMetaSnapshotService#materialize(Long, java.time.LocalDateTime)}
 *       기존 materialize 경로를 그대로 호출해 <b>동일한 MNG_* 조인·파생·SNPSHT_HASH</b> 를 보장한다
 *       (순수 SQL 백필은 materialize 로직과 drift 위험).</li>
 *   <li>승인 시각 소급 — {@code RVW_CMPL_DT} 를 {@code now()} 가 아니라 과거 APPROVED 전이 시각
 *       ({@code LS_RAW_DATA_STATUS.UPD_DT}) 으로 채운다.</li>
 *   <li>멱등 — {@link LsDatasetVideoMetaRepository#findApprovedWithoutActiveSnapshot()} 의 NOT EXISTS
 *       가드로 이미 동결된 영상은 대상에서 제외되어 재실행이 안전하다(중복 없음).</li>
 *   <li>내결함 — 개별 영상 materialize 실패(예: 소스 누락)는 로깅 후 건너뛰고 나머지를 계속 처리한다.
 *       각 materialize 는 자체 {@code controlTransactionManager} 트랜잭션(REQUIRED)으로 독립 커밋된다
 *       (한 건 실패가 전체를 롤백하지 않음).</li>
 * </ul>
 *
 * <p>보안: 백필 페이로드는 materialize 와 동일하게 비식별 메타만 동결하며 PII/토큰/원본 이미지를
 * 포함하지 않는다. 로그는 rawSn·건수 식별자만 남긴다(CWE-359/209).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetVideoMetaBackfillService {

    /** event_annotation 치유 배치 1회 조회 상한(대량 영상 대비 페이징 — 무제한 findAll 금지). */
    private static final int HEAL_BATCH_SIZE = 200;

    private final LsDatasetVideoMetaRepository metaRepository;
    private final DatasetVideoMetaSnapshotService snapshotService;
    /** event_annotation 지연 동결 치유(autoApprove→materialize)를 원자 트랜잭션으로 수행하는 어댑터. */
    private final EvntAnnoReviewService evntAnnoReviewService;

    /**
     * 활성 스냅샷이 없는 모든 APPROVED 영상을 소급 동결한다.
     *
     * @return 실제 동결에 성공한 영상 건수
     */
    public int backfill() {
        List<BackfillTargetRow> targets = metaRepository.findApprovedWithoutActiveSnapshot();
        if (targets.isEmpty()) {
            log.info("[Dataset] backfill — no target (all APPROVED videos already materialized)");
            return 0;
        }
        int done = 0;
        for (BackfillTargetRow t : targets) {
            try {
                snapshotService.materialize(t.getRawSn(), t.getApprovedAt());
                done++;
            } catch (RuntimeException e) {
                // 개별 실패는 격리 — 나머지 영상 백필은 계속한다(부분 진행 허용). 본문/PII 미출력.
                log.warn("[Dataset] backfill materialize failed rawSn={}", t.getRawSn(), e);
            }
        }
        log.info("[Dataset] backfill completed targets={} materialized={}", targets.size(), done);
        return done;
    }

    /**
     * event_annotation 지연 동결 <b>치유</b>(HIGH — 사용자 지목 rawSn 24) — 이미 APPROVED + 활성 스냅샷은
     * 있으나 {@code EVNT_ANNO_CN=NULL} 로 동결돼 export 에서 event_annotation 이 영구 누락된 영상을 소급
     * 치유한다. 기존 {@link #backfill()} 은 활성 스냅샷이 이미 있는 rawSn 24 를 대상에서 제외하므로
     * 별도 치유 경로가 필요하다.
     *
     * <p>각 대상은 {@link EvntAnnoReviewService#healLateFrozenEventAnnotation(Long)} 로 autoApprove→
     * materialize 를 <b>원자 트랜잭션</b>으로 수행한다(부분 실패 격리 — 한 건 실패가 나머지를 롤백하지 않음).
     * 치유 성공 시 {@code EVNT_ANNO_CN} 이 채워져 다음 배치 조회에서 자동으로 빠지므로 {@code LIMIT} 배치
     * 반복이 수렴한다. 실패로 남은 rawSn 은 {@code attempted} 집합으로 재시도를 차단해 무한 루프를 막는다
     * (진행 없는 배치면 중단). REJECTED·event_annotation 부재 영상은 치유 대상 쿼리/서비스에서 제외된다.
     *
     * @return 실제 재동결에 성공한 영상 건수
     */
    public int healMissingEventAnnotation() {
        int healed = 0;
        Set<Long> attempted = new HashSet<>();
        while (true) {
            List<BackfillTargetRow> targets = metaRepository.findEventAnnoHealTargets(HEAL_BATCH_SIZE);
            if (targets.isEmpty()) {
                break;
            }
            boolean progressed = false;
            for (BackfillTargetRow t : targets) {
                Long rawSn = t.getRawSn();
                if (!attempted.add(rawSn)) {
                    // 이미 시도(실패로 잔존) — 재시도 안 함(무한 루프 방지).
                    continue;
                }
                progressed = true;
                try {
                    if (evntAnnoReviewService.healLateFrozenEventAnnotation(rawSn)) {
                        healed++;
                    }
                } catch (RuntimeException e) {
                    // 개별 실패는 격리 — 나머지 영상 치유는 계속한다. 본문/PII 미출력.
                    log.warn("[Dataset] event_annotation heal failed rawSn={}", rawSn, e);
                }
            }
            if (!progressed) {
                // 배치 전체가 이미 시도된(실패 잔존) rawSn — 더 진행 불가, 중단.
                break;
            }
        }
        log.info("[Dataset] event_annotation heal completed healed={}", healed);
        return healed;
    }
}
