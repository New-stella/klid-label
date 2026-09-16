package kr.co.cudo.authoring.video.repository;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@ControlRepo
public interface VideoRepository extends JpaRepository<LsDataRaw, Long> {

    Optional<LsDataRaw> findByVmsClipId(String vmsClipId);

    /**
     * 부모 RAW 행을 {@link LockModeType#PESSIMISTIC_WRITE}(SELECT … FOR UPDATE)로 잠금 조회한다
     * (HIGH #1 — 증강본 비식별 게이트 TOCTOU 차단).
     *
     * <p>증강 콜백은 부모의 {@code DE_IDNTF_YN='Y'} 를 확인한 뒤 증강본을 {@code COMPLETED}(라벨 복사 완료·
     * 작업목록 노출)로 확정한다. 이 확인~커밋 사이에 동시 비식별 신고({@code DeidentReportService.report})가 부모를
     * {@code 'F'} 로 전이시키면, 신고로 노출본으로 되돌아간 부모에서 파생된 PII 증강본이 스트리밍되는 사고가
     * 났다(CWE-359). 신고 경로는 부모 RAW 행을 {@code markDeidentified('F')} 로 UPDATE 하므로, 본 행 잠금이
     * 신고의 UPDATE 와 같은 row 에서 경합한다. 증강 tx 가 먼저 잠그면 {@code 'Y'} 를 고정한 채 커밋할 때까지
     * 신고 UPDATE 가 직렬화되고, 신고가 먼저 {@code 'F'} 를 커밋하면 증강은 잠금 획득 후 {@code 'F'} 를 읽고
     * 생성을 보류한다. 잠금은 caller {@code @Transactional} 종료까지 유지된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM LsDataRaw r WHERE r.rawSn = :rawSn")
    Optional<LsDataRaw> findByRawSnForUpdate(@Param("rawSn") Long rawSn);

    /**
     * S7 — 비식별 처리 코드({@code DE_IDNTF_YN}) 단일 컬럼 projection.
     *
     * <p>신고 구간 판정({@code DeidentReportGate.isUnderDeidentReport})이 매 조회마다 호출하므로 전체 row
     * fetch 를 피한다(PK 인덱스 lookup + 1컬럼). 값이 NULL 인 행은 빈 Optional 로 온다(=통과).
     *
     * <p><b>판정은 자기 행 하나로 끝난다</b> — 파생영상은 원본의 신고와 무관하게 다루는 것이 확정 정책
     * (2026-07-29)이라 {@code ORGNL_RAW_SN} 을 함께 읽어 조상으로 올라가지 않는다(게이트 javadoc 참조).
     */
    @Query("SELECT r.deIdntfYn FROM LsDataRaw r WHERE r.rawSn = :rawSn")
    Optional<String> findDeIdntfYnByRawSn(@Param("rawSn") Long rawSn);

    /**
     * 배치 단계 상태({@code DATA_STTS_CD}) 단일 컬럼 projection (B-ISSUE-101).
     *
     * <p>수동 재처리 클레임({@code BatchTransitionService.tryClaimReprocessFromFailed})이 RAW 클레임
     * 0행의 <b>원인을 구분</b>하는 데 쓴다 — "남이 방금 선점(PROCESSING)" 과 "애초에 FAILED 가 아님"은
     * 다른 사건인데, 이를 구분하지 않고 작업상태 컬럼으로 폴백하면 상호배제가 깨진다(상세는 그 메서드).
     * 전체 row fetch 없이 PK 인덱스 lookup + 1컬럼만 읽는다.
     */
    @Query("SELECT r.dataSttsCd FROM LsDataRaw r WHERE r.rawSn = :rawSn")
    Optional<String> findDataSttsCdByRawSn(@Param("rawSn") Long rawSn);

    /**
     * 제외여부({@code EXCL_YN}) 단일 컬럼 projection. [@design ADR-069]
     *
     * <p>아래 두 원자 갱신이 <b>0행</b>을 돌려줬을 때 그 원인을 가르는 데 쓴다 — 「이미 그 상태였다」
     * (멱등 성공)와 「배정이 있어 막혔다」(409)와 「행이 없다」(404)는 서로 다른 사건이다.
     * <b>빈 {@code Optional} 이 곧 행 부재</b>다(컬럼이 {@code NOT NULL} 이라 값이 비지 않는다).
     *
     * <p>전체 row 를 로드하지 않는다 — 영속 컨텍스트에 엔티티를 들이면 벌크 갱신 결과와 어긋난 스냅샷을
     * 쥐게 되고, 그 인스턴스가 flush 되면 함께 로드된 다른 컬럼을 stale 값으로 덮어쓴다(CWE-362).
     */
    @Query("SELECT r.exclYn FROM LsDataRaw r WHERE r.rawSn = :rawSn")
    Optional<String> findExclYnByRawSn(@Param("rawSn") Long rawSn);

