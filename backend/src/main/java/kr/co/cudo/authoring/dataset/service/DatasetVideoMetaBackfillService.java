package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.dataset.repository.BackfillTargetRow;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.evntanno.service.EvntAnnoReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
public class DatasetVideoMetaBackfillService {

    /** event_annotation 치유 배치 1회 조회 상한(대량 영상 대비 페이징 — 무제한 findAll 금지). */
    private static final int HEAL_BATCH_SIZE = 200;

    /** 촬영환경 정정 배치 1회 조회 상한(페이징 — 무제한 findAll 금지). */
    private static final int ENV_CORRECTION_BATCH_SIZE = 100;

    private final LsDatasetVideoMetaRepository metaRepository;
    private final DatasetVideoMetaSnapshotService snapshotService;
    /** event_annotation 지연 동결 치유(autoApprove→materialize)를 원자 트랜잭션으로 수행하는 어댑터. */
    private final EvntAnnoReviewService evntAnnoReviewService;
    /** 촬영환경 레거시 파생 동결값 정정 1건을 원자 트랜잭션으로 수행하는 어댑터. */
    private final DatasetVideoMetaEnvCorrectionTx envCorrectionTx;

    /**
     * 1회 실행(기동)당 정정 상한 — <b>폭주 방지</b>. 정정 1건마다 export 폴더가 새 버전으로 전량
     * 재생성되고 관제 통지가 나가므로, 승인 영상이 많은 운영 환경에서 한 번에 전부 돌리면 스토리지·
     * 통지가 몰린다. 남은 대상은 다음 기동에서 이어서 처리한다(멱등이라 안전).
     */
    private final int envCorrectionMaxPerRun;

    public DatasetVideoMetaBackfillService(
            LsDatasetVideoMetaRepository metaRepository,
            DatasetVideoMetaSnapshotService snapshotService,
            EvntAnnoReviewService evntAnnoReviewService,
            DatasetVideoMetaEnvCorrectionTx envCorrectionTx,
            @Value("${authoring.dataset-video-meta.env-correction.max-per-run:200}")
            int envCorrectionMaxPerRun) {
        this.metaRepository = metaRepository;
        this.snapshotService = snapshotService;
        this.evntAnnoReviewService = evntAnnoReviewService;
        this.envCorrectionTx = envCorrectionTx;
        this.envCorrectionMaxPerRun = Math.max(1, envCorrectionMaxPerRun);
    }

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

    /**
     * 촬영환경 정정 <b>대상 건수</b>만 센다(dry-run) — 정정(재동결·export 재생성·통지)을 <b>수행하지 않는다</b>.
     *
     * <p>수동 트리거({@code GET /v1/dev/dataset-video-meta/shooting-env-correction-targets})가 실행 전
     * 규모를 확인하고, 실행 후 잔여 건수를 응답에 담는 데 쓴다. 판별식은
     * {@link LsDatasetVideoMetaRepository#ENV_CORRECTION_PREDICATE} 하나이므로 조회와 실행이 어긋나지 않는다.
     *
     * @return 아직 정정되지 않은 레거시 파생 동결 영상 건수
     */
    public long countDerivedShootingEnvCorrectionTargets() {
        return metaRepository.countShootingEnvCorrectionTargets();
    }

    /**
     * M-1(Phase 10B) — 촬영환경 <b>레거시 파생 동결값 정정</b> 백필.
     *
     * <p>E-ISSUE-42 로 동결 경로의 파생 폴백을 제거했지만 그 이전에 파생값({@code NGT}/{@code SUMMER})으로
     * 동결된 스냅샷은 그대로 남아, ①관제 뷰에 추정값이 관측값처럼 노출되고 ②{@code VideoMetaMapper} 의
     * {@code raw → meta} 폴백을 타고 <b>재-export 되는 새 버전 폴더에도 다시 기록</b>된다(오염 확산).
     * 기존 {@link #backfill()} 은 "활성 스냅샷이 없는" 영상만 대상이라 이 행들을 <b>영구 제외</b>하므로
     * 별도 정정 경로가 필요하다.
     *
     * <p>대상 판별식·범위 제한(날씨 제외, 반대 방향 제외)은
     * {@link LsDatasetVideoMetaRepository#ENV_CORRECTION_PREDICATE} 참조. 정정 1건은
     * {@link DatasetVideoMetaEnvCorrectionTx#correct(Long)} 가 재동결 + export 재생성 동반 통지 발행을
     * 원자 트랜잭션으로 수행한다(부분 실패 격리).
     *
     * <p><b>폭주 방지</b>: 정정 1건마다 export 폴더가 새 버전으로 전량 재생성되므로 1회 실행당
     * {@link #envCorrectionMaxPerRun} 건으로 제한하고, 조회는 {@link #ENV_CORRECTION_BATCH_SIZE} 페이징으로
     * 나눈다. 시작 전 총 대상 건수를, 종료 후 잔여 건수를 로그로 남겨 운영이 진행 상황을 볼 수 있다
     * (잔여분은 다음 기동에서 이어서 처리 — 멱등이라 안전). 정정된 영상은 스냅샷 값이 null 이 되어
     * 판별식에서 빠지므로 재실행은 자연히 no-op 이다.
     *
     * @return 실제 정정(재동결)에 성공한 영상 건수
     */
    public int correctDerivedShootingEnvironment() {
        long total = metaRepository.countShootingEnvCorrectionTargets();
        if (total == 0) {
            log.info("[Dataset] shooting-env correction — no target (no legacy derived snapshot)");
            return 0;
        }
        log.info("[Dataset] shooting-env correction started targets={} maxPerRun={}",
                total, envCorrectionMaxPerRun);

        int corrected = 0;
        int processed = 0;
        Set<Long> attempted = new HashSet<>();
        while (processed < envCorrectionMaxPerRun) {
            List<BackfillTargetRow> targets =
                    metaRepository.findShootingEnvCorrectionTargets(ENV_CORRECTION_BATCH_SIZE);
            if (targets.isEmpty()) {
                break;
            }
            boolean progressed = false;
            for (BackfillTargetRow t : targets) {
                if (processed >= envCorrectionMaxPerRun) {
                    break;
                }
                Long rawSn = t.getRawSn();
                if (!attempted.add(rawSn)) {
                    // 이미 시도(실패로 잔존) — 재시도 안 함(무한 루프 방지).
                    continue;
                }
                progressed = true;
                processed++;
                try {
                    if (envCorrectionTx.correct(rawSn)) {
                        corrected++;
                    }
                } catch (RuntimeException e) {
                    // 개별 실패는 격리 — 나머지 영상 정정은 계속한다. 본문/PII 미출력.
                    log.warn("[Dataset] shooting-env correction failed rawSn={}", rawSn, e);
                }
            }
            if (!progressed) {
                // 배치 전체가 이미 시도된(실패 잔존) rawSn — 더 진행 불가, 중단.
                break;
            }
            log.info("[Dataset] shooting-env correction progress processed={} corrected={}",
                    processed, corrected);
        }
        log.info("[Dataset] shooting-env correction completed corrected={} remaining={}",
                corrected, metaRepository.countShootingEnvCorrectionTargets());
        return corrected;
    }
}
