package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.dto.MarkingImportIngestCommand;
import kr.co.cudo.authoring.video.dto.MarkingImportIngestResult;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
 *
 * <h3>★ 이 클래스에는 성격이 다른 자리가 하나 더 있다 — 마킹 이관 적재</h3>
 * <p>{@link #ingestMarkingImport} 는 위 폴링과 <b>아무것도 공유하지 않는</b> 별개 경로다(ADR-053).
 * 인입 원장을 읽지도 쓰지도 않고 클레임·좀비 회수·미도착 backoff 도 타지 않으며, 사람이 화면에서
 * 지정한 값으로 영상 한 건을 그 자리에서 만든다. 자리를 여기 둔 것은 SEQ-030 이 이관 쪽 호출 대상을
 * 이 서비스로 못 박았기 때문이고, 실제 쓰기는 {@link MarkingImportIngestTx} 가 갖는다.
 * <p><b>두 경로를 한 흐름으로 읽지 말 것</b> — 위 트랜잭션 규약·상태 전이 표는 폴링 경로에만 적용된다.
 *
 * @design ADR-053
 * @design SEQ-030
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

    /** 마킹 이관 적재 1건의 트랜잭션 경계 — 관제 인입 상태머신과 공유하지 않는다. */
    private final MarkingImportIngestTx markingImportIngestTx;

    /** 마킹 이관 적재의 <b>중복 식별자 사전 조회</b> 전용 — 쓰기 트랜잭션 밖에서 본다. */
    private final VideoRepository videoRepository;

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
            MarkingImportIngestTx markingImportIngestTx,
            VideoRepository videoRepository,
            @Value("${authoring.control.training-scan.processing-stale-timeout-minutes:120}")
            long processingStaleTimeoutMinutes) {
        this.ingestRepository = ingestRepository;
        this.ingestTx = ingestTx;
        this.markingImportIngestTx = markingImportIngestTx;
        this.videoRepository = videoRepository;
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

    /**
     * <b>외부 마킹 산출물 일괄 가져오기</b>가 영상 한 건을 적재할 때 부르는 자리(ADR-053 · SEQ-030).
     *
     * <h3>위 폴링 경로와 <b>아무것도 공유하지 않는다</b></h3>
     * <p>{@code LS_DATA_INGEST} 를 읽지도 쓰지도 않는다 — 그 원장은 "관제가 무엇을 보냈는가"의 기록이라
     * 저작도구가 자기 판단으로 행을 넣으면 관제가 보낸 것과 우리가 넣은 것을 나중에 구분할 수 없다
     * (ADR-048 이 라벨링 완료 갈래에 정한 규칙과 같다). 클레임·좀비 회수·미도착 backoff 같은 폴링
     * 상태머신 규약도 이 경로에는 적용되지 않는다. 자리를 이 클래스에 둔 것은 SEQ-030 이 이관 쪽
     * 호출 대상을 이 서비스로 못 박았기 때문이고, 실제 쓰기는 별도 트랜잭션 빈
     * ({@link MarkingImportIngestTx})이 갖는다.
     *
     * <h3>중복 식별자는 <b>그 항목만 건너뛴다</b> — 덮어쓰지 않는다 (AC-1033)</h3>
     * <p>이중 방어다. 한쪽만으로는 부족하다.
     * <ol>
     *   <li><b>사전 조회</b> — 이미 그 식별자를 쓰는 영상이 있으면 쓰기 트랜잭션을 열지도 않는다.
     *       일괄 백 건에서 대부분의 중복이 여기서 걸린다.</li>
     *   <li><b>제약 위반 흡수</b> — 사전 조회와 INSERT 사이에는 창이 있고 2노드 Active-Active 라
     *       그 창으로 동시 적재가 들어온다(check-then-act, CWE-362). {@code UK_LS_DATA_RAW_VMS_CLIP}
     *       위반이 실제 방어선이다.</li>
     * </ol>
     * <p>제약 위반을 중복으로 <b>단정하지 않고 다시 확인</b>한다 — 다른 제약이 깨진 것을 "이미 있음"으로
     * 삼키면 적재되지 않은 항목이 성공처럼 집계된다. 재조회로 실제 그 식별자의 영상이 확인될 때만
     * 중복으로 마감하고, 아니면 예외를 그대로 올린다.
     *
     * <p>이 메서드에는 트랜잭션이 없다 — 재조회가 <b>롤백된 뒤의 DB 상태</b>를 봐야 하기 때문이다.
     *
     * @return 적재됨 / 중복이라 건너뜀. 그 밖의 실패는 예외로 올라간다
     * @throws IllegalArgumentException 커맨드가 적재에 필요한 값을 갖추지 못했을 때
     * @design ADR-053
     * @design DFEAT-060
     * @design SEQ-030
     * @design AC-1032
     * @design AC-1033
     */
    public MarkingImportIngestResult ingestMarkingImport(MarkingImportIngestCommand command) {
        MarkingImportIngestCommand normalized = MarkingImportIngestValidator.validate(command);
        Optional<LsDataRaw> existing = videoRepository.findByVmsClipId(normalized.vmsClipId());
        if (existing.isPresent()) {
            // 이미 들어와 있다 — 기존 내용을 건드리지 않고 그 항목만 건너뛴다.
            log.info("[MarkingImport] clip already ingested — skip rawSn={}", existing.get().getRawSn());
            return MarkingImportIngestResult.duplicate(existing.get().getRawSn());
        }
        try {
            return MarkingImportIngestResult.ingested(markingImportIngestTx.persist(normalized));
        } catch (DataIntegrityViolationException e) {
            LsDataRaw raced = videoRepository.findByVmsClipId(normalized.vmsClipId()).orElse(null);
            if (raced == null) {
                // 식별자 충돌이 아닌 다른 제약 위반 — 삼키면 적재 안 된 항목이 성공으로 집계된다.
                throw e;
            }
            log.info("[MarkingImport] duplicate clip race — skip rawSn={}", raced.getRawSn());
            return MarkingImportIngestResult.duplicate(raced.getRawSn());
        }
    }
}