    /**
     * <b>영상 제외 원자 갱신</b> — 배정 부재 조건을 <b>갱신 문장 자체에</b> 함께 건다.
     * [@design ADR-069] [@design API-260] [@design AC-1127]
     *
     * <p>★사전 조회로 배정을 확인한 뒤 갱신하면 <b>그 사이에 배정이 새로 생긴다</b>(CWE-367 TOCTOU).
     * 단일 UPDATE 에 조건을 실으면 DB 가 그 창을 닫는다. 프레임 폐기({@code FrameDiscardApplier})가
     * 조건부 UPDATE 의 <b>반환 행수</b>로 "실제로 바뀌었는가"를 판정하는 것과 같은 관례이며, 여기서는
     * 거기에 배정 조건 한 겹이 더 붙는다.
     *
     * <p>{@code r.exclYn <> :excluded} 가 <b>멱등</b>을 만든다 — 이미 제외된 영상은 0행이 되고 호출부가
     * {@link #findExclYnByRawSn} 로 그것이 멱등인지 배정 충돌인지 가른다.
     *
     * <p>배정 판정 축은 목록의 {@link #ASSIGNED_ONLY_PREDICATE}·단건 가드
     * ({@code LabelAccessGuard.verifyRawAccess})와 <b>같은</b> {@link #LABELER_TASK_TYPE_CD} 다 —
     * 축이 갈리면 목록에는 배정이 보이는데 제외는 통과하는 비대칭이 생긴다.
     *
     * <p>★<b>{@code MDFCN_DT} 를 건드리지 않는다.</b> 그 컬럼은 고착 회수 스윕
     * ({@code ProcessingStaleReclaimSweeper})의 1차 필터라, 제외가 그 값을 밀면 고착된 영상이 <b>방금
     * 선점된 것처럼 보여 회수에서 빠진다</b>. 제외는 화면 시야만 바꾸고 배치를 조금도 건드리지 않는다는
     * 경계(AC-1121)가 이 한 줄에 걸려 있다.
     *
     * @return 영향 행수 (1=제외됨, 0=이미 제외 / 배정 존재 / 행 부재)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsDataRaw r SET r.exclYn = :excluded "
            + "WHERE r.rawSn = :rawSn AND r.exclYn <> :excluded "
            + "AND NOT EXISTS (SELECT 1 FROM LsTaskAssignment a "
            + "                 WHERE a.rawDataId = r.rawSn AND a.taskTypeCd = :labelerTaskTypeCd)")
    int markExcluded(@Param("rawSn") Long rawSn,
                     @Param("excluded") String excluded,
                     @Param("labelerTaskTypeCd") String labelerTaskTypeCd);

    /**
     * <b>영상 복원 원자 갱신</b> — 제외 표시를 되돌린다. [@design ADR-069] [@design API-261]
     *
     * <p><b>배정 조건이 없는 것은 누락이 아니라 의도</b>다 — 배정이 있는 영상은 제외 자체가 거부되므로
     * 제외된 영상에는 배정이 존재할 수 없고, 그 조건이 성립할 자리가 없다. 「일관성」을 이유로 여기에
     * 배정 조건을 붙이지 말 것.
     *
     * <p>{@code MDFCN_DT} 를 건드리지 않는 이유는 {@link #markExcluded} 와 같다.
     *
     * @return 영향 행수 (1=복원됨, 0=이미 보이는 영상 / 행 부재)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsDataRaw r SET r.exclYn = :visible "
            + "WHERE r.rawSn = :rawSn AND r.exclYn <> :visible")
    int markRestored(@Param("rawSn") Long rawSn, @Param("visible") String visible);

    /**
     * <b>배치 파이프라인 진입 원자 클레임</b> (B-ISSUE-01 / 1차 B-ISSUE-22, CWE-362, check-and-set).
     *
     * <p>배치 단계 상태(DATA_STTS_CD)가 아직 {@code processingStatus}(PROCESSING)가 <b>아닐 때만</b>
     * PROCESSING 으로 전이한다. 단일 SQL UPDATE 라 DB 가 동시 실행을 직렬화하므로, 동일 rawSn 에
     * 진입 요청이 몇 건 겹치든 <b>정확히 1건만</b> 영향 행수 1 을 받는다. 나머지는 0 을 받고
     * {@code BatchOrchestrator} 가 {@code SKIPPED} 로 즉시 종료한다.
     *
     * <p><b>왜 이 컬럼인가</b>: 진입점(마킹 브리지·Quartz 큐·재시도 잡·dev 트리거·수동 재처리)마다
     * 클레임 자원이 달라 상호배제가 성립하지 않던 것이 결함의 뿌리였다. {@code LS_DATA_RAW} 행은
     * 파생 RAW 를 포함해 <b>항상 존재</b>하는 유일한 축이라(작업 상태 {@code LS_RAW_DATA_STATUS} 행은
     * 배정 시점 lazy 생성이라 없을 수 있다) 전 진입점 공통의 상호배제 토큰이 된다.
     *
     * <p><b>왜 PROCESSING 만 제외하는가</b>: 구 구현은 현재 값과 무관하게 PROCESSING 으로 덮었다.
     * 제외 집합을 PROCESSING 하나로 두면 "동시 실행 금지"만 새로 강제하고 나머지 출발 상태
     * (MARKING_READY/PENDING/FAILED/COMPLETED)의 기존 동작은 그대로 보존된다. COMPLETED 재진입 차단은
     * 별개 관심사라 마킹 브리지({@code SKIP_BATCH_STAGES})가 계속 담당한다.
     *
     * <p>해제(=재진입 허용)는 배치 종료 전이가 담당한다 — 완료 시 COMPLETED, 실패 시 FAILED.
     *
     * <p><b>NULL 3값 논리 주의</b>: {@code dataSttsCd} 가 NULL 이면 {@code <>} 비교가 UNKNOWN 이라 이
     * UPDATE 는 <b>0행</b>이 된다(클레임 실패). 다만 이 컬럼은 DDL 이 {@code NOT NULL DEFAULT 'PENDING'}
     * (V1/V2/V36)이고 엔티티도 {@code @Column(nullable = false)} + 모든 생성 팩토리가 {@code PENDING} 을
     * 대입하므로 NULL 은 구조적으로 발생하지 않는다. 따라서 {@code COALESCE} 같은 방어를 두지 않는다 —
     * 넣으면 "NULL 도 정상 출발 상태"라는 잘못된 계약을 새로 만든다. (0행 이후 원인 구분은
     * {@code BatchTransitionService.markProcessing} 이 {@link #findDataSttsCdByRawSn} 로 수행한다.)
     *
     * @return 영향 행수 (1=클레임 성공, 0=이미 다른 주체가 처리 중/row 부재)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsDataRaw r SET r.dataSttsCd = :processingStatus, r.mdfcnDt = CURRENT_TIMESTAMP "
            + "WHERE r.rawSn = :rawSn AND r.dataSttsCd <> :processingStatus")
    int claimForProcessing(@Param("rawSn") Long rawSn,
                           @Param("processingStatus") String processingStatus);

    /**
     * <b>고착 후보</b> 조회 — 배치 단계가 {@code PROCESSING} 인 채 오래 갱신되지 않은 영상.
     *
     * <p>회수 스윕({@code ProcessingStaleReclaimSweeper})의 1차 필터다. {@code MDFCN_DT} 는
     * {@link #claimForProcessing}/{@link #claimReprocessFromFailed} 가 선점 시 {@code CURRENT_TIMESTAMP}
     * 로 갱신하므로, 방금 선점된 영상은 여기서 걸러진다.
     *
     * <p><b>이 조건만으로 회수하면 안 된다</b> — 여기 걸린 영상 중에는 ①정상 실행 중(단계가 길어
     * {@code LS_DATA_RAW} 를 건드리지 않은 경우) ②선점 직전 상태를 기록하지 않은 경로로 진입한 경우가
     * 섞여 있다. 최종 판정은 선점 표식 + 진행 로그 갱신 시각으로 한다(스윕 서비스 참조).
     *
     * <p>{@code MDFCN_DT IS NULL} 도 후보에 넣는다 — 값이 없으면 "오래됐다" 와 구분되지 않으므로
     * 배제하면 회수 사각이 생긴다(최종 판정이 다시 거른다).
     *
     * <p>정렬은 PK 오름차순 <b>고정</b>이다 — DB 반환 순서를 그대로 쓰면 tick 마다 후보 집합이 흔들려
     * 상한({@code Pageable})에 걸린 뒷줄이 영영 처리되지 않을 수 있다. 파라미터 바인딩만 사용한다(CWE-89).
     *
     * <h3>★{@code afterRawSn} — 앞줄이 회수 큐를 영구 점유하지 못하게 하는 회전 커서</h3>
     * <p>판정이 <b>보류</b>된 후보(선점 표식이 없는 영상 등)는 상태가 그대로라 <b>다음 tick 에도 같은
     * 자리에 다시 뽑힌다</b>. 표식 없는 {@code PROCESSING} 은 선점 직전 상태를 기록하지 않는 다른 진입
     * 경로(마킹 브리지·배치 잡·자동 재시도 잡·dev 트리거)가 노드 사망 중에 남기며 <b>회수해 줄 다른
     * 주체가 없다</b>. 그런 영상이 상한만큼 쌓이면 그 뒤의 <b>진짜 회수 대상이 영영 판정되지 않아</b>
     * 스윕이 조용히 무력화된다. 스윕은 마지막으로 살펴본 지점을 기억했다가 그 뒤부터 이어서 훑고, 끝에
     * 닿으면 처음으로 돌아온다(상한은 그대로 유지 — 자원 보호).
     *
     * <h3>★ 포털 자산은 후보가 아니다 — 방어를 우연에서 구조로 옮긴다 (ADR-058)</h3>
     * <p>흡수로 포털 업로드 자산이 이 원장에 함께 앉는다. 지금까지 이 조회가 포털 행을 집지 않은 것은
     * <b>배치 단계 값이 우연히 겹치지 않았기 때문</b>이지 막아서가 아니었다 — 포털 파이프라인이 이
     * 컬럼을 건드리는 순간 섞인다. 채널 축을 술어에 박아 그 우연을 구조로 바꾼다.
     * <p>술어는 여기서 쓰지 않고 채널 판별의 단일 소유자 {@link InternalWorkScope#INTERNAL_JPQL} 을
     * 붙인다(별칭 규약 {@code LsDataRaw = r}). 「포털이 아니다」로 적는 이유는 그 상수 주석에 있다.
     *
     * @param cutoff     이 시각 이전에 마지막 갱신된 행만 후보
     * @param afterRawSn 이 값보다 큰 {@code RAW_SN} 만 후보(회전 커서). 처음부터 훑으려면 {@code 0}
     */
    @Query("SELECT r.rawSn FROM LsDataRaw r "
            + "WHERE r.dataSttsCd = :processingStatus "
            + InternalWorkScope.INTERNAL_JPQL
            + "  AND (r.mdfcnDt IS NULL OR r.mdfcnDt < :cutoff) "
            + "  AND r.rawSn > :afterRawSn "
            + "ORDER BY r.rawSn ASC")
    List<Long> findStaleProcessingRawSns(@Param("processingStatus") String processingStatus,
                                         @Param("cutoff") java.time.LocalDateTime cutoff,
                                         @Param("afterRawSn") long afterRawSn,
                                         Pageable pageable);

