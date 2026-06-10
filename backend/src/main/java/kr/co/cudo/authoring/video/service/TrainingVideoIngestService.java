package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.entity.MngClipMaster;
import kr.co.cudo.authoring.video.repository.MngClipMasterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 관제 학습용 영상 픽업 적재 서비스.
 *
 * <p>관제서버가 {@code MNG_CLIP_MASTER.JOB_DMND_YN='Y'} 로 학습용 지정한 클립을 주기 배치
 * ({@code ControlTrainingVideoScanJob}) 가 픽업해 {@code LS_DATA_RAW} 로 적재(PENDING)하고
 * {@code VideoIngestedEvent} 를 발행한다. 적재 이후 비식별 선두 파이프라인
 * ({@code IngestDeidentifyBridge → AsyncDeidentifyRunner}) 는 기존 흐름을 그대로 재사용한다.
 *
 * <p>트랜잭션 경계 (code-reviewer DEV_FIX):
 * <ol>
 *   <li><b>조회는 쓰기 밖에서</b> — {@code JOB_DMND_YN='Y'} 클립 조회는 READ 전용
 *       ({@code @Transactional(readOnly=true)})이며, 관제 DB 읽기 커넥션을 쓰기 트랜잭션 전체 시간
 *       동안 점유하지 않는다 (MEDIUM-2).</li>
 *   <li><b>부분 실패 격리</b> — 클립별 적재는 {@link TrainingVideoIngestTx#ingestOne} 의
 *       {@code REQUIRES_NEW} 독립 트랜잭션으로 수행한다. 한 클립의 JPA 예외/UK 충돌로 그 트랜잭션이
 *       롤백돼도 다른 클립의 커밋을 오염시키지 않는다 (CRITICAL-1).</li>
 *   <li><b>이벤트 AFTER_COMMIT</b> — {@code VideoIngestedEvent} 발행은 각 클립의 REQUIRES_NEW
 *       경계 안에서 수행돼 커밋 후 비식별이 트리거된다 (단, 파일경로 미해결 시 보류 — HIGH-3).</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingVideoIngestService {

    /** 학습용 지정(작업수요) 플래그 값. */
    private static final String JOB_DEMAND_YES = "Y";

    private final MngClipMasterRepository clipMasterRepository;
    private final TrainingVideoIngestTx ingestTx;

    /**
     * 학습용 지정 클립을 스캔해 미적재 분을 LS_DATA_RAW 로 적재한다.
     *
     * <p>조회만 READ 트랜잭션으로 묶고, 적재(쓰기)는 클립별 {@code REQUIRES_NEW}({@link TrainingVideoIngestTx})
     * 로 분리해 부분 실패를 격리한다.
     *
     * @return 이번 스캔에서 신규 적재된 건수
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public int scanAndIngest() {
        List<MngClipMaster> clips = clipMasterRepository.findIngestCandidatesByJobDmndYn(JOB_DEMAND_YES);
        if (clips == null || clips.isEmpty()) {
            log.debug("[TrainingIngest] no training-designated clips to scan");
            return 0;
        }
        int ingested = 0;
        for (MngClipMaster clip : clips) {
            try {
                if (ingestTx.ingestOne(clip)) {
                    ingested++;
                }
            } catch (RuntimeException e) {
                // CRITICAL-1: 한 클립의 REQUIRES_NEW 트랜잭션 롤백/예외가 다른 클립을 막지 않게 흡수.
                // 식별자(evntId/clipId)만 기록(파일경로/PII 미출력) 후 다음 클립 계속 진행.
                log.error("[TrainingIngest] ingest failed evntId={} clipId={} causeType={}",
                        clip.getEvntId(), clip.getClipId(), e.getClass().getSimpleName());
            }
        }
        log.info("[TrainingIngest] scan finished scanned={} ingested={}", clips.size(), ingested);
        return ingested;
    }
}
