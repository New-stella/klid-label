package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.dataset.repository.BackfillTargetRow;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

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

    private final LsDatasetVideoMetaRepository metaRepository;
    private final DatasetVideoMetaSnapshotService snapshotService;

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
}