    /**
     * 수동 배치 재처리 클레임용 조건부 원자 전이 (CWE-362, check-and-set).
     *
     * <p>배치 단계 상태(DATA_STTS_CD)가 {@code fromStatus}(FAILED)일 때만 {@code toStatus}(PROCESSING)로
     * 전이한다. 단일 SQL UPDATE 라 DB 가 동시 호출을 직렬화하므로, 수동 재기동(REVIEWER)과 자동 재시도
     * 폴러가 동일 rawSn 에 동시에 접근해도 정확히 1건만 영향 행수 1 을 받아 파이프라인이 이중 실행되지 않는다.
     * LS_DATA_RAW 는 {@code @Version} 이 없어 낙관적 잠금 충돌이 없다.
     *
     * @return 영향 행수 (1=클레임 성공, 0=FAILED 아님/이미 클레임됨)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsDataRaw r SET r.dataSttsCd = :toStatus, r.mdfcnDt = CURRENT_TIMESTAMP "
            + "WHERE r.rawSn = :rawSn AND r.dataSttsCd = :fromStatus")
    int claimReprocessFromFailed(@Param("rawSn") Long rawSn,
                                 @Param("fromStatus") String fromStatus,
                                 @Param("toStatus") String toStatus);

    /**
     * 수동 배치 재처리 클레임 <b>보상 롤백</b> (DEV_FIX H10) — PROCESSING → FAILED 조건부 원자 전이.
     *
     * <p>{@link #claimReprocessFromFailed} 로 FAILED→PROCESSING 을 선점했으나 이어지는
     * {@code BatchOrchestrator.process()} 가 검수 소유 작업 상태를 만나 {@code SKIPPED} 로 즉시 반환하면,
     * 파이프라인은 한 건도 실행되지 않고 {@code markRawDataFailed}/{@code markRawDataCompleted} 도 타지
     * 않아 <b>배치 단계 상태가 PROCESSING 으로 영구 고착</b>된다(이후 재처리는 stage/work 어느 쪽도 FAILED
     * 가 아니라 영구 409). 이를 막기 위해 클레임을 걸었던 호출자가 SKIPPED 를 받으면 본 메서드로 원상복구한다.
     *
     * <p>조건부(현재 PROCESSING 일 때만)라 그 사이 다른 주체가 상태를 바꿨으면 0행으로 안전하게 포기한다.
     *
     * @return 영향 행수 (1=보상 성공, 0=이미 다른 상태)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsDataRaw r SET r.dataSttsCd = :toStatus, r.mdfcnDt = CURRENT_TIMESTAMP "
            + "WHERE r.rawSn = :rawSn AND r.dataSttsCd = :fromStatus")
    int compensateReprocessClaim(@Param("rawSn") Long rawSn,
                                 @Param("fromStatus") String fromStatus,
                                 @Param("toStatus") String toStatus);

    /**
     * ffprobe 역류 back-fill — {@code LS_DATA_RAW.VDO_LEN_SEC} 가 비어 있을 때(NULL 또는 ≤0)만 초 단위
     * 길이로 채운다. 관제가 준 유효값(≥1)은 WHERE 가드로 보존한다(override 금지).
     *
     * <p><b>배경</b>: 주 적재 경로(관제 인입, {@code TrainingVideoIngestTx})는 인입
     * {@code VDO_LEN_SEC} 가 NULL 이거나 1초 미만이면 VDO_LEN_SEC 를 채우지 못한다. 적재 직후 이미 수행되는
     * ffprobe({@code AsyncVideoMetaRunner} → {@link kr.co.cudo.authoring.video.service.VideoMetaService})
     * 의 duration 결과를 초로 환산해 역류시켜 데이터 정합을 맞춘다.
     *
     * <p><b>단일 컬럼 조건부 UPDATE(엔티티 load-modify-save 아님)인 이유</b>: {@code LS_DATA_RAW} 는
     * {@code @Version}/{@code @DynamicUpdate} 가 없어 엔티티 저장 시 <b>전체 컬럼</b>을 덮어쓴다. 본 back-fill
     * 은 적재 직후 선두 비식별({@code DeidentifyStep})과 <b>동시</b> 실행되므로(같은 {@code VideoIngestedEvent},
     * {@code @Async batchAsyncExecutor}), 엔티티 전체 저장을 쓰면 비식별이 방금 커밋한 {@code DE_IDENT_YN='Y'}/
     * {@code DATA_STTS_CD} 를 stale 스냅샷으로 되돌릴 수 있다(lost update, CWE-362 → PII 재노출 위험).
     * {@code VDO_LEN_SEC} + {@code MDFCN_DT} 만 SET 하는 조건부 UPDATE 는 그 컬럼들을 건드리지 않아 안전하며,
     * DB row 잠금이 동시 UPDATE 를 직렬화한다({@link #claimReprocessFromFailed}/{@link #updateStatus} 와
     * 동일한 검증된 패턴). "비어 있을 때만" 가드도 WHERE 에서 DB 가 원자 판정한다.
     *
     * @param rawSn 대상 영상 PK
     * @param sec   ffprobe 로 산출한 초 단위 길이(≥1, 호출자 보장)
     * @return 영향 행수 (1=back-fill 됨, 0=이미 유효값 보유/row 부재)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsDataRaw r SET r.durationSec = :sec, r.mdfcnDt = CURRENT_TIMESTAMP "
            + "WHERE r.rawSn = :rawSn AND (r.durationSec IS NULL OR r.durationSec <= 0)")
    int backfillDurationSecIfBlank(@Param("rawSn") Long rawSn, @Param("sec") int sec);

    Page<LsDataRaw> findAllByOrderByRegDtDesc(Pageable pageable);

    /** 파생영상 목록(원본 1건 기준) — 파생 확정 상태 조회(E-ISSUE-24)용. */
    List<LsDataRaw> findAllByOrgnlRawSnOrderByRawSnAsc(Long orgnlRawSn);

    /**
     * 영상 처리 현황(GET /v1/videos) <b>통합 검색</b> — 상태 2종 + 검색어 + 이벤트 카테고리 + 촬영기간을
     * 한 쿼리에서 조립한다(원본 전용).
     *
     * <p><b>왜 하나로 합치는가</b>: 필터가 6개(상태 2 + 검색어 + 이벤트 + from + to)라 조합이 2^6 이다.
     * 파생 메서드 이름으로 풀면 조합 폭발이고, 조합마다 {@code ORGNL_RAW_SN IS NULL}(파생영상 제외)을
     * 다시 적어야 해서 <b>하나라도 빠지면 파생영상이 처리 현황에 샌다</b>. 조건은 전부 nullable 파라미터로
     * 조립하고 파생 제외는 이 쿼리 <b>한 곳</b>에만 둔다.
     *
     * <p><b>조인 3종</b>
     * <ul>
     *   <li>{@code LsRawDataStatus s} — 검수 상태 필터 + 정렬 alias({@code s.updDt}). 구
     *       {@code findOriginalsWithReviewStatus} 는 INNER JOIN 이었는데 여기서는 LEFT JOIN 이다.
     *       <b>동치인 이유</b>: {@code LS_RAW_DATA_STATUS} 의 PK 가 {@code RAW_DATA_ID} 라 영상당 최대
     *       1행이므로 중복 행이 생기지 않고, 필터가 지정되면 {@code s.dataSttsCd = :reviewStatusCd} 가
     *       상태행 없는 영상(= s 가 null)을 걸러 INNER 와 같은 결과를 낸다. 필터가 null 이면 조인이
     *       결과에 영향을 주지 않아 <b>구 무조인 분기와도 동일</b>하다.</li>
     * </ul>
     *
     * <p><b>CCTV 명 검색은 인입 평면값({@code LS_DATA_INGEST.CCTV_NM}) EXISTS 로</b> 판정한다. 구 구현은
     * 관제 공유 마스터({@code MNG_RESOURCE_CCTV})를 {@code LEFT JOIN} 했으나 그 테이블은 제거됐다(V167).
     * <b>조인이 아니라 EXISTS 인 이유</b>: 인입의 {@code RAW_SN} 에는 UNIQUE 가 없어(UK 는
     * {@code VMS_CLIP_ID}) 수기 정정으로 2행이 생기면 조인이 <b>목록 행을 증식</b>시켜
     * {@code totalElements} 까지 틀어진다. EXISTS 는 존재 여부만 보므로 행 수에 영향이 없다.
     * 연결 규칙(파생영상 {@code ORGNL_RAW_SN} 폴백)은
     * {@link kr.co.cudo.authoring.video.repository.IngestSourceLink} 와 같은 의미다 — 이 쿼리는
     * {@code v.orgnlRawSn IS NULL}(원본 전용)이라 폴백 항이 실제로는 타지 않지만, 조건을 그대로 적어
     * 파생 제외가 풀렸을 때 규칙이 갈라지지 않게 한다.
     *
     * <p><b>검색어({@code keyword})</b> 는 caller 가 소문자화 + LIKE 메타문자({@code % _ !}) 이스케이프
     * 까지 마친 {@code %패턴%} 이며, 이스케이프 문자는 {@code !} 다({@code ESCAPE '!'}). 사용자가 {@code %}
     * 를 넣어도 전체 매칭되지 않는다. 매칭 대상은 <b>화면에 보이는 값</b>과 같은 규칙으로 고른다 —
     * CCTV 명이 없거나 공백이면 {@code VMS_CCTV_ID} 로 폴백하는 것이
     * {@code VideoSummaryResponse.from} 의 표시 규칙과 동일하다. 숫자 입력은 caller 가 파싱한
     * {@code keywordRawSn} 으로 영상 ID 동등 비교를 OR 로 더한다(FE 라벨 "CCTV명 / 영상ID").
     *
     * <p><b>이벤트({@code eventCodes})</b> 는 카테고리 키를 관제 마스터로 변환한 EV-코드 집합이다.
     * 필터 미적용은 {@code eventFilterOn=0} 으로 표현하며, 이때도 {@code eventCodes} 에는 <b>비어 있지 않은</b>
     * 더미 컬렉션을 넘긴다(빈 {@code IN ()} 은 SQL 로 렌더되지 않는다).
     *
     * <p>모든 값은 파라미터 바인딩이며 문자열 연결로 조건/ORDER BY 를 만드는 지점이 없다(CWE-89).
     * 정렬 키는 호출 측이 allowlist 로 검증·매핑한 값만 넘어온다.
     */
    default Page<LsDataRaw> searchOriginals(String dataSttsCd,
                                            String reviewStatusCd,
                                            String keyword,
                                            Long keywordRawSn,
                                            int eventFilterOn,
                                            Collection<String> eventCodes,
                                            java.time.LocalDateTime from,
                                            java.time.LocalDateTime to,
                                            String skippedBundle,
                                            Pageable pageable) {
        return searchOriginals(dataSttsCd, reviewStatusCd, keyword, keywordRawSn,
                eventFilterOn, eventCodes, from, to, skippedBundle, null, null, pageable);
    }

