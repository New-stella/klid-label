package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 배치 단계별 DB 기반 상태 추적.
 * LsBatchProcLog 신규 스키마 기준으로 영상별 최신 로그를 갱신한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchStatusService {

    /**
     * 단계 미수행(skip) 감사 행의 처리상태 코드 — 진행 조회에서 제외되는 유일한 값.
     *
     * <p>{@code LS_BATCH_PROC_LOG.PROC_STTS_CD} 는 코드 도메인 {@code VARCHAR(20)} 자유값이며
     * (CHECK 제약 없음), 기존 값은 STARTED/COMPLETED/FAILED 3종이다. skip 은 이 중 어디에도 해당하지
     * 않으므로 별도 값으로 추가한다({@code BatchStage.SKIPPED} 와 이름이 같지만 축이 다르다 —
     * 이쪽은 <b>처리상태</b>, 저쪽은 진입 가드의 <b>반환 단계값</b>).
     */
    static final String STTS_SKIPPED = "SKIPPED";

    /**
     * 진행 행의 <b>종결</b> 처리상태 — 파이프라인이 끝까지 갔다는 뜻이다.
     *
     * <p>{@code markCompleted} 는 {@code updateStage(COMPLETED)} 로 {@code 'COMPLETED'} 를,
     * {@code markFailed} 는 {@code fail(cause)} 로 {@code 'FAILED'} 를 남긴다. 두 값 모두
     * {@code MDFCN_DT} 를 함께 갱신하므로 <b>언제 종결했는지</b>까지 판정할 수 있다
     * ({@link #progressTerminatedAfter}). 실행 중인 파이프라인은 {@code 'STARTED'} 다.
     */
    static final Set<String> TERMINAL_PROGRESS_STATUSES = Set.of("COMPLETED", "FAILED");

    private final LsBatchProcLogRepository repository;

    /** 파이프라인 진행 행(=SKIPPED 감사 행 제외 최신 행) 조회 — 모든 상태 갱신/조회의 단일 진입점. */
    private Optional<LsBatchProcLog> latestProgressLog(Long rawSn) {
        return repository.findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc(rawSn, STTS_SKIPPED);
    }

    @Transactional("controlTransactionManager")
    public void markStage(Long rawSn, BatchStage stage) {
        if (rawSn == null || stage == null) return;
        LsBatchProcLog log = latestProgressLog(rawSn)
                .map(existing -> { existing.updateStage(stage); return existing; })
                .orElseGet(() -> LsBatchProcLog.create(rawSn, stage));
        repository.save(log);
    }

    /**
     * VLM 단계를 <b>수행하지 않고 건너뛴 사실</b>을 사유와 함께 영속한다 (B-ISSUE-24).
     *
     * <p>과거 skip 경로는 애플리케이션 로그만 남기고 DB 에 아무 흔적도 남기지 않아, VLM 비활성/장애
     * 구간에 처리된 영상이 "메타 없음 + 무기록" 으로 남았다. 그 결과 재처리 대상 식별이 로그 보존기간에
     * 종속됐다. 이제 {@code PROC_STEP_CD='VLM' / PROC_STTS_CD='SKIPPED'} 감사 행 1건을 적재한다.
     *
     * @param reason 건너뛴 사유(예: {@code vlm.client.enabled=false})
     */
    @Transactional("controlTransactionManager")
    public void recordVlmSkipped(Long rawSn, String reason) {
        if (rawSn == null) return;
        saveVlmSkipRow(rawSn, reason);
    }

    /**
     * VLM 단계 미수행 사유를 <b>독립 트랜잭션</b>으로 적재한다 — 비동기 완료 핸들러/스위퍼 전용 (Phase C-1).
     *
     * <p>{@link #recordVlmSkipped} 와 적재 내용은 같고 트랜잭션 전파만 다르다. 논블로킹 제출의 완료
     * 핸들러는 파이프라인 스레드 밖(ambient tx 없음)에서 실행되고, 그 기록은 <b>호출자의 성패와
     * 무관하게 남아야</b> 재개 대상 식별이 가능하다({@code ledger.recordIssued} 와 동일 규약).
     *
     * <p>{@link #recordVlmSkipped} 를 그대로 REQUIRES_NEW 로 바꾸지 않은 이유: 그 메서드는
     * {@code vlm.client.enabled=false} 기본 형상에서 <b>모든</b> 배치가 지나는 길이라, 스텝 트랜잭션
     * 안에서 중첩 커넥션을 요구하게 만들면 커넥션 기아 교착(과거 실사고 2건)의 노출면만 넓어진다.
     *
     * <p>두 메서드는 프록시 경유가 필요한 자기호출을 피하려 공통 로직을 <b>비트랜잭션 private
     * 헬퍼</b>로 공유한다(자기호출로 경계가 유실되는 패턴을 만들지 않는다).
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordVlmSkippedInNewTx(Long rawSn, String reason) {
        if (rawSn == null) return;
        saveVlmSkipRow(rawSn, reason);
    }

    /** SKIPPED 감사 행 적재 공통 로직 — 트랜잭션 경계는 호출한 public 메서드가 소유한다. */
    private void saveVlmSkipRow(Long rawSn, String reason) {
        saveSkipRow(rawSn, BatchStage.VLM, reason);
    }

    /**
     * 임의 단계의 <b>미수행(skip) 사실</b>을 사유와 함께 영속한다 — {@link #recordVlmSkipped} 의 일반형.
     *
     * <p>애플리케이션 로그만 남기는 skip 은 "성공"과 구분되지 않고 로그 보존기간에 종속돼 재처리 대상
     * 식별이 불가능해진다(B-ISSUE-24 와 동일한 문제). 재비식별 프레임 재추출에서 {@code VDO_FRM_NO}
     * 결측으로 건너뛴 프레임이 대표 사례다 — 그 영상은 "재추출 성공"으로 응답하지만 마스킹 실패 픽셀이
     * 그대로 남는다(CWE-359).
     *
     * <p><b>전파는 REQUIRED</b> — 호출자의 트랜잭션에 참여한다. 이 기록은 "그 실행에서 건너뛰었다"는
     * 사실이므로, 그 실행 자체가 롤백되면 함께 사라지는 것이 맞다(반대로 {@code recordVlmSkippedInNewTx}
     * 는 ambient tx 가 없는 비동기 핸들러 전용이라 축이 다르다).
     *
     * @param reason 건너뛴 사유. 되읽기({@link #isStageSkippedWithReason})가 <b>정확 일치</b>로 판정하므로
     *               건수 등 가변값을 섞지 말고 <b>안정된 상수 문자열</b>을 넘긴다(단일 원천은 각 스텝의 상수).
     */
    @Transactional("controlTransactionManager")
    public void recordStageSkipped(Long rawSn, BatchStage stage, String reason) {
        if (rawSn == null || stage == null) return;
        saveSkipRow(rawSn, stage, reason);
    }

    private void saveSkipRow(Long rawSn, BatchStage stage, String reason) {
        repository.save(LsBatchProcLog.createSkipped(rawSn, stage, reason));
        log.info("[Batch] stage skipped recorded rawSn={} stage={}", rawSn, stage);
    }

    /**
     * 해당 단계가 <b>지정한 사유로 건너뛴(SKIPPED) 감사 행</b>을 갖고 있는가 — 보류 작업 재개 판정용.
     *
     * <p>{@link #recordVlmSkipped} 가 남긴 흔적을 되읽는 <b>대칭 진입점</b>이다. 신고 구간 보류처럼
     * "실패가 아니라서 재시도 큐가 집지 않는" 작업은 해제 시점에 누군가 이 흔적을 보고 재개해야 한다
     * ({@code VlmWithheldResumeRunner}). {@code PROC_STTS_CD} 리터럴을 호출부로 흘리지 않도록 판정은
     * 여기(로그 축의 소유자)에서 한다.
     *
     * @param reason 기록 시 사용한 사유 문자열(단일 원천은 각 스텝의 상수)
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public boolean isStageSkippedWithReason(Long rawSn, BatchStage stage, String reason) {
        if (rawSn == null || stage == null || reason == null) return false;
        return repository.existsByDataRawSnAndProcStepCdAndProcSttsCdAndErrorMsg(
                rawSn, stage.name(), STTS_SKIPPED, reason);
    }

    /**
     * 해당 단계가 <b>주어진 사유들 중 하나로</b> 건너뛴(SKIPPED) 감사 행을 갖고 있는가 (Phase C-1).
     *
     * <p>{@link #isStageSkippedWithReason} 의 다중 사유판. VLM 은 재개가 필요한 미수행 사유가
     * 셋(신고 보류 · 비동기 제출 실패 · ACK 미수신)으로 늘었고, 재개 판정은 <b>어느 사유든</b>
     * 성립해야 한다. 사유 문자열의 단일 원천은 각 스텝의 상수다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public boolean isStageSkippedWithAnyReason(Long rawSn, BatchStage stage, Collection<String> reasons) {
        if (rawSn == null || stage == null || reasons == null || reasons.isEmpty()) return false;
        return repository.existsByDataRawSnAndProcStepCdAndProcSttsCdAndErrorMsgIn(
                rawSn, stage.name(), STTS_SKIPPED, reasons);
    }

    /**
     * REVIEWER 가 <b>손으로 누른</b> 작업 묶음 스킵 표식을 적재한다. [@design API-198]
     *
     * <p>{@link #recordStageSkipped} 와 저장 축(SKIPPED 감사 행)은 같지만 <b>사유 축이 다르다</b> —
     * 이쪽은 전용 {@code ERR_CD} + 강제 접두를 써서 {@code VlmTimeseriesStep.RESUMABLE_SKIP_REASONS}
     * 재개 판정에 절대 걸리지 않는다(사람의 결정이 이벤트에 뒤집히지 않게).
     *
     * <p><b>기록은 단일 INSERT 다</b> — 묶음 코드 한 행이 그 묶음의 결정을 통째로 담으므로 오토라벨
     * 3단계 중 일부만 스킵된 <b>부분 상태가 표현 자체로 불가능</b>하다(근거는 {@link ManualStageSkip}).
     *
     * @param reason  접두가 이미 붙은 사유(정제·상한은 호출 서비스가 적용)
     * @param actorId 행위자 식별자
     */
    @Transactional("controlTransactionManager")
    public void recordManualStageSkip(Long rawSn, BatchStageBundle bundle, String reason, String actorId) {
        if (rawSn == null || bundle == null) return;
        repository.save(LsBatchProcLog.createManualSkipMarker(
                rawSn, bundle, ManualStageSkip.ERR_CD_SKIPPED, reason, actorId));
        log.info("[Batch] manual bundle skip recorded rawSn={} bundle={}", rawSn, bundle);
    }

    /**
     * 수동 스킵 <b>해제</b> 표식을 적재한다 — 표식만 지우고 작업을 실행하지 않는다. [@design API-200]
     *
     * <p>기존 행을 지우거나 갱신하지 않는다(append-only) — 누가 언제 스킵했고 누가 언제 풀었는지가
     * 모두 남아야 감사로 성립한다. 기록과 마찬가지로 <b>단일 INSERT</b> 라 해제도 원자적이다.
     */
    @Transactional("controlTransactionManager")
    public void recordManualStageSkipCleared(
            Long rawSn, BatchStageBundle bundle, String reason, String actorId) {
        if (rawSn == null || bundle == null) return;
        repository.save(LsBatchProcLog.createManualSkipMarker(
                rawSn, bundle, ManualStageSkip.ERR_CD_CLEARED, reason, actorId));
        log.info("[Batch] manual bundle skip cleared rawSn={} bundle={}", rawSn, bundle);
    }

    /**
     * 이 묶음이 <b>지금</b> 수동 스킵 상태인가 — 스킵 게이트의 <b>단일 판정 지점</b>. [@design API-198]
     *
     * <p>판정은 "(영상 × 묶음) 의 마지막 표식 행이 {@link ManualStageSkip#ERR_CD_SKIPPED} 인가" 하나다.
     * 호출부(오케스트레이터 루프 · VLM 전송 직전)가 이 규칙을 재유도하면 두 곳이 갈린다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public boolean isBundleManuallySkipped(Long rawSn, BatchStageBundle bundle) {
        if (rawSn == null || bundle == null) return false;
        return latestManualSkipMarker(rawSn, bundle)
                .map(l -> ManualStageSkip.ERR_CD_SKIPPED.equals(l.getErrorCd()))
                .orElse(false);
    }

    /**
     * 이 <b>단계</b>가 지금 건너뛰어야 하는가 — 오케스트레이터 루프·VLM 전송 직전 게이트가 부른다.
     * [@design API-198]
     *
     * <p>단계는 스스로 스킵되지 않는다. 그 단계가 <b>속한 묶음</b>이 스킵됐는지를 물을 뿐이며, 소속
     * 판정은 {@link BatchStageBundle#containing} 단일 지점이다. 어느 묶음에도 없는 단계
     * ({@code MARKING}·{@code FRAME_EXTRACT})는 항상 {@code false} — 건너뛸 수 없다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public boolean isStageManuallySkipped(Long rawSn, BatchStage stage) {
        return BatchStageBundle.containing(stage)
                .map(bundle -> isBundleManuallySkipped(rawSn, bundle))
                .orElse(false);
    }

    /**
     * <b>지금</b> 수동 스킵 상태인 작업 묶음 목록 — 영상 상세용. [@design API-043]
     *
     * <p>건너뛴 묶음은 {@code markStage} 를 타지 않고 표식 행도 진행 조회에서 제외되므로,
     * <b>진행 축({@link #stagesFor})만으로는 어느 묶음이 스킵됐는지 알 수 없다.</b> 화면이 스킵 표시와
     * 되돌리기 조작을 띄우려면 이 목록이 필요하다.
     *
     * <p><b>판정 규칙은 {@link #isBundleManuallySkipped} 와 동일</b>하다 — "마지막 표식 행이
     * {@link ManualStageSkip#ERR_CD_SKIPPED} 인가". 여기서 규칙을 재유도하지 않고 같은 축을 한 번에
     * 읽기만 한다(왕복 1회 — 상세는 폴링 경로다).
     *
     * <p>순서는 {@link BatchStageBundle} 선언 순서(VLM → AUTOLABEL) <b>고정</b>이다 — DB 반환 순서를
     * 그대로 쓰면 실행마다 흔들려 화면이 깜빡인다.
     *
     * @return 스킵 중인 묶음 코드 목록. 없으면 <b>빈 리스트</b>({@code null} 아님)
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<String> manuallySkippedBundles(Long rawSn) {
        if (rawSn == null) {
            return List.of();
        }
        Set<String> skipped = repository
                .findLatestManualSkipMarkers(rawSn, STTS_SKIPPED, ManualStageSkip.MARKER_ERR_CDS)
                .stream()
                .filter(l -> ManualStageSkip.ERR_CD_SKIPPED.equals(l.getErrorCd()))
                .map(LsBatchProcLog::getStageCd)
                .collect(Collectors.toSet());
        return java.util.Arrays.stream(BatchStageBundle.values())
                .map(BatchStageBundle::name)
                .filter(skipped::contains)
                .toList();
    }

    /**
     * <b>이 묶음의 건너뛰기를 되돌렸는가</b>(마지막 표식 행이 해제인가) — 지목 재수행의 수락 판정.
     * [@design API-201]
     *
     * <h3>왜 이 판정이 재수행의 입구인가</h3>
     * <p>재수행은 <b>요청이 대상 묶음을 자유롭게 고르지 못한다</b>. 임의 묶음을 받으면 앞 작업을
     * 건너뛰도록 요청이 강제할 수 있어 전제 없는 산출물이 만들어진다. 그래서 서버는 <b>그 영상에서
     * 실제로 되돌린 묶음</b>만 수락하며, 그 판정이 여기다.
     *
     * <h3>판정 규칙은 새로 만들지 않는다</h3>
     * <p>{@link #isBundleManuallySkipped} 와 <b>완전히 같은 축</b>("(영상 × 묶음) 의 마지막 표식 행")을
     * 같은 쿼리({@link #latestManualSkipMarker})로 읽고, 그 값이 {@link ManualStageSkip#ERR_CD_SKIPPED}
     * 가 아니라 {@link ManualStageSkip#ERR_CD_CLEARED} 인지만 본다 — 두 판정은 같은 질문의 앞뒷면이라
     * 규칙이 갈릴 수 없다.
     *
     * <p>지금 다시 스킵된 묶음(마지막 행이 SKIPPED)은 해당하지 않는다 — 재수행해도 오케스트레이터의
     * 스킵 게이트가 다시 건너뛰므로 "다시 수행할 묶음"이 아니다. 표식이 아예 없는 묶음도 해당하지
     * 않는다(되돌린 적이 없다).
     *
     * @return 그 묶음의 마지막 표식이 <b>해제</b>면 {@code true}
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public boolean hasClearedManualSkip(Long rawSn, BatchStageBundle bundle) {
        if (rawSn == null || bundle == null) {
            return false;
        }
        return latestManualSkipMarker(rawSn, bundle)
                .map(l -> ManualStageSkip.ERR_CD_CLEARED.equals(l.getErrorCd()))
                .orElse(false);
    }

    /**
     * 수동 재기동·재수행의 <b>선점 표식(열림)</b>을 적재한다 — 고착 회수의 유일한 판정 근거.
     *
     * <p>선점 직전 상태({@link ReprocessClaimOrigin})는 지금까지 호출 스레드의 인자로만 존재해
     * <b>노드가 죽으면 함께 사라졌다</b>. 이 기록이 그 값을 DB 에 남긴다. 저장 축·판정 규칙의 단일
     * 원천은 {@link ReprocessClaimMarker} 이며 여기서 재유도하지 않는다.
     *
     * <p><b>REQUIRES_NEW</b> — 호출자(재기동 서비스)는 트랜잭션 없이 원자 클레임 직후 이 메서드를 부르며,
     * 이 기록은 <b>선점이 커밋된 사실</b>을 남기는 감사라 호출자의 후속 성패(디스패치 거부 등)와 무관하게
     * 남아야 한다. 남지 않으면 그 영상은 회수 대상에서 영구히 빠진다.
     *
     * @param originStageStatus 선점 직전의 배치 단계 상태 코드. 알 수 없는 값이면 표식을 남기지 않고
     *                          WARN 만 남긴다 — 추측한 출발 상태를 적으면 회수가 <b>잘못된 상태로</b>
     *                          되돌려 완주 영상을 실패로 강등할 수 있다(fail-closed)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordReprocessClaimOpened(Long rawSn, String originStageStatus) {
        if (rawSn == null) return;
        ReprocessClaimOrigin origin = ReprocessClaimOrigin.fromStageStatus(originStageStatus).orElse(null);
        if (origin == null) {
            log.warn("[Batch][ReclaimMarker] claim origin not recognised — marker skipped rawSn={}", rawSn);
            return;
        }
        repository.save(LsBatchProcLog.createReprocessClaimMarker(
                rawSn, ReprocessClaimMarker.ERR_CD_OPEN, origin.name(), ReprocessClaimMarker.REG_ID));
        log.info("[Batch][ReclaimMarker] claim opened rawSn={} origin={}", rawSn, origin);
    }

    /**
     * 선점 표식을 <b>닫는다</b> — 실행이 어떤 결과로든 끝났거나 접수 자체가 거부돼 선점을 되돌렸을 때.
     *
     * <p>기존 행을 지우거나 갱신하지 않는다(append-only) — 언제 선점하고 언제 놓았는지가 모두 남아야
     * 감사로 성립한다. <b>이 기록이 빠지면</b> 뒤에 다른 경로로 고착된 같은 영상을 스윕이 <b>옛 표식의
     * 출발 상태로</b> 되돌린다(근거는 {@link ReprocessClaimMarker} 「닫힘 행이 왜 반드시 필요한가」).
     *
     * @param detail 종료 사유 문구(고정 상수). 사용자 입력·경로·PII 를 담지 않는다
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordReprocessClaimClosed(Long rawSn, String detail) {
        if (rawSn == null) return;
        repository.save(LsBatchProcLog.createReprocessClaimMarker(
                rawSn, ReprocessClaimMarker.ERR_CD_CLOSED, detail, ReprocessClaimMarker.REG_ID));
    }

    /**
     * 회수 스윕이 고착 선점을 되돌렸다는 <b>감사 표식</b>을 적재한다 — 닫힘 축의 일종이다.
     *
     * <p>무엇을 왜 회수했는지가 여기 남는다(무엇=rawSn, 왜=사유 문구, 어디로=복구한 출발 축).
     * 회수는 드문 사건이고 애플리케이션 로그는 보존기간에 종속되므로 DB 에 남긴다(B-ISSUE-24 와 같은 취지).
     *
     * <p><b>전파는 REQUIRED</b>({@link #recordReprocessClaimOpened}·{@link #recordReprocessClaimClosed}
     * 와 <b>의도적으로 다르다</b>) — 이 기록은 「상태를 되돌렸다」와 <b>한 몸</b>이라 호출자의 트랜잭션에
     * 참여해야 한다. 독립 커밋이면 상태만 되돌아가고 표식은 열린 채 남는 창이 열리고, 그 표식은 나중에
     * 다른 경로로 고착된 같은 영상을 <b>옛 출발 상태로</b> 되돌리게 만든다(완주 영상의 {@code FAILED}
     * 강등 = 이 설계가 막으려던 파괴). 경계는 {@code ProcessingStaleReclaimTxService.reclaimAndClose}
     * 가 세운다.
     */
    @Transactional("controlTransactionManager")
    public void recordReprocessClaimReclaimed(Long rawSn, String detail) {
        if (rawSn == null) return;
        repository.save(LsBatchProcLog.createReprocessClaimMarker(
                rawSn, ReprocessClaimMarker.ERR_CD_RECLAIMED, detail, ReprocessClaimMarker.RECLAIM_REG_ID));
    }

    /**
     * <b>지금 열려 있는</b> 선점 표식 행 — 마지막 표식이 열림일 때만 값이 있다.
     *
     * <p>판정 규칙은 수동 스킵({@code isBundleManuallySkipped})과 같은 골격이다 — "(영상) 의 마지막
     * 표식 행이 열림 코드인가". 닫힘·회수 행이 뒤에 붙어 있으면 비어 있다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Optional<LsBatchProcLog> openReprocessClaimMarker(Long rawSn) {
        if (rawSn == null) return Optional.empty();
        return repository
                .findTopByDataRawSnAndProcStepCdAndProcSttsCdAndErrorCdInOrderByBatchProcLogSnDesc(
                        rawSn, ReprocessClaimMarker.PROC_STEP_CD, STTS_SKIPPED,
                        ReprocessClaimMarker.MARKER_ERR_CDS)
                .filter(l -> ReprocessClaimMarker.ERR_CD_OPEN.equals(l.getErrorCd()));
    }

    /**
     * 파이프라인 <b>진행 행</b>의 마지막 갱신 시각 — "진행이 멈췄는가" 판정의 입력.
     *
     * <p>{@code markStage} 가 <b>단계마다</b> 이 행의 {@code MDFCN_DT} 를 갱신하므로, 살아 있는
     * 파이프라인은 단계가 넘어갈 때마다 값이 앞으로 간다. 진행 행이 없으면(배치 미진행) 비어 있다.
     *
     * <p>⚠ <b>이 값 단독으로 고착을 판정하면 안 된다</b> — 큐에서 대기 중인(아직 한 단계도 실행하지
     * 않은) 재기동은 이 값이 <b>직전 실행 때의 옛 시각</b>이라 즉시 "멈춤"으로 보인다. 회수 판정은
     * 반드시 선점 표식 시각과 함께 <b>더 나중 값</b>을 기준으로 삼는다
     * ({@code ProcessingStaleReclaimTxService}).
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Optional<java.time.LocalDateTime> latestProgressUpdatedAt(Long rawSn) {
        if (rawSn == null) return Optional.empty();
        return latestProgressLog(rawSn).map(LsBatchProcLog::getUpdatedAt);
    }

    /**
     * 이 영상의 배치가 <b>{@code since} 이후에 종결(완료/실패)까지 갔던 흔적</b>이 있는가 — 회수의
     * <b>에피소드 결속</b> 판정.
     *
     * <h3>무엇을 가르는가</h3>
     * <p>선점 표식은 닫힘 행 1건이 유실되면 열린 채 남는다(러너 {@code finally} 의 기록 실패·프로세스
     * 사망). 그러면 "마지막 표식이 열림인가" 만으로는 그 표식이 <b>지금 고착의 것</b>인지 <b>이미 끝난
     * 옛 에피소드의 잔재</b>인지 구분되지 않고, 잔재를 근거로 회수하면 완주 영상이 옛 출발 상태
     * ({@code FAILED})로 강등된다.
     *
     * <p>둘을 가르는 신호가 <b>종결 기록의 유무</b>다.
     * <ul>
     *   <li><b>진짜 고착</b>(큐 대기 중 노드 사망) — 한 단계도 실행하지 못했으므로 표식 이후 진행 행이
     *       움직이지 않았다. 진행 행이 종결 상태여도 그 시각은 <b>표식보다 이전</b>(직전 실행 때)이다.</li>
     *   <li><b>잔재 표식</b> — 그 뒤 파이프라인이 끝까지 가서 {@code markCompleted}/{@code markFailed} 가
     *       진행 행을 종결 상태 + 새 {@code MDFCN_DT} 로 갱신했다.</li>
     * </ul>
     *
     * <p>실행 중({@code 'STARTED'})은 종결이 아니므로 {@code false} 다 — 실행 도중 노드가 죽어 생긴
     * 고착은 회수 대상으로 남는다.
     *
     * @param since 선점 표식이 열린 시각
     * @return 표식 이후에 종결 기록이 있으면 {@code true}(→ 호출자는 회수를 포기해야 한다)
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public boolean progressTerminatedAfter(Long rawSn, java.time.LocalDateTime since) {
        if (rawSn == null || since == null) return false;
        return latestProgressLog(rawSn)
                .filter(l -> TERMINAL_PROGRESS_STATUSES.contains(l.getProcSttsCd()))
                .map(LsBatchProcLog::getUpdatedAt)
                .map(updatedAt -> updatedAt.isAfter(since))
                .orElse(false);
    }

    /** 마지막 수동 스킵 표식 행(스킵 또는 해제) — 상태 조회 응답이 사유·행위자를 함께 내리는 데 쓴다. */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Optional<LsBatchProcLog> latestManualSkipMarker(Long rawSn, BatchStageBundle bundle) {
        if (rawSn == null || bundle == null) return Optional.empty();
        return repository
                .findTopByDataRawSnAndProcStepCdAndProcSttsCdAndErrorCdInOrderByBatchProcLogSnDesc(
                        rawSn, bundle.name(), STTS_SKIPPED, ManualStageSkip.MARKER_ERR_CDS);
    }

    /**
     * 영상 상세용 <b>배치 실패 사유</b>(사용자 문구). 실패가 아니면 {@code null}. [@design API-043]
     *
     * <p>내부 원문({@code ERR_MSG_CN})은 <b>읽지도 않는다</b> — 변환 판정은
     * {@link BatchFailureReasonPolicy} 단일 지점이며 단계·원인 유형 코드만 입력으로 받는다(CWE-209).
     *
     * <p>단계를 특정할 수 없는 실패({@code PROC_STEP_CD='FAILED'})도 사유를 돌려준다 — 그런 영상은
     * {@link #stagesFor} 가 빈 배열을 주므로, 사유가 단계 배열 안에 있었다면 아무것도 못 봤을 것이다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public String failureReasonFor(Long rawSn) {
        if (rawSn == null) {
            return null;
        }
        return latestProgressLog(rawSn)
                .map(l -> BatchFailureReasonPolicy.describe(
                        l.getStageCd(), l.getProcSttsCd(), l.getErrorCd()))
                .orElse(null);
    }

    @Transactional("controlTransactionManager")
    public void markCompleted(Long rawSn) {
        markStage(rawSn, BatchStage.COMPLETED);
    }

    /**
     * VLM 시계열 외부 위탁 응답(externalJobId/status) 을 최신 로그의 RESP_PAYLOAD_CN 에 기록.
     *
     * <p>Phase 2 결과 수신 webhook 에서 externalJobId 로 영상을 역추적할 때 사용한다.
     * 별도 컬럼 추가 없이 기존 {@code RESP_PAYLOAD_CN} JSON 컬럼에 적재한다.
     * 로그가 없으면 새로 생성한다.
     *
     * <h3>왜 {@code REQUIRES_NEW} 인가 (감사 기록은 스텝 성패와 무관, CWE-778)</h3>
     * <p>이 메서드는 <b>외부 위탁이 이미 성공한 사실</b>(request_id/status)을 남기는 감사 기록이다.
     * 호출자({@code VlmTimeseriesStep.persistResult})는 스텝 트랜잭션 <b>안</b>에서 부르고, 그 뒤에
     * 마킹 상태 전이 저장이 이어진다 — 기본 propagation(REQUIRED)이면 그 후속 작업이 실패할 때 이미
     * 성공한 외부 호출의 유일한 흔적이 함께 사라진다(콜백 역추적 근거 소실). 그래서 스텝 tx 와 운명을
     * 분리해 독립 커밋한다({@code ledger.recordIssued} 와 동일한 규약).
     *
     * <p><b>커넥션 1개 추가 요구</b> — 이 호출 지점은 외부 I/O({@code vlmClient…block()})가 <b>이미
     * 반환한 뒤</b>이므로, 외부 대기 중에 커넥션 2개를 붙잡지 않는다. 또 같은 경로에서
     * {@code WebhookIdempotencyLedger.recordIssued} 가 이미 {@code REQUIRES_NEW} 로 중첩 커넥션을
     * 요구하므로 이 경로의 동시 점유 최대치(2)는 변하지 않는다.
     * <p>같은 클래스의 {@code recordVlmSkipped} 는 <b>일부러 바꾸지 않았다</b> — 호출 직후 곧바로
     * 반환해 스텝 tx 가 커밋되므로 롤백에 휩쓸릴 후속 작업이 없고(위험 부재), 그 경로는
     * {@code vlm.client.enabled=false} 기본 형상에서 <b>모든</b> 배치가 지나는 길이라 여기에 중첩
     * 커넥션을 요구하면 커넥션 기아 교착(과거 실사고 2건)의 노출면만 넓어진다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordVlmTimeseriesResult(Long rawSn, String resPayloadJson) {
        if (rawSn == null) return;
        LsBatchProcLog logEntry = latestProgressLog(rawSn)
                .orElseGet(() -> LsBatchProcLog.create(rawSn, BatchStage.VLM));
        logEntry.setResPayloadCn(resPayloadJson);
        repository.save(logEntry);
    }

    @Transactional("controlTransactionManager")
    public void markFailed(Long rawSn, Throwable cause) {
        if (rawSn == null) return;
        Optional<LsBatchProcLog> existing = latestProgressLog(rawSn);
        LsBatchProcLog log = existing.orElseGet(() -> LsBatchProcLog.create(rawSn, BatchStage.FAILED));
        log.fail(cause);
        if (existing.isPresent()) {
            log.incrementRetry();
        }
        repository.save(log);
    }

    // 관측 전용 — 현재 소비 API 없음(진행률 화면 연결 시 사용 예정).
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public BatchStage currentStage(Long rawSn) {
        return latestProgressLog(rawSn)
                .map(l -> BatchStage.valueOf(l.getStageCd()))
                .orElse(BatchStage.PENDING);
    }

    /**
     * 영상 상세 진행률 표시용 — 최신 배치 로그를 canonical 단계 순서로 펼친 상태 리스트.
     *
     * <p>로그가 없으면(배치 미진행/기존 영상) 빈 리스트를 반환해 FE 가 기존 배지로 폴백하게 한다
     * (하위호환, 예외 없음). 단건 상세 조회에서만 호출하므로 영상당 1쿼리 이내(N+1 아님).
     *
     * @param videoCompleted 영상이 이미 COMPLETED 인지(LS_DATA_RAW.DATA_STTS_CD) — true 면 전 단계 DONE.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<BatchStageProgressMapper.StageStatus> stagesFor(Long rawSn, boolean videoCompleted) {
        if (rawSn == null) {
            return List.of();
        }
        return latestProgressLog(rawSn)
                .map(l -> BatchStageProgressMapper.build(l.getStageCd(), l.getProcSttsCd(), videoCompleted))
                .orElseGet(List::of);
    }
}
