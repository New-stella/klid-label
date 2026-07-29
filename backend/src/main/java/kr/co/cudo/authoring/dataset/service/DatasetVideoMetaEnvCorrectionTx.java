package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 10B DEV_FIX(M-1) — 촬영환경 <b>레거시 파생 동결값</b> 정정 1건을 원자 트랜잭션으로 수행하는 어댑터.
 *
 * <p>배경: E-ISSUE-42 로 동결 경로의 촬영환경 파생 폴백을 제거했지만, 제거 <b>이전</b>에 파생값
 * ({@code NGT}/{@code SUMMER})으로 동결된 스냅샷 행은 그대로 남는다. 그 행은 데이터마트 뷰
 * ({@code V_COMPLETED_VIDEO.DAY_NGT_CD/SESN_CD})로 관제에 노출될 뿐 아니라, export 조립
 * ({@code VideoMetaMapper} 의 {@code raw → meta} 폴백)이 라이브 수동값이 없을 때 이 동결값을 집으므로
 * <b>이후 재-export 되는 새 버전 폴더에도 다시 기록</b>된다 — 오염이 과거 산출물에 머물지 않고 계속 번진다.
 *
 * <p>설계 결정:
 * <ul>
 *   <li><b>직접 SQL UPDATE 금지</b> — 동결값을 SQL 로 고치면 {@code SNPSHT_HASH}(Java {@code SnapshotHasher})
 *       가 내용과 어긋나 "동일 페이로드 = 동일 해시" 멱등 계약이 깨진다. 반드시
 *       {@link DatasetVideoMetaSnapshotService#materialize(Long, LocalDateTime)} 재동결 경로를 타서
 *       새 해시·advisory 락·{@code deactivate-then-insert}·{@code activateByHash} 불변식을 그대로 유지한다.
 *       재동결 소스가 라이브 {@code LS_DATA_RAW}(수동값 없음 = null)이므로 결과가 곧 정정이다.</li>
 *   <li><b>검수 완료 일시 승계</b> — 2-arg 오버로드로 기존 활성 스냅샷의 {@code RVW_CMPL_DT} 를 그대로 넘겨
 *       "검수 완료 일시"가 정정 실행 시각으로 오염되는 것을 막는다({@code TASK_COMPLETED} 계약).</li>
 *   <li><b>행을 지우지 않는다</b> — 이전 오염 행은 {@code ACTIVE_YN='N'} 이력으로 남고 관제가 보던
 *       영상 행도 뷰에서 사라지지 않는다(CLAUDE.md 관제 경계 구속).</li>
 *   <li><b>재산출 → 재통지 순서</b> — 동결값이 바뀌면 관제가 픽업하는 값이 바뀌므로
 *       {@link TaskModifiedEvent}({@code exportRegenerated=true}) 를 발행한다. 이 한 축이 디바운스 flush 에서
 *       "export 새 버전 폴더 전량 재생성 → SUCCEEDED 이후 통지" 를 직렬화한다(CLAUDE.md: 통지는 export
 *       성공 후. 역전 시 관제가 구 버전 폴더를 픽업). 축적 리스너는 통지 토글과 무관하게 항상 활성이라
 *       dev/stg/prd 기본 형상에서도 재산출이 도달한다.</li>
 * </ul>
 *
 * <p>별도 빈으로 분리한 이유: 배치 루프({@link DatasetVideoMetaBackfillService})와 같은 빈에 두면
 * self-invocation 이라 {@code @Transactional} 프록시가 적용되지 않고, {@code AFTER_COMMIT} 리스너도
 * 발화하지 않는다(선례: {@code EvntAnnoReviewService#healLateFrozenEventAnnotation}).
 *
 * <p>보안: 재동결 페이로드는 비식별 메타만 담고 PII/토큰/원본 경로를 포함하지 않는다. 로그는 rawSn
 * 식별자만 남긴다(CWE-359/209). 무인 배치라 수정자({@code modifierNo})는 null 이다 — 디바운스 축적은
 * 수정자를 저장하지 않으므로 통지 페이로드에 영향이 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetVideoMetaEnvCorrectionTx {

    private final LsDatasetVideoMetaRepository metaRepository;
    private final DatasetVideoMetaSnapshotService snapshotService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 영상 1건의 동결 촬영환경을 라이브 수동값(=미입력이면 null) 기준으로 재동결하고, export 재생성을
     * 동반하는 수정 통지를 발행한다.
     *
     * @param rawSn 정정 대상 영상 PK
     * @return 실제 재동결을 수행하면 {@code true}, 활성 스냅샷 부재(이례)로 skip 했으면 {@code false}
     */
    @Transactional("controlTransactionManager")
    public boolean correct(Long rawSn) {
        // materialize 와 동일한 rawSn advisory 락 — 동시 승인/재동결과 직렬화한다.
        metaRepository.acquireRawLock(rawSn);
        List<LsDatasetVideoMeta> active =
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES);
        if (active.isEmpty()) {
            // 대상 조회 시점에는 있었으나 그 사이 사라진 이례 — fail-safe skip(선례 동일).
            log.warn("[Dataset] shooting-env correction skipped — no active snapshot rawSn={}", rawSn);
            return false;
        }
        snapshotService.materialize(rawSn, active.get(0).getRvwCmplDt());
        eventPublisher.publishEvent(new TaskModifiedEvent(
                rawSn, null, ChangeType.META_UPDATED, null, true));
        log.info("[Dataset] shooting-env correction re-froze rawSn={}", rawSn);
        return true;
    }
}