    /**
     * 실패 묶음 필터까지 받는 전체 진입점 — 위 오버로드는 여기로 위임한다(하위호환).
     * [@design API-042] [@design ADR-050]
     *
     * @param failedBundleStages 실패로 볼 <b>단계</b> 코드 집합(그 묶음의 구성원). {@code null}·빈 값이면
     *                           필터 미적용
     * @param vlmFailureReasons  시계열 「확정 실패」 사유 문자열. 단일 원천은
     *                           {@code BatchBundleFailureGate.vlmFailureSkipReasons()} 이며 호출 서비스가
     *                           그대로 넘긴다({@code null}·빈 값이면 위탁 실패 축 미적용)
     */
    default Page<LsDataRaw> searchOriginals(String dataSttsCd,
                                            String reviewStatusCd,
                                            String keyword,
                                            Long keywordRawSn,
                                            int eventFilterOn,
                                            Collection<String> eventCodes,
                                            java.time.LocalDateTime from,
                                            java.time.LocalDateTime to,
                                            String skippedBundle,
                                            Collection<String> failedBundleStages,
                                            Collection<String> vlmFailureReasons,
                                            Pageable pageable) {
        return searchOriginals(dataSttsCd, reviewStatusCd, keyword, keywordRawSn,
                eventFilterOn, eventCodes, from, to, skippedBundle,
                failedBundleStages, vlmFailureReasons, null, pageable);
    }

    /**
     * 배정 스코핑까지 받는 전체 진입점 — 위 오버로드들은 여기로 위임한다(하위호환). [@design API-042]
     *
     * @param assignedToUserNo 이 사용자에게 <b>라벨링 작업자로 배정된</b> 영상만 남긴다. {@code null} 이면
     *                         스코핑 미적용(검수자 · 사용자 축이 없는 내부 호출). 값은 <b>인증 주체</b>에서만
     *                         와야 한다 — 요청 파라미터가 채울 수 있는 자리에 두면 그 자체가 IDOR 입구다
     *                         (CWE-639). 그래서 {@code VideoListFilter} 가 아니라 별도 인자다.
     */
    default Page<LsDataRaw> searchOriginals(String dataSttsCd,
                                            String reviewStatusCd,
                                            String keyword,
                                            Long keywordRawSn,
                                            int eventFilterOn,
                                            Collection<String> eventCodes,
                                            java.time.LocalDateTime from,
                                            java.time.LocalDateTime to,
                                            String skippedBundle,
                                            Collection<String> failedBundleStages,
                                            Collection<String> vlmFailureReasons,
                                            Long assignedToUserNo,
                                            Pageable pageable) {
        return searchOriginals(dataSttsCd, reviewStatusCd, keyword, keywordRawSn,
                eventFilterOn, eventCodes, from, to, skippedBundle,
                failedBundleStages, vlmFailureReasons, assignedToUserNo, false, pageable);
    }

    /**
     * 제외분 보기까지 받는 <b>최종 진입점</b> — 위 오버로드들은 여기로 위임한다(하위호환).
     * [@design API-042] [@design ADR-069]
     *
     * @param excludedOnly {@code false}(기본)면 <b>제외되지 않은 영상만</b>, {@code true} 면 <b>제외된
     *                     영상만</b> 남긴다. 값역은 이 두 갈래뿐이며 섞어 보는 갈래는 두지 않는다.
     *                     보내지 않던 기존 호출은 위 오버로드로 들어와 {@code false} 가 되므로 결과가
     *                     조금도 달라지지 않는다(하위호환 계약). 배제 술어의 소유자는
     *                     {@link VideoExclusionScope} 이며 목록과 건수에 <b>같은 문자열</b>이 붙는다.
     */
    default Page<LsDataRaw> searchOriginals(String dataSttsCd,
                                            String reviewStatusCd,
                                            String keyword,
                                            Long keywordRawSn,
                                            int eventFilterOn,
                                            Collection<String> eventCodes,
                                            java.time.LocalDateTime from,
                                            java.time.LocalDateTime to,
                                            String skippedBundle,
                                            Collection<String> failedBundleStages,
                                            Collection<String> vlmFailureReasons,
                                            Long assignedToUserNo,
                                            boolean excludedOnly,
                                            Pageable pageable) {
        boolean failedOn = failedBundleStages != null && !failedBundleStages.isEmpty();
        boolean vlmReasonOn = failedOn && vlmFailureReasons != null && !vlmFailureReasons.isEmpty();
        return searchOriginalsInternal(dataSttsCd, reviewStatusCd, keyword, keywordRawSn,
                eventFilterOn, eventCodes,
                from != null ? 1 : 0, from != null ? from : SHT_DT_FLOOR,
                to != null ? 1 : 0, to != null ? to : SHT_DT_CEILING,
                skippedBundle != null ? 1 : 0,
                skippedBundle != null ? skippedBundle : NO_BUNDLE_MATCH,
                MANUAL_SKIP_STTS_CD, MANUAL_SKIP_ERR_CD, MANUAL_SKIP_MARKER_ERR_CDS,
                failedOn ? 1 : 0,
                failedOn ? failedBundleStages : NO_STAGE_MATCH,
                vlmReasonOn ? 1 : 0,
                vlmReasonOn ? vlmFailureReasons : NO_REASON_MATCH,
                PROGRESS_FAILED_STTS_CD, VLM_STAGE_CD,
                assignedToUserNo != null ? 1 : 0,
                assignedToUserNo != null ? assignedToUserNo : NO_USER_MATCH,
                LABELER_TASK_TYPE_CD,
                excludedOnly ? 1 : 0,
                withDefaultRegDtDesc(pageable));
    }

    /**
     * 건너뜀 필터 미적용 시 바인딩할 <b>더미 묶음 코드</b> — 실제 묶음 코드와 절대 겹치지 않는 값.
     *
     * <p>{@code fromFilterOn} 과 같은 on/off 플래그 관례를 따른다. 플래그가 0 이면 이 값은 결과에 영향을
     * 주지 않지만, 파라미터가 항상 비교 위치에 등장해야 타입 추론이 확정되므로 {@code null} 을 넣지 않는다.
     */
    String NO_BUNDLE_MATCH = "__NO_BUNDLE_MATCH__";

    /**
     * 수동 건너뜀 표식 행의 처리상태·코드값 — {@code ManualStageSkip} 이 소유하는 상수의 <b>바인딩 값</b>이다.
     *
     * <p>리포지토리 인터페이스가 {@code batch} 패키지 상수를 정적 초기화로 끌어오면 두 모듈이 순환
     * 참조로 얽히므로 값만 옮겨 적고, 두 곳이 갈리지 않도록 {@code VideoListSkippedBundleFilterIT} 가
     * 상수 동일성을 기계로 고정한다.
     */
    String MANUAL_SKIP_STTS_CD = "SKIPPED";
    String MANUAL_SKIP_ERR_CD = "MANUAL_SKIP";
    List<String> MANUAL_SKIP_MARKER_ERR_CDS = List.of("MANUAL_SKIP", "MANUAL_SKIP_CLEARED");

