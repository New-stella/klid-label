package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.entity.MngClipEvntLst;
import kr.co.cudo.authoring.video.entity.MngClipMaster;
import kr.co.cudo.authoring.video.repository.MngClipEvntLstRepository;
import kr.co.cudo.authoring.video.repository.MngClipMasterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *
 * <p><b>스캔 비용 억제 (B-ISSUE-04)</b> — 본 잡은 60초마다 돌며 <b>관제 공유 DB(MNG_*)</b> 를 친다.
 * <ol>
 *   <li>후보 조회는 미적재({@code NOT EXISTS LS_DATA_RAW}) 클립만, {@link #INGEST_SCAN_LIMIT} 건 상한으로
 *       좁힌다. 상한 초과분은 <b>다음 tick 이 이어서 처리</b>한다(의도된 이월 — 로그로 관측 가능).</li>
 *   <li>이벤트리스트(EVNT_TYPE_CD/SHT_DT) 는 클립당 개별 조회 대신 후보 EVNT_ID 집합 <b>IN 조회 1회</b>로
 *       배치화한다. 구 구현은 후보 N 건이면 매 tick 2N 쿼리를 공유 DB 에 발생시켰다.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingVideoIngestService {

    /** 학습용 지정(작업수요) 플래그 값. */
    private static final String JOB_DEMAND_YES = "Y";

    /**
     * tick 당 처리 상한 (B-ISSUE-04).
     *
     * <p>60초 주기 잡이므로 상한을 넘는 후보는 굶지 않고 다음 tick 에 이어서 처리된다(조회 정렬이 PK
     * 오름차순으로 고정돼 있고, 적재된 클립은 다음 조회에서 {@code NOT EXISTS} 로 빠지므로 커서가 전진한다).
     */
    public static final int INGEST_SCAN_LIMIT = 100;

    private final MngClipMasterRepository clipMasterRepository;
    private final MngClipEvntLstRepository clipEvntLstRepository;
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
        List<MngClipMaster> clips = clipMasterRepository.findIngestCandidatesByJobDmndYn(
                JOB_DEMAND_YES, PageRequest.of(0, INGEST_SCAN_LIMIT));
        if (clips == null || clips.isEmpty()) {
            log.debug("[TrainingIngest] no training-designated clips to scan");
            return 0;
        }
        // N+1 제거: 후보 EVNT_ID 를 모아 이벤트리스트를 IN 조회 1회로 가져온다.
        Map<String, MngClipEvntLst> evntLstByEvntId = loadEventListsFor(clips);
        int ingested = 0;
        for (MngClipMaster clip : clips) {
            try {
                if (ingestTx.ingestOne(clip, evntLstByEvntId.get(clip.getEvntId()))) {
                    ingested++;
                }
            } catch (RuntimeException e) {
                // CRITICAL-1: 한 클립의 REQUIRES_NEW 트랜잭션 롤백/예외가 다른 클립을 막지 않게 흡수.
                // 식별자(evntId/clipId)만 기록(파일경로/PII 미출력) 후 다음 클립 계속 진행.
                log.error("[TrainingIngest] ingest failed evntId={} clipId={} causeType={}",
                        clip.getEvntId(), clip.getClipId(), e.getClass().getSimpleName());
            }
        }
        // 상한 도달은 "후보가 더 있을 수 있음" 을 뜻한다 — 잔여분 이월이 의도된 동작임을 관측 가능하게 남긴다.
        boolean limitReached = clips.size() >= INGEST_SCAN_LIMIT;
        log.info("[TrainingIngest] scan finished scanned={} ingested={} limit={} carriedOver={}",
                clips.size(), ingested, INGEST_SCAN_LIMIT, limitReached);
        return ingested;
    }

    /**
     * 후보 클립의 EVNT_ID 집합으로 이벤트리스트를 <b>1회</b> 조회해 EVNT_ID 기준 맵으로 축약한다.
     *
     * <p>복합 PK (EVNT_ID, EVNT_TYPE_CD) 상 EVNT_ID 당 다행이 가능하므로, PK 오름차순 정렬 결과의
     * <b>첫 행</b>만 채택해 구 {@code findFirstByEvntId} 와 동일 의미를 유지한다.
     */
    private Map<String, MngClipEvntLst> loadEventListsFor(List<MngClipMaster> clips) {
        Set<String> evntIds = new LinkedHashSet<>();
        for (MngClipMaster clip : clips) {
            if (StringUtils.hasText(clip.getEvntId())) {
                evntIds.add(clip.getEvntId());
            }
        }
        if (evntIds.isEmpty()) {
            return Map.of();
        }
        List<MngClipEvntLst> rows =
                clipEvntLstRepository.findByEvntIdInOrderByEvntIdAscEvntTypeCdAsc(evntIds);
        Map<String, MngClipEvntLst> byEvntId = new LinkedHashMap<>();
        for (MngClipEvntLst row : rows) {
            byEvntId.putIfAbsent(row.getEvntId(), row);
        }
        return byEvntId;
    }
}
