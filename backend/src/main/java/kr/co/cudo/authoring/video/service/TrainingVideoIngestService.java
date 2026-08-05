package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관제 인입 픽업 적재 서비스.
 *
 * <p><b>관제서버가 {@code LS_DATA_INGEST} 에 직접 INSERT</b> 한 미처리({@code PRCS_STTS_CD='PENDING'})
 * 행을 주기 배치({@code ControlTrainingVideoScanJob})가 픽업해 {@code LS_DATA_RAW} 로 적재하고
 * {@code VideoIngestedEvent} 를 발행한다. 적재 이후 비식별 선두 파이프라인
 * ({@code IngestDeidentifyBridge → AsyncDeidentifyRunner})은 기존 흐름을 그대로 재사용한다.
 *
 * <p>구 구현은 관제 공유 클립 마스터·이벤트리스트 테이블(2026-08-04 제거
 * 조인)을 스캔했다. 관제 2차에서 적재 주체가 반전되어 <b>읽는 대상만</b> 인입 테이블로 교체했다 —
 * 적재 주체({@link TrainingVideoIngestTx})와 이벤트 체인은 그대로라 하류 파이프라인은 무변경이다.
 *
 * <p>트랜잭션 경계:
 * <ol>
 *   <li><b>조회는 쓰기 밖에서</b> — 후보 조회는 READ 전용({@code @Transactional(readOnly=true)}).</li>
 *   <li><b>부분 실패 격리</b> — 행별 적재는 {@link TrainingVideoIngestTx#ingestOne} 의
 *       {@code REQUIRES_NEW} 독립 트랜잭션이다. 한 행의 JPA 예외/UK 충돌로 그 트랜잭션이 롤백돼도
 *       다른 행의 커밋을 오염시키지 않는다.</li>
 *   <li><b>착수 클레임은 적재 쪽</b> — 후보 목록은 여러 노드가 동시에 받을 수 있으므로, 실제 착수는
 *       {@code ingestOne} 진입부의 원자 클레임이 단독으로 판정한다(설계 §6-0, CWE-362).</li>
 * </ol>
 *
 * <p><b>스캔 비용 억제</b> — 본 잡은 60초마다 돈다. 후보 조회는 부분 인덱스
 * ({@code IX_LS_DATA_INGEST_POLL … WHERE PRCS_STTS_CD='PENDING'})와 술어가 일치하는 미처리 행만
 * 수신일시 오름차순(FIFO)으로 {@link #INGEST_SCAN_LIMIT} 건 상한으로 가져온다. 상한 초과분은
 * <b>다음 tick 이 이어서 처리</b>한다(의도된 이월 — 로그로 관측 가능).
 *
 * <p><b>고착 행 제외(backoff)</b> — 후보 술어에는 <b>재시도 예정 시각</b> 조건이 함께 걸린다
 * ({@code NXTM_RTRY_DT IS NULL OR NXTM_RTRY_DT <= now}, 설계 §6-0-1-a ㉢). 파일 미도착으로 되돌아온
 * 행은 다음 시도가 뒤로 밀려 <b>그 사이 후보에서 빠지므로</b>, 미도착 행이 tick 상한만큼 쌓여도 뒤의
 * 정상 인입이 굶지 않는다. 기준 시각은 <b>우리 시계</b>이며 관제 수신값을 쓰지 않는다.
 */
@Slf4j
@Service
public class TrainingVideoIngestService {

    /**
     * tick 당 처리 상한.
     *
     * <p>60초 주기 잡이므로 상한을 넘는 후보는 굶지 않고 다음 tick 에 이어서 처리된다(조회 정렬이
     * 수신일시+PK 오름차순으로 고정돼 있고, 종결된 행은 다음 조회에서 폴링 술어로 빠지므로 커서가 전진한다).
     */
    public static final int INGEST_SCAN_LIMIT = 100;

    /**
     * 좀비 회수 임계값 하한(분) — 오설정이 <b>살아 있는 처리</b>를 뺏지 않도록 clamp 한다.
     *
     * <p>정상 적재 1건은 ms~초 단위라 10분이면 이미 3자릿수 여유다. 0·음수 설정이 그대로 먹히면
     * 매 tick 이 방금 클레임한 행을 되돌려 무한 재적재 루프가 된다.
     */
    private static final long MIN_PROCESSING_STALE_MINUTES = 10L;

    private final LsDataIngestRepository ingestRepository;
    private final TrainingVideoIngestTx ingestTx;

    /**
     * {@code PROCESSING} 좀비로 판정하는 경과 임계값(기본 2시간, 설계 §6-0-1 ① — 보류에는 끝이 있다).
     *
     * <p>보수적 기본값이다 — 실제 적재는 ms~초라 이 값과 3~4 자릿수 차이가 나므로 살아 있는 처리를
     * 회수할 여지가 사실상 없다.
     */
    private final Duration processingStaleTimeout;

    public TrainingVideoIngestService(
            LsDataIngestRepository ingestRepository,
            TrainingVideoIngestTx ingestTx,
            @Value("${authoring.control.training-scan.processing-stale-timeout-minutes:120}")
            long processingStaleTimeoutMinutes) {
        this.ingestRepository = ingestRepository;
        this.ingestTx = ingestTx;
        long minutes = processingStaleTimeoutMinutes;
        if (minutes < MIN_PROCESSING_STALE_MINUTES) {
            log.warn("[TrainingIngest] processing-stale-timeout-minutes={} 는 하한 미만 — {}분으로 보정한다",
                    minutes, MIN_PROCESSING_STALE_MINUTES);
            minutes = MIN_PROCESSING_STALE_MINUTES;
        }
        this.processingStaleTimeout = Duration.ofMinutes(minutes);
    }

    /**
     * <b>좀비 회수</b> — 오래 {@code PROCESSING} 에 머문 행을 {@code PENDING} 으로 되돌린다
     * (DEV_FIX 2차 [B]).
     *
     * <p>클레임한 노드가 종결을 찍기 전에 죽으면(2노드 롤링 재기동·OOM) 그 행은 어떤 통로로도 다시
     * 처리되지 않는다 — 폴링은 {@code PENDING} 만 보고, 재큐는 {@code FAILED} 전용이며, 행 삭제는
     * 금지다. <b>끝이 없는 보류</b>를 막는 유일한 통로이므로 스캔 <b>앞</b>에서 매 tick 돈다(회수된 행이
     * 같은 tick 에 바로 처리된다).
     *
     * <p>스캔과 <b>별도 트랜잭션</b>이다 — 스캔은 {@code readOnly} 이고, 회수 실패가 그 tick 의 정상
     * 적재를 막아서는 안 된다(호출부가 예외를 흡수한다).
     *
     * @return 회수된 행 수
     */
    @Transactional("controlTransactionManager")
    public int reclaimStaleProcessing() {
        LocalDateTime cutoff = LocalDateTime.now().minus(processingStaleTimeout);
        int reclaimed = ingestRepository.reclaimStaleProcessing(cutoff, INGEST_SCAN_LIMIT);
        if (reclaimed > 0) {
            // 정상 형상에서는 0 이 이어진다 — 값이 잡히면 노드 이상 종료 신호이므로 WARN 으로 드러낸다.
            log.warn("[TrainingIngest] reclaimed stale PROCESSING rows count={} staleAfterMin={}",
                    reclaimed, processingStaleTimeout.toMinutes());
        }
        return reclaimed;
    }

    /**
     * 미처리 인입 행을 스캔해 {@code LS_DATA_RAW} 로 적재한다.
     *
     * @return 이번 스캔에서 신규 적재된 건수
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public int scanAndIngest() {
        List<LsDataIngest> pending = ingestRepository.findPendingReadyForPolling(
                LocalDateTime.now(), PageRequest.of(0, INGEST_SCAN_LIMIT));
        if (pending == null || pending.isEmpty()) {
            log.debug("[TrainingIngest] no pending ingest rows to scan");
            return 0;
        }
        int ingested = 0;
        for (LsDataIngest row : pending) {
            try {
                if (ingestTx.ingestOne(row)) {
                    ingested++;
                }
            } catch (UnexpectedRollbackException e) {
                // ★ 정상 race 를 ERROR 로 올리지 않는다.
                //   ingestOne 은 UK(VMS_CLIP_ID) 충돌(= 동시 중복 적재 race)을 잡아 false 로 흡수하지만,
                //   PostgreSQL 은 제약 위반 시 <트랜잭션 전체를 abort> 하므로 REQUIRES_NEW 커밋 시점에
                //   이 예외가 던져진다. 즉 이 경로는 "설계대로 동작한 결과"다 — 함께 롤백된 클레임 덕에
                //   행은 PENDING 으로 돌아가고, 다음 tick 이 1차 멱등(findByVmsClipId)으로 DONE 종결한다.
                //   ERROR 로 남기면 실제 장애 알림과 섞여 관측 가치를 떨어뜨린다(목 기반 단위 테스트는
                //   커밋이 없어 이 동작을 재현하지 못하므로 실 DB IT 가 이 계약을 고정한다).
                log.info("[TrainingIngest] ingest tx rolled back (duplicate clip race) — retried next tick"
                        + " rcptnSn={}", row.getRcptnSn());
            } catch (RuntimeException e) {
                // 한 행의 REQUIRES_NEW 트랜잭션 롤백/예외가 다른 행을 막지 않게 흡수한다.
                // 식별자는 인입 PK(수치)만 남긴다 — 관제 자유텍스트/파일경로는 로그에 넣지 않는다
                // (CWE-117 log injection · CWE-359 경로 노출).
                log.error("[TrainingIngest] ingest failed rcptnSn={} causeType={}",
                        row.getRcptnSn(), e.getClass().getSimpleName());
            }
        }
        // 상한 도달은 "후보가 더 있을 수 있음" 을 뜻한다 — 잔여분 이월이 의도된 동작임을 관측 가능하게 남긴다.
        boolean limitReached = pending.size() >= INGEST_SCAN_LIMIT;
        log.info("[TrainingIngest] scan finished scanned={} ingested={} limit={} carriedOver={}",
                pending.size(), ingested, INGEST_SCAN_LIMIT, limitReached);
        return ingested;
    }
}