    /**
     * 「지금 그 묶음이 건너뛴 상태인 영상만」 술어 — 본 쿼리와 count 쿼리가 <b>같은 문자열</b>을 쓴다.
     * [@design API-042] [@design ADR-050]
     *
     * <p>판정 축은 표식 소유자({@code BatchStatusService.isBundleManuallySkipped})와 같다 —
     * "(영상 × 묶음) 의 <b>마지막</b> 표식 행이 건너뜀인가". 표식은 append-only 라 스킵→해제→재스킵이
     * 반복될 수 있어, 존재 여부만 보면 <b>이미 되살린 영상까지</b> 걸린다.
     *
     * <p>정렬 키가 등록시각이 아니라 <b>PK</b> 인 것도 그 소유자와 같다 — 같은 밀리초에 스킵→해제가
     * 연달으면 시각 정렬은 판정을 뒤집는다.
     *
     * <p>{@code EXISTS} 라 목록 행을 증식시키지 않는다(조인이면 표식 개수만큼 행이 늘어
     * {@code totalElements} 까지 틀어진다). 모든 값은 파라미터 바인딩이다(CWE-89).
     */
    String SKIPPED_BUNDLE_PREDICATE =
            "AND (:skippedBundleOn = 0\n"
            + "     OR EXISTS (SELECT 1 FROM LsBatchProcLog b\n"
            + "                 WHERE b.dataRawSn = v.rawSn\n"
            + "                   AND b.procStepCd = :skippedBundle\n"
            + "                   AND b.procSttsCd = :skipSttsCd\n"
            + "                   AND b.errorCd = :skipErrCd\n"
            + "                   AND b.batchProcLogSn = (\n"
            + "                         SELECT MAX(x.batchProcLogSn) FROM LsBatchProcLog x\n"
            + "                          WHERE x.dataRawSn = v.rawSn\n"
            + "                            AND x.procStepCd = :skippedBundle\n"
            + "                            AND x.procSttsCd = :skipSttsCd\n"
            + "                            AND x.errorCd IN :skipMarkerErrCds)))\n";

    /**
     * 실패 필터 미적용 시 바인딩할 <b>더미 값</b> — {@code IN} 은 빈 컬렉션을 유효 SQL 로 렌더하지 못한다.
     * 플래그가 0 이면 결과에 영향을 주지 않지만, 파라미터가 항상 비교 위치에 등장해야 타입이 확정된다.
     */
    List<String> NO_STAGE_MATCH = List.of("__NO_STAGE_MATCH__");
    List<String> NO_REASON_MATCH = List.of("__NO_REASON_MATCH__");

    /**
     * 진행 축 실패 상태 코드 · 시계열 단계 코드 — {@code BatchStatusService}/{@code BatchStage} 가 소유하는
     * 상수의 <b>바인딩 값</b>이다(위 {@code MANUAL_SKIP_*} 과 같은 이유로 값만 옮겨 적는다). 두 곳이
     * 갈리지 않도록 {@code VideoListFailedBundleFilterIT} 가 상수 동일성을 기계로 고정한다.
     */
    String PROGRESS_FAILED_STTS_CD = "FAILED";
    String VLM_STAGE_CD = "VLM";

    /**
     * 「지금 그 묶음이 <b>실패한</b> 상태인 영상만」 술어 — 본 쿼리와 count 쿼리가 <b>같은 문자열</b>을 쓴다.
     * [@design API-042] [@design ADR-050]
     *
     * <h3>축이 둘인 이유 (하나로 통일할 수 없다)</h3>
     * <p>판정 소유자({@code BatchBundleFailureGate})와 <b>같은 OR 합성</b>이다.
     * <ul>
     *   <li><b>진행 축</b> — 마지막 <b>진행</b> 행(표식·감사 행 제외)이 그 묶음의 단계이면서 {@code FAILED}.
     *       오토라벨은 스텝이 예외를 던져 여기에 남는다.</li>
     *   <li><b>위탁 실패 감사 행</b> — 시계열 제출은 논블로킹이라 실패해도 예외가 위로 올라가지 않아
     *       진행 축이 <b>절대 {@code FAILED} 가 되지 않는다</b>. 그래서 {@code VLM/SKIPPED} + 확정 실패
     *       사유만 남으며, 이 축이 없으면 벤더 장애로 실패한 영상이 필터에 하나도 잡히지 않는다.</li>
     * </ul>
     *
     * <p>진행 행의 「마지막」을 <b>PK 최대</b>로 고르는 것은 위 건너뜀 술어와 같은 관례다 — 같은 밀리초에
     * 행이 겹치면 시각 정렬은 판정을 뒤집는다. 진행 행은 파이프라인 1회차에 <b>한 행</b>이라
     * ({@code markStage} 가 그 자리에서 갱신) 두 판정이 갈리지 않는다.
     *
     * <p>{@code EXISTS} 라 목록 행을 증식시키지 않는다(표식·감사 행이 쌓여도 {@code totalElements} 가
     * 틀어지지 않는다). 모든 값은 파라미터 바인딩이다(CWE-89).
     */
    String FAILED_BUNDLE_PREDICATE =
            "AND (:failedBundleOn = 0\n"
            + "     OR EXISTS (SELECT 1 FROM LsBatchProcLog f\n"
            + "                 WHERE f.dataRawSn = v.rawSn\n"
            + "                   AND f.procSttsCd = :progressFailedSttsCd\n"
            + "                   AND f.procStepCd IN :failedBundleStages\n"
            + "                   AND f.batchProcLogSn = (\n"
            + "                         SELECT MAX(y.batchProcLogSn) FROM LsBatchProcLog y\n"
            + "                          WHERE y.dataRawSn = v.rawSn\n"
            + "                            AND y.procSttsCd <> :skipSttsCd))\n"
            + "     OR (:vlmFailureOn = 1\n"
            + "         AND EXISTS (SELECT 1 FROM LsBatchProcLog g\n"
            + "                      WHERE g.dataRawSn = v.rawSn\n"
            + "                        AND g.procStepCd = :vlmStageCd\n"
            + "                        AND g.procSttsCd = :skipSttsCd\n"
            + "                        AND g.errorMsg IN :vlmFailureReasons)))\n";

    /**
     * 배정 스코핑 미적용 시 바인딩할 <b>더미 사용자 번호</b> — 실제 {@code USER_NO} 와 절대 겹치지 않는 값.
     *
     * <p>{@code fromFilterOn} 과 같은 on/off 플래그 관례를 따른다. 플래그가 0 이면 이 값은 결과에 영향을
     * 주지 않지만, 파라미터가 항상 비교 위치에 등장해야 타입 추론이 확정되므로 {@code null} 을 넣지 않는다.
     */
    Long NO_USER_MATCH = -1L;

    /**
     * 라벨링 작업자 배정의 작업유형 코드 — {@link LsTaskAssignment#TASK_LABELER} 를 <b>그대로 참조</b>한다.
     *
     * <p>위 {@code MANUAL_SKIP_*} 은 {@code batch} 패키지와의 순환 참조를 피하려 값만 옮겨 적었지만,
     * 여기서는 그럴 이유가 없다 — {@code assignment} 의 <b>엔티티</b> 상수라 빈 의존이 생기지 않고,
     * 같은 판정을 쓰는 단건 가드({@code LabelAccessGuard.verifyRawAccess})도 이 상수를 직접 참조한다.
     * 리터럴을 새로 적으면 목록과 단건의 배정 축이 조용히 갈라진다.
     */
    String LABELER_TASK_TYPE_CD = LsTaskAssignment.TASK_LABELER;

    /**
     * 「본인에게 라벨링 작업자로 배정된 영상만」 술어 — 본 쿼리와 count 쿼리가 <b>같은 문자열</b>을 쓴다.
     * [@design API-042]
     *
     * <p>판정 축은 단건 가드({@code LabelAccessGuard.verifyRawAccess})와 같다 —
     * {@code (RAW_SN, USER_NO, TASK_TYPE_CD='LABELER')} 의 배정 행이 존재하는가. 목록은 열려 있는데
     * 클릭하면 403 이 되는 비대칭을 없애는 것이 이 술어의 목적이므로 축이 갈리면 안 된다.
     *
     * <p><b>범위 제한은 거부가 아니라 결과 축소</b>다 — 배정이 하나도 없으면 403 이 아니라 빈 페이지다.
     *
     * <p>{@code EXISTS} 라 목록 행을 증식시키지 않는다. 한 영상에 같은 사용자의 {@code LABELER}·
     * {@code REVIEWER} 배정이 함께 있을 수 있는데(UK 가 작업유형까지 포함한다) 조인이면 그 영상이
     * 두 행으로 나와 {@code totalElements} 까지 부푼다. 모든 값은 파라미터 바인딩이다(CWE-89).
     */
    String ASSIGNED_ONLY_PREDICATE =
            "AND (:assignedOnlyOn = 0\n"
            + "     OR EXISTS (SELECT 1 FROM LsTaskAssignment a\n"
            + "                 WHERE a.rawDataId = v.rawSn\n"
            + "                   AND a.userNo = :actorUserNo\n"
            + "                   AND a.taskTypeCd = :labelerTaskTypeCd))\n";

    /**
     * 촬영기간 필터 미적용 시 바인딩할 <b>더미 경계값</b>.
     *
     * <p>날짜 조건만 {@code IS NULL} 대신 on/off 플래그를 쓰는 이유: PostgreSQL 확장 프로토콜은
     * {@code $n IS NULL} 처럼 <b>비교 상대가 없는 위치</b>의 timestamp 파라미터 타입을 추론하지 못해
     * {@code ERROR: could not determine data type of parameter} 로 실패한다(문자열 파라미터는 추론된다).
     * 플래그를 쓰면 파라미터가 항상 컬럼과 비교되는 위치에만 등장해 타입이 확정되고, 값 자체는
     * 플래그가 0 이라 결과에 영향을 주지 않는다.
     */
    java.time.LocalDateTime SHT_DT_FLOOR = java.time.LocalDateTime.of(1970, 1, 1, 0, 0);
    java.time.LocalDateTime SHT_DT_CEILING = java.time.LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    /**
     * 검색어 술어 — 화면 표시명(인입 {@code CCTV_NM}, 없거나 공백이면 {@code VMS_CCTV_ID} 폴백) 기준.
     * 본 쿼리와 count 쿼리가 <b>같은 문자열</b>을 쓰도록 상수로 뽑았다(둘이 갈라지면 목록 건수와
     * 총 건수가 어긋난다).
     */
    String KEYWORD_PREDICATE =
            "AND (:keyword IS NULL\n"
            + "     OR v.rawSn = :keywordRawSn\n"
            + "     OR EXISTS (SELECT 1 FROM LsDataIngest i\n"
            + "                 WHERE " + IngestSourceLink.JPQL_MATCHES_SOURCE + "\n"
            + "                   AND LOWER(TRIM(i.cctvNm)) LIKE :keyword ESCAPE '!')\n"
            + "     OR (LOWER(v.vmsCctvId) LIKE :keyword ESCAPE '!'\n"
            + "         AND NOT EXISTS (SELECT 1 FROM LsDataIngest i\n"
            + "                          WHERE " + IngestSourceLink.JPQL_MATCHES_SOURCE + "\n"
            + "                            AND TRIM(i.cctvNm) <> '')))\n";

    @Query(value = """
            SELECT v FROM LsDataRaw v
            LEFT JOIN LsRawDataStatus s ON s.rawDataId = v.rawSn
            WHERE v.orgnlRawSn IS NULL
            AND (:dataSttsCd IS NULL OR v.dataSttsCd = :dataSttsCd)
            AND (:reviewStatusCd IS NULL OR s.dataSttsCd = :reviewStatusCd)
            """
            + KEYWORD_PREDICATE
            + """
            AND (:eventFilterOn = 0 OR v.evntTypeCd IN :eventCodes)
            AND (:fromFilterOn = 0 OR v.shtDt >= :from)
            AND (:toFilterOn = 0 OR v.shtDt <= :to)
            """
            + SKIPPED_BUNDLE_PREDICATE
            + FAILED_BUNDLE_PREDICATE
            + ASSIGNED_ONLY_PREDICATE
            + VideoExclusionScope.EXCLUSION_TOGGLE_JPQL_V,
            countQuery = """
            SELECT COUNT(v) FROM LsDataRaw v
            LEFT JOIN LsRawDataStatus s ON s.rawDataId = v.rawSn
            WHERE v.orgnlRawSn IS NULL
            AND (:dataSttsCd IS NULL OR v.dataSttsCd = :dataSttsCd)
            AND (:reviewStatusCd IS NULL OR s.dataSttsCd = :reviewStatusCd)
            """
            + KEYWORD_PREDICATE
            + """
            AND (:eventFilterOn = 0 OR v.evntTypeCd IN :eventCodes)
            AND (:fromFilterOn = 0 OR v.shtDt >= :from)
            AND (:toFilterOn = 0 OR v.shtDt <= :to)
            """
            + SKIPPED_BUNDLE_PREDICATE
            + FAILED_BUNDLE_PREDICATE
            + ASSIGNED_ONLY_PREDICATE
            + VideoExclusionScope.EXCLUSION_TOGGLE_JPQL_V)
    Page<LsDataRaw> searchOriginalsInternal(@Param("dataSttsCd") String dataSttsCd,
                                            @Param("reviewStatusCd") String reviewStatusCd,
                                            @Param("keyword") String keyword,
                                            @Param("keywordRawSn") Long keywordRawSn,
                                            @Param("eventFilterOn") int eventFilterOn,
                                            @Param("eventCodes") Collection<String> eventCodes,
                                            @Param("fromFilterOn") int fromFilterOn,
                                            @Param("from") java.time.LocalDateTime from,
                                            @Param("toFilterOn") int toFilterOn,
                                            @Param("to") java.time.LocalDateTime to,
                                            @Param("skippedBundleOn") int skippedBundleOn,
                                            @Param("skippedBundle") String skippedBundle,
                                            @Param("skipSttsCd") String skipSttsCd,
                                            @Param("skipErrCd") String skipErrCd,
                                            @Param("skipMarkerErrCds") Collection<String> skipMarkerErrCds,
                                            @Param("failedBundleOn") int failedBundleOn,
                                            @Param("failedBundleStages") Collection<String> failedBundleStages,
                                            @Param("vlmFailureOn") int vlmFailureOn,
                                            @Param("vlmFailureReasons") Collection<String> vlmFailureReasons,
                                            @Param("progressFailedSttsCd") String progressFailedSttsCd,
                                            @Param("vlmStageCd") String vlmStageCd,
                                            @Param("assignedOnlyOn") int assignedOnlyOn,
                                            @Param("actorUserNo") Long actorUserNo,
                                            @Param("labelerTaskTypeCd") String labelerTaskTypeCd,
                                            @Param("excludedOnlyOn") int excludedOnlyOn,
                                            Pageable pageable);

    /**
     * 정렬이 지정되지 않은 Pageable 에 기존 기본 정렬({@code regDt DESC})을 채운다.
     *
     * <p>JPQL 에서 정적 {@code ORDER BY} 를 걷어낸 대가로, 정렬 미지정 호출은 ORDER BY 가 아예 없는
     * 쿼리가 되어 순서가 비결정적이 된다. 기본 순서 보장은 이 지점 한 곳에서만 한다.
     *
     * <p>{@code unpaged} Pageable 은 page/size 를 읽을 수 없어 그대로 통과시킨다 — 현재 호출자는
     * 모두 paged 이며, unpaged 로 부를 경우 호출자가 Sort 를 직접 지정해야 한다.
     */
    private static Pageable withDefaultRegDtDesc(Pageable pageable) {
        if (pageable == null || !pageable.isPaged() || pageable.getSort().isSorted()) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "regDt"));
    }

    /**
     * dataSttsCd 필터 — 정렬은 Pageable 의 Sort 로 위임한다 (정적 OrderBy 미적용).
     *
     * <p>정적 {@code OrderByRegDtDesc} 파생 메서드는 정적 {@code regDt DESC} 가 Pageable Sort 보다
     * 우선해 외부 정렬 키(예: capturedAt→shtDt)가 보조 정렬로만 밀린다. 본 메서드는 컨트롤러가
     * allowlist 로 검증·매핑한 Sort 를 1차 정렬로 적용하기 위해 사용한다.
     */
    Page<LsDataRaw> findAllByDataSttsCd(String dataSttsCd, Pageable pageable);

    /**
     * 영상 목록 + 검수 상태 필터(LS_RAW_DATA_STATUS) 조합 조회.
     *
     * <p>증강 요청 화면(SCR-AUG-001)에서 검수 완료(APPROVED) 영상만 노출하기 위한 용도.
     * 검수 상태는 영상 레코드와 별도 테이블에 저장되어 있으므로 명시적 JPQL JOIN ON 으로 결합한다
     * (Hibernate 6 HHH90003004 — 객체 참조 없는 implicit join 경고 회피).
     *
     * <p>INNER JOIN 특성상 LS_RAW_DATA_STATUS row 가 없는 영상(검수 미시작)은
     * reviewStatusCd 필터가 지정된 호출에서 자동 제외된다.
     *
     * <p>두 필터 모두 null 일 수 있으며 각각 조건부로 적용된다 (AND 결합).
     * 파라미터 바인딩만 사용 — SQL Injection 방어 (CWE-89).
     */
    @Query("SELECT v FROM LsDataRaw v JOIN LsRawDataStatus s ON s.rawDataId = v.rawSn " +
            "WHERE (:dataSttsCd IS NULL OR v.dataSttsCd = :dataSttsCd) " +
            "AND (:reviewStatusCd IS NULL OR s.dataSttsCd = :reviewStatusCd) " +
            "ORDER BY v.regDt DESC")
    Page<LsDataRaw> findAllWithReviewStatus(@Param("dataSttsCd") String dataSttsCd,
                                            @Param("reviewStatusCd") String reviewStatusCd,
                                            Pageable pageable);

    /**
     * 미배정(UNASSIGNED) 영상 목록 — 지정 배치 상태(dataSttsCd)이면서 LABELER 배정이 없는 영상만.
     *
     * <p>SCR-TASK-002 배정 전용 화면에서 작업자가 아직 배정되지 않은 영상만 노출하는 용도.
     * LS_TASK_ALTMNT 에 TASK_TYPE_CD='LABELER' row 가 없는 영상을 NOT EXISTS 로 필터링한다.
     * 파라미터 바인딩만 사용 — SQL Injection 방어 (CWE-89). 정렬은 Pageable 의 Sort 로 위임.
     */
    @Query("SELECT v FROM LsDataRaw v WHERE v.dataSttsCd = :dataSttsCd " +
            "AND NOT EXISTS (SELECT 1 FROM LsTaskAssignment a " +
            "WHERE a.rawDataId = v.rawSn AND a.taskTypeCd = 'LABELER')")
    Page<LsDataRaw> findUnassignedByDataSttsCd(@Param("dataSttsCd") String dataSttsCd, Pageable pageable);

    /** 개발 전용: DATA_STTS_CD 기준 가장 오래된 1건 (REG_DT 오름차순). */
    Optional<LsDataRaw> findFirstByDataSttsCdOrderByRegDtAsc(String dataSttsCd);

    /** 개발 전용: DATA_STTS_CD 기준 전체 목록. */
    List<LsDataRaw> findAllByDataSttsCd(String dataSttsCd);

    @Modifying
    @Transactional("controlTransactionManager")
    @Query("UPDATE LsDataRaw r SET r.dataSttsCd = :status, r.mdfcnDt = CURRENT_TIMESTAMP WHERE r.rawSn = :rawSn")
    void updateStatus(@Param("rawSn") Long rawSn, @Param("status") String status);

    /**
     * 영상(rawSn) 별 최신 내보내기 요약.
     *
     * <p>V34 (PJT_ID 제거) 이후 export 소스 테이블(LS_DATA_SET)에 영상(RAW_DATA_ID) 매핑 컬럼이
     * 없어 영상별 export 매핑은 사문화됐고, V86 에서 해당 테이블은 삭제됐다(Export 는 저작도구 범위 외).
     * 본 메서드는 API 응답 계약(exportStatus 등) 호환을 위해 stub 으로 남되 항상 빈 결과를 반환한다.
     */
    default List<VideoExportProjection> findLatestExportsByRawSns(Collection<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.emptyList();
    }

    /**
     * 페이지의 rawSn 들에 대해 (rawSn, cctvNm, vmsCctvId) 를 한 번에 조회 (N+1 회피).
     *
     * <p>FE WORKER/REVIEWER 작업 목록·검수 목록·증강 이력의 영상명 컬럼에 표시할 CCTV 명을 일괄
     * lookup 하기 위한 용도. 소스는 <b>관제 인입 평면값</b>({@code LS_DATA_INGEST.CCTV_NM})이며 구
     * 조달처였던 {@code MNG_RESOURCE_CCTV} 는 제거됐다(V167). 연결 규칙(파생영상
     * {@code ORGNL_RAW_SN} 1단계 폴백 · LATERAL 단건 보장)은 {@link IngestSourceLink} 단일 진실원에서
     * 온다 — 그래서 <b>파생영상(증강·해상도)도 부모의 CCTV 명이 그대로 표시된다</b>. 인입 행이 없는
     * 영상은 cctvNm 이 null 로 반환된다.
     *
     * <p>반환 행: {@code [Long rawSn, String cctvNm, String vmsCctvId]}.
     * 호출 측에서 cctvNm 이 null/blank 일 때 vmsCctvId 로 폴백한다.
     */
    default List<Object[]> findCctvNamesByRawSns(Collection<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) {
            return Collections.emptyList();
        }
        return findCctvNamesByRawSnsInternal(rawSns);
    }

    @Query(value = """
            SELECT r.RAW_SN AS rawSn,
                   i.CCTV_NM AS cctvNm,
                   r.VMS_CCTV_ID AS vmsCctvId
            FROM LS_DATA_RAW r
            """
            + IngestSourceLink.SQL_LATERAL_JOIN
            + """
            WHERE r.RAW_SN IN (:rawSns)
            """, nativeQuery = true)
    List<Object[]> findCctvNamesByRawSnsInternal(@Param("rawSns") Collection<Long> rawSns);

    /**
     * 페이지의 rawSn 들에 대해 (rawSn, eventName, eventTypeCd) 를 한 번에 조회 (N+1 회피).
     *
     * <p>FE 검수/작업 목록의 이벤트 컬럼에 표시할 이벤트 정보를 일괄 lookup 하기 위한 용도.
     * 현 단계에서는 {@code EVNT_TYPE_CD} 값을 eventName/eventTypeCd 양쪽에 동일하게 반환한다
     * (VideoSummaryResponse 와 동일한 정책 — 코드값 fallback). 향후 이벤트 마스터 테이블이
     * 추가되면 JOIN 으로 한글명을 가져오도록 확장 가능하다.
     *
     * <p>반환 행: {@code [Long rawSn, String eventName, String eventTypeCd]}.
     * 영상 메타가 없거나 EVNT_TYPE_CD 가 null 이면 호출 측에서 키가 누락된 채 반환된다.
     */
    default List<Object[]> findEventInfoByRawSns(Collection<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) {
            return Collections.emptyList();
        }
        return findEventInfoByRawSnsInternal(rawSns);
    }

    @Query(value = """
            SELECT r.RAW_SN AS rawSn,
                   r.EVNT_TYPE_CD AS eventName,
                   r.EVNT_TYPE_CD AS eventTypeCd
            FROM LS_DATA_RAW r
            WHERE r.RAW_SN IN (:rawSns)
            """, nativeQuery = true)
    List<Object[]> findEventInfoByRawSnsInternal(@Param("rawSns") Collection<Long> rawSns);

    /**
     * A-6 — 지정 파일 경로를 <b>다른 행</b>이 참조하고 있는지 센다(공유 파일 오삭제 방지).
     *
     * <p>구 경로 규약({@code videos/resolution/{parent}/{preset}.mp4})은 {@code (부모, 프리셋)} 만으로
     * 키잉돼 <b>같은 파일을 여러 파생 RAW 가 공유</b>했다(실측 5건 공유). 이관 후 구 파일을 지울 때 다른
     * 파생이 아직 그 경로를 가리키고 있으면 삭제해선 안 된다.
     *
     * <p>{@code LS_DATA_RAW.RAW_FILE_PATH_NM} 과 {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM}
     * (스트리밍·데이터마트가 읽는 비식별 영상 경로) 양쪽을 모두 센다. 파라미터 바인딩만 사용(CWE-89).
     */
    @Query(value = """
            SELECT (SELECT COUNT(*) FROM LS_DATA_RAW r
                     WHERE r.RAW_FILE_PATH_NM = :filePath AND r.RAW_SN <> :rawSn)
                 + (SELECT COUNT(*) FROM LS_DEIDENT_PROC_LOG l
                     WHERE l.DE_IDNTF_FILE_PATH_NM = :filePath AND l.DATA_RAW_SN <> :rawSn)
            """, nativeQuery = true)
    long countOtherReferencesToFilePath(@Param("filePath") String filePath, @Param("rawSn") Long rawSn);

    /**
     * <b>유예 삭제 안전 조건</b> — 지정 파일 경로를 <b>아직 어느 행이라도</b> 참조하고 있는지 센다.
     *
     * <p>유예 삭제(grace period) 스윕은 이관 시점이 아니라 <b>삭제 직전</b>에 안전을 재판정한다.
     * 삭제 조건은 ①DB 가 이미 새 경로를 가리킨다(=이 경로 참조 0) ②다른 행도 참조하지 않는다 이며,
     * 두 조건은 "총 참조 수 0" 하나로 동치다. 이관 시점에 통과했더라도 그 사이 롤백·재작성으로 다시
     * 참조가 생겼을 수 있으므로 <b>삭제 직전 재확인</b>이 필요하다.
     *
     * <p>영상 경로 2곳({@code LS_DATA_RAW.RAW_FILE_PATH_NM},
     * {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM}) 뿐 아니라 <b>프레임 경로</b>
     * ({@code LS_DATA_SRC.SRC_FILE_PATH_NM}/{@code DE_IDNTF_SRC_FILE_PATH_NM}) 도 센다 —
     * 유예 대기 목록에는 프레임 파일이 포함되므로 프레임 참조를 빼면 아직 서빙 중인 프레임을 지울 수 있다.
     * 파라미터 바인딩만 사용(CWE-89).
     */
    @Query(value = """
            SELECT (SELECT COUNT(*) FROM LS_DATA_RAW r
                     WHERE r.RAW_FILE_PATH_NM = :filePath)
                 + (SELECT COUNT(*) FROM LS_DEIDENT_PROC_LOG l
                     WHERE l.DE_IDNTF_FILE_PATH_NM = :filePath)
                 + (SELECT COUNT(*) FROM LS_DATA_SRC s
                     WHERE s.SRC_FILE_PATH_NM = :filePath
                        OR s.DE_IDNTF_SRC_FILE_PATH_NM = :filePath)
            """, nativeQuery = true)
    long countReferencesToFilePath(@Param("filePath") String filePath);

    /**
     * 감사 대상 프레임 커서 조회 — 비식별 경로가 있는 프레임을 {@code SRC_SN} 오름차순으로 페이징한다.
     *
     * <p><b>H-4</b>: 감사 판정 자체는 SQL 이 아니라 {@code StorageSubtreePolicy.verifyDeidentifiedFile}
     * (서빙과 동일 판정기)이 수행한다. SQL 로 근사(문자열 POSITION)하면 ①base 무검증 ②세그먼트가 아닌
     * 부분일치 ③파일시스템 무검증 3중 비동치가 생겨 "영향 없음" 주장을 입증할 수 없다. 여기서는
     * <b>행만</b> 넘긴다.
     *
     * <p><b>A-3 (뷰 게이트와의 동치)</b>: 빈 문자열/공백 경로는 <b>결측</b>으로 취급해 감사 입력에서
     * 제외한다({@code TRIM(...) <> ''}). V133 뷰 게이트와 {@link #findRawSnsExcludedByFrameViewGate}
     * 가 빈 문자열을 결측(정상 통과)으로 다루는데 감사만 이를 포함시켜 {@code BLANK} 를 위반으로
     * 집계하면, H-4 가 없앴다는 "SQL 근사 vs 코드 판정" 비동치가 <b>입력 단계</b>에 그대로 남는다.
     * 결측은 PII 노출이 아니라 데이터 결측이므로 위반 집계 대상이 아니다.
     *
     * <p>반환 행: {@code [Long srcSn, Long rawSn, String deidPath]}.
     */
    @Query(value = """
            SELECT s.SRC_SN, s.RAW_SN, s.DE_IDNTF_SRC_FILE_PATH_NM
            FROM LS_DATA_SRC s
            WHERE s.SRC_SN > :cursor
              AND s.DE_IDNTF_SRC_FILE_PATH_NM IS NOT NULL
              AND TRIM(s.DE_IDNTF_SRC_FILE_PATH_NM) <> ''
            ORDER BY s.SRC_SN
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findDeidFramePathsAfter(@Param("cursor") Long cursor, @Param("limit") int limit);

    /**
     * G-2 (D-ISSUE-46 정책 C) — 현행 {@code V_COMPLETED_FRAME} 게이트로 <b>뷰에서 제외되는</b> rawSn 목록.
     *
     * <p>게이트는 결함 형태(원본 경로를 비식별 경로로 노출 = 두 값이 <b>동일</b>)에 한정한다(M-1).
     * 비식별 경로 결측({@code DEID IS NULL})은 PII 노출이 아니라 데이터 결측이므로 제외 대상이 아니며,
     * 뷰에도 종전대로 노출된다. 빈 문자열은 코드({@code isBlank})와 동일하게 결측으로 취급한다.
     *
     * <p>반환 행: {@code [Long rawSn, Long frameCount]} — APPROVED 영상만(뷰 노출 조건과 동일).
     */
    @Query(value = """
            SELECT s.RAW_SN, COUNT(*) AS frameCount
            FROM LS_DATA_SRC s
            WHERE EXISTS (
                    SELECT 1 FROM LS_RAW_DATA_STATUS st
                     WHERE st.RAW_DATA_ID = s.RAW_SN AND st.DATA_STTS_CD = 'APPROVED')
              AND s.SRC_FILE_PATH_NM IS NOT NULL
              AND TRIM(s.SRC_FILE_PATH_NM) <> ''
              AND s.DE_IDNTF_SRC_FILE_PATH_NM IS NOT NULL
              AND TRIM(s.DE_IDNTF_SRC_FILE_PATH_NM) <> ''
              AND s.DE_IDNTF_SRC_FILE_PATH_NM = s.SRC_FILE_PATH_NM
            GROUP BY s.RAW_SN
            ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> findRawSnsExcludedByFrameViewGate();

    /**
     * 해상도 파생 백필 전용 — <b>파생 RAW 에 한해</b> 영상 파일 경로를 새 비식별 저장소 경로로 교체한다.
     * {@code ORGNL_RAW_SN IS NOT NULL} 조건으로 원본 영상 경로는 절대 변경될 수 없다(원본 보존 원칙).
     */
    @Modifying
    @Query(value = """
            UPDATE LS_DATA_RAW
               SET RAW_FILE_PATH_NM = :filePath
             WHERE RAW_SN = :rawSn
               AND ORGNL_RAW_SN IS NOT NULL
            """, nativeQuery = true)
    int updateDerivativeVideoPath(@Param("rawSn") Long rawSn, @Param("filePath") String filePath);

    /**
     * E-ISSUE-23 — 확정 실패한 파생 RAW 고아 행 삭제. <b>파생(ORGNL_RAW_SN NOT NULL) + FAILED</b>
     * 두 조건을 SQL 조건으로 함께 걸어, 삭제 직전 상태가 바뀐 행(정상 확정으로 전이)은 조건 불일치로
     * 0건 삭제된다(경합 안전).
     *
     * <p><b>M-5 (TOCTOU)</b>: "프레임 없음" 조건도 <b>DELETE 문 자체</b>에 {@code NOT EXISTS} 로 건다.
     * 호출측의 사전 {@code countByRawSn} 검사만으로는 검사~삭제 사이에 커밋된 프레임 INSERT 를 놓쳐
     * ({@code LS_DATA_SRC.RAW_SN} 에 FK 가 없어 DB 도 막아주지 않는다) RAW 없는 고아 프레임·라벨이 남는다.
     * 단일 문장 안에서 조건을 평가하면 그 창이 닫힌다.
     */
    @Modifying
    @Query(value = """
            DELETE FROM LS_DATA_RAW
             WHERE RAW_SN = :rawSn
               AND ORGNL_RAW_SN IS NOT NULL
               AND DATA_STTS_CD = 'FAILED'
               AND NOT EXISTS (SELECT 1 FROM LS_DATA_SRC s WHERE s.RAW_SN = :rawSn)
            """, nativeQuery = true)
    int deleteFailedDerivative(@Param("rawSn") Long rawSn);
}
