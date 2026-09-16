package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.dto.KpstDeidentReportSummary;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.step.DeidentFrameAttacher;
import kr.co.cudo.authoring.common.cache.StreamMetaCacheEvictor;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.DeidentArtifactIntegrity;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * KPST 폴링 상태 전이 전용 트랜잭션 서비스 (Phase 3 / UC018).
 *
 * <p>{@link KpstDeidentService} 의 폴링 오케스트레이션은 비트랜잭션(다운로드 I/O 포함)이며 self-invocation
 * 으로는 {@code @Transactional} 프록시가 적용되지 않는다(BatchTransitionService 와 동일 제약). 따라서 DB
 * 상태 전이는 별도 빈의 {@code REQUIRES_NEW} public 메서드로 분리해 cross-bean 호출로 트랜잭션을 보장한다.
 * 각 메서드는 load → 비즈니스 메서드 → dirty checking 영속의 단순 위임만 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "kpst.deid", name = "enabled", havingValue = "true")
public class KpstDeidentTxService {

    private final VideoRepository videoRepository;
    private final LsDeidentProcLogRepository procLogRepository;
    private final DeidentReportService deidentReportService;
    private final NotificationService notificationService;
    private final WorkLockService workLockService;
    private final DeidentFrameAttacher deidentFrameAttacher;
    private final StreamMetaCacheEvictor streamMetaCacheEvictor;
    /**
     * 비식별 성공 기록에 붙여 검수 승인 보류를 푸는 단일 지점(ADR-048). 자체 트랜잭션을 열지 않아
     * 본 서비스의 {@code REQUIRES_NEW} 에 참여한다 — 성공 기록이 롤백되면 보류 해제도 함께 롤백된다.
     */
    private final DeidentApprovalHoldReleaser deidentApprovalHoldReleaser;
    /**
     * 예약 마킹 활성화·마감 배선 (ADR-052). <b>KPST 폴링 완료</b> 경로가 이 서비스이며, mock 동기 완료
     * 경로의 같은 배선은 {@code AsyncDeidentifyRunner} 에 따로 있다 — 두 경로가 각각 배선돼야 한다
     * (mock 은 local/dev, KPST 가 실환경이라 한쪽만 붙이면 발견이 배포 후로 밀린다).
     * 이 서비스의 {@code REQUIRES_NEW} 안에서 불리므로 훅이 스스로 {@code afterCommit} 으로 미룬다.
     */
    private final DeidentReservationHook deidentReservationHook;
    /**
     * 재생 인덱스 재배치 (ADR-072). 완료 전이를 기록한 자리에서 불리며 스스로 {@code afterCommit} 으로
     * 미룬다 — 완료 선점을 얻어 <b>실제로 커밋한 노드</b>에서만 실행되고, 롤백되면 실행되지 않는다.
     * 파일 입출력은 이 트랜잭션 밖(전용 실행기)에서 하며 실패해도 완료 전이를 되돌리지 않는다.
     */
    private final DeidentFaststartService deidentFaststartService;

    /**
     * 폴링 대상 <b>원자 클레임</b> — 이 호출이 {@code true} 를 받은 노드만 해당 위탁 건을 폴링한다
     * (B-ISSUE-82).
     *
     * <p>2노드 Active-Active 이고 Quartz 클러스터링이 기본 꺼져 있어 같은 트리거가 양 노드에서 발화하므로,
     * 대상 선점을 DB 레벨에서 보장해야 한다(설정에 의존하지 않는 방어). 규칙·리스 의미는
     * {@link LsDeidentProcLogRepository#claimForPoll} 참조.
     *
     * <p>REQUIRES_NEW — 폴링 잡은 트랜잭션 밖에서 돌고, 클레임은 즉시 커밋되어야 다른 노드가 관측한다.
     *
     * @param leaseCutoff 이 시각 이후에 폴링된 건은 다시 클레임하지 않는다(= now - 리스 길이)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaimPoll(Long procLogSn, LocalDateTime leaseCutoff) {
        if (procLogSn == null || leaseCutoff == null) {
            return false;
        }
        return procLogRepository.claimForPoll(procLogSn, leaseCutoff, LocalDateTime.now()) == 1;
    }

    /**
     * Phase C-2 — 비동기 제출의 <b>선커밋 원장 발급</b>. 외부 호출 <b>전에</b> 독립 커밋된다.
     *
     * <p>제출이 논블로킹이 되면 ACK/실패 신호가 <b>호출자 트랜잭션이 커밋되기 전에</b> 도착할 수 있다.
     * 원장을 호출자 트랜잭션 안에서 만들면 그 신호를 받은 핸들러가 행을 찾지 못해 위탁 사실이 통째로
     * 유실된다(prjId 미기록 → 폴링 대상 부재 → 영상이 영영 마킹 대기 고착). 그래서 발급을 별도 빈의
     * {@code REQUIRES_NEW} 로 분리해 <b>제출 전에</b> 커밋한다(C-1 VLM 상관키 선커밋과 동형).
     *
     * <p>{@code POLL_STTS=WAITING} 이되 {@code prjId} 는 null 이다 — 폴링 잡이 이 조합을 "ACK 대기"로
     * 해석해 진행조회를 호출하지 않고, 유예를 넘기면 회수(terminal 'F')한다. 이 원장이 없으면
     * 노드 사망 시 in-flight 제출이 아무 흔적 없이 사라진다.
     *
     * @param redeident 검수완료 재비식별 경로면 true (REQ_KIND_CD=REDEIDENT)
     * @return 커밋된 원장(procLogSn 발급됨). 반환 시점에는 detached 다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public LsDeidentProcLog issueSubmitLedger(Long rawSn, String orgnlFilePathNm, boolean redeident) {
        LsDeidentProcLog procLog = LsDeidentProcLog.request(rawSn, null, orgnlFilePathNm, "batch");
        if (redeident) {
            procLog.markRedeident();
        }
        procLog.markKpstSubmitPending();
        return procLogRepository.saveAndFlush(procLog);
    }

    /**
     * Phase C-2 — 제출 ACK(prj_id) 기록. 완료 핸들러({@code KpstSubmitOutcomeRecorder})가 전용 풀
     * 스레드에서 <b>프록시 경유</b>로 호출한다(ambient 트랜잭션 없음 → REQUIRES_NEW 필수).
     *
     * <p>기록은 조건부 원자 UPDATE({@link LsDeidentProcLogRepository#claimSubmitAck})로만 수행한다 —
     * 지각 ACK 가 이미 종결된 원장을 되살리지 못하게 하고(부활 금지), 2노드 중복 신호에도 1행만 성립한다.
     *
     * @return 실제로 기록됐으면 true. false 는 "이미 종결/기록됨"(정상, 무시)이다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean recordSubmitAck(Long procLogSn, Long rawSn, Long prjId) {
        if (procLogSn == null || prjId == null) {
            return false;
        }
        if (procLogRepository.claimSubmitAck(procLogSn, prjId, LocalDateTime.now()) != 1) {
            log.info("[KpstDeid] submit ack ignored (already settled) rawSn={} prjId={}", rawSn, prjId);
            return false;
        }
        log.info("[KpstDeid] submitted rawSn={} prjId={}", rawSn, prjId);
        return true;
    }

    /**
     * M3 — 제출이 <b>개시되지도 않은</b> 건의 원장 취소 종결(호출자 트랜잭션 롤백).
     *
     * <h3>왜 {@link #failSubmit} 를 쓰지 않는가</h3>
     * <p>{@code failSubmit} 은 영상을 {@code DE_IDNTF_YN='F'} 로 내린다. 그런데 이 경로는 <b>외부로
     * 아무것도 나가지 않은</b> 상태다 — 요청 트랜잭션이 롤백돼 위탁 구독 자체가 일어나지 않았다.
     * 여기서 'F' 를 찍으면 실패한 요청이 그 영상을 신고 게이트(라벨 조회 412 · 스트리밍 404 ·
     * export 보류)에 밀어 넣는다. 그래서 <b>원장만</b> terminal 로 닫아 폴링 대상에서 제외하고
     * 영상 상태는 건드리지 않는다(재비식별 작업락 INSERT 도 같은 롤백으로 사라졌다). 단 선두 비식별
     * 재시작이 요청 트랜잭션에서 미리 커밋한 잠금은 이 종결에서 한정 해제한다(AC-1135).
     *
     * <p>종결은 {@code claimSubmitFailure}(WAITING + prjId null) 조건부 UPDATE 라, 만에 하나 ACK 가
     * 먼저 기록된 건이면 0행 no-op 이다(진행 중 위탁을 취소하지 않는다).
     *
     * @return 실제로 취소 종결했으면 true
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean cancelSubmit(Long procLogSn, Long rawSn) {
        if (procLogSn == null) {
            return false;
        }
        int applied = procLogRepository.claimSubmitFailure(procLogSn,
                KpstDeidentService.SUBMIT_CANCELED_CODE, "caller transaction rolled back",
                LocalDateTime.now());
        if (applied != 1) {
            log.info("[KpstDeid] submit cancel ignored (already settled) rawSn={}", rawSn);
            return false;
        }
        // AC-1135 — 취소 종결도 선두 비식별 재시작의 종결이다. 영상 상태는 그대로 두되 그 재시작이 잡은
        //   잠금은 푼다(재비식별 잠금은 같은 롤백으로 이미 사라졌고, 이 해제는 그 잠금을 건드리지 않는다).
        releaseDeidentRetryLock(rawSn, "DEIDENT_RETRY_CANCELED");
        log.warn("[KpstDeid] submit canceled — ledger closed without deident failure rawSn={}", rawSn);
        return true;
    }

    /**
     * Phase C-2 — 제출 <b>확정 실패</b> 종결. 원장 FAILED + 영상 {@code DE_IDNTF_YN='F'} + (재비식별이면)
     * 작업락 해제를 <b>하나의 REQUIRES_NEW 로 커밋</b>한다.
     *
     * <p>왜 별도 빈·별도 트랜잭션인가: ①실패는 비동기 완료 핸들러(전용 풀, ambient tx 없음) 또는 호출자
     * 트랜잭션이 곧 롤백될 사전조건 실패 경로에서 발생한다 — 어느 쪽이든 호출자와 운명을 묶으면 실패
     * 흔적이 함께 사라진다(구 {@code submit} 의 catch 블록이 정확히 그랬다: 'F' 마킹이 REQUIRES_NEW
     * 롤백으로 취소돼 영상이 흔적 없이 PENDING 에 고착). ②이 레포에는 자기호출로 트랜잭션 경계가
     * 유실된 실사고 이력이 있어 프록시 경유가 강제되는 구조로 둔다.
     *
     * <p><b>상태 강등 금지</b>: 종결은 {@link LsDeidentProcLogRepository#claimSubmitFailure}(WAITING +
     * prjId null)로만 성립한다. ACK 를 이미 받았거나 폴링이 완료시킨 건에 지각 실패가 도착하면 0행 →
     * 영상 상태를 건드리지 않는다.
     *
     * @param errorCd 실패 코드 — 제출 실패/ACK 미수신을 운영에서 구분하기 위해 호출자가 지정
     * @return 실제로 종결했으면 true
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean failSubmit(Long procLogSn, Long rawSn, String errorCd, String errorDetail) {
        if (procLogSn == null) {
            return false;
        }
        if (procLogRepository.claimSubmitFailure(procLogSn, errorCd, errorDetail, LocalDateTime.now()) != 1) {
            log.info("[KpstDeid] submit failure ignored (already settled) rawSn={} errCd={}", rawSn, errorCd);
            return false;
        }
        videoRepository.findById(rawSn).ifPresent(v -> v.markDeidentified("F"));
        // 재비식별(REDEIDENT)은 요청 시 작업락을 잡는다 — 위탁이 실패로 끝나면 해제해야 재요청이 가능하다
        // (해제하지 않으면 409 영구 차단). 배치 경로는 선두 비식별 재시작이 잡은 잠금만 있을 수 있어
        // 아래 한정 해제가 맡는다(적재 직후 자동 실행은 잠금이 없어 무영향). ACK 유예 만료 회수도 이 지점이다.
        releaseDeidentRetryLock(rawSn, "DEIDENT_RETRY_SUBMIT_FAILED");
        boolean redeident = procLogRepository.findById(procLogSn)
                .map(LsDeidentProcLog::isRedeident)
                .orElse(false);
        if (redeident && workLockService.isRawLocked(rawSn)) {
            workLockService.releaseRaw(rawSn, "batch", "REDEIDENT_SUBMIT_FAILED");
        }
        // ADR-052 / AC-1033 — 제출이 확정 실패로 종결됐다. 예약 마킹을 적용하지 못한 채 마감한다
        //   (적재는 되돌리지 않는다 — 재마킹 가능 상태로 남긴다).
        deidentReservationHook.closeAfterCommit(rawSn, DeidentReservationHook.REASON_DEIDENT_FAILED);
        log.warn("[KpstDeid] submit terminal-failed rawSn={} errCd={} detail={} redeident={}",
                rawSn, errorCd, errorDetail, redeident);
        return true;
    }

    /**
     * 다운로드 완료 + 비식별 완료(Y 전이)를 단일 REQUIRES_NEW 트랜잭션으로 원자화 — DEV_FIX HIGH/MEDIUM(M-1).
     *
     * <p>기존 2분리 트랜잭션(DOWNLOADED → Y) 은
     * 사이 크래시 시 DOWNLOADED 이나 Y 미전이인 영구 stuck 행을 남겼다(재폴링 대상도 아님). 본 메서드는
     * procLog DOWNLOADED/SUCCEEDED 전이와 raw Y/MARKING_READY 전이를 한 트랜잭션에 묶어 stuck 창을 제거한다.
     * 비식별 파일 무결성(정규파일 + 크기 하한 + 컨테이너 시그니처) 검증을 선행하므로 불완전/위장
     * 산출물은 Y 로 가지 않는다({@link #verifyDeidFile}).
     *
     * <p><b>멱등 가드(B-ISSUE-82)</b>: 완료 전이를 조건부 UPDATE
     * ({@link LsDeidentProcLogRepository#claimDownloadCompletion})로 <b>선점</b>한 호출만 후처리로
     * 진행한다. 두 노드가 같은 건의 완료를 동시에 커밋하려 하면 뒤에 온 쪽은 0행을 받아 즉시 반환하므로
     * 비식별 프레임 재추출(attach)·알림·락 해제가 두 번 수행되지 않는다. 같은 이유로 <b>같은 인자의
     * 재호출도 no-op</b> 이다(폴링 재시도·중복 콜백 안전).
     *
     * <p><b>잠금 순서</b>: LS_DEIDENT_PROC_LOG → LS_DATA_RAW. 역순(RAW 선점 후 이 procLog 행 갱신)
     * 경로는 없다 — 재비식별 위탁({@code ApprovedRedeidentService})은 RAW 를 잡은 채 <b>새 행을 INSERT</b>
     * 할 뿐이고, 신고 해제({@code DeidentReportService})는 procLog 를 <b>읽기만</b> 한다(MVCC 라 대기 없음).
     * 따라서 사이클이 없다. RAW 다중 행을 잠그지 않으므로 "조상→자손" 불변식과도 충돌하지 않는다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void finishDownloadAndComplete(Long rawSn, Long procLogSn, Long datasetId, String deidFilePath) {
        finishDownloadAndComplete(rawSn, procLogSn, datasetId, deidFilePath, null);
    }

    /**
     * 위와 동일하되 <b>처리 결과 리포트 요약</b>을 같은 트랜잭션에 함께 적재한다. [req: R14]
     *
     * <p>리포트를 별도 트랜잭션으로 나중에 쓰지 않는 이유: 그 사이 크래시하면 "완료됐는데 집계는
     * 없는" 행이 남고, 그 행을 다시 채울 트리거가 없다(폴링 대상에서 이미 빠졌다). 완료 전이와
     * 한 트랜잭션에 묶으면 <b>둘 다 커밋되거나 둘 다 롤백</b>된다.
     *
     * <p>{@code report} 가 {@code null} 이면 리포트 컬럼은 건드리지 않는다 — 조회 실패는 정상 경로이며
     * 완료 전이를 막지 않는다({@code KpstDeidentService.fetchReportQuietly}).
     *
     * <p><b>중복 완료(클레임 0행)면 리포트도 적재하지 않는다</b>: 이미 다른 노드가 완료를 적용했고
     * 그쪽도 자기 리포트를 실었다. 여기서 덮어쓰면 클레임이 보장한 단일 기록자 원칙이 깨진다.
     *
     * <p>구 4-인자 시그니처는 그대로 유지된다(기존 호출자·테스트 무영향). 위 오버로드가 본 메서드를
     * 자기호출하지만 이미 {@code REQUIRES_NEW} 트랜잭션 안이므로 <b>같은 트랜잭션</b>에서 수행되며,
     * 이는 의도된 동작이다(중첩 신규 트랜잭션을 만들지 않는다).
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void finishDownloadAndComplete(Long rawSn, Long procLogSn, Long datasetId, String deidFilePath,
                                          KpstDeidentReportSummary report) {
        verifyDeidFile(rawSn, deidFilePath);
        if (procLogRepository.claimDownloadCompletion(procLogSn, deidFilePath, LocalDateTime.now()) != 1) {
            // 이미 다른 노드(또는 앞선 호출)가 완료를 적용했다 — 중복 후처리 금지.
            log.info("[KpstDeid] completion already applied — skip duplicate rawSn={}", rawSn);
            return;
        }
        LsDeidentProcLog procLog = procLogRepository.findById(procLogSn).orElse(null);
        boolean redeident = procLog != null && procLog.isRedeident();
        if (procLog != null) {
            procLog.recordDatasetId(datasetId);
            // 클레임 UPDATE 와 동일 값을 엔티티에도 반영해 영속 컨텍스트/DB 를 일치시킨다(재수렴).
            procLog.markDownloaded(deidFilePath);
            // R14 — 리포트 집계값(null 이면 no-op). dirty checking 으로 완료 전이와 함께 커밋된다.
            procLog.recordReport(report);
        }
        // REQ_KIND 분기 — REDEIDENT 는 검수완료(APPROVED) 유지 + 프레임 attach, 기존 BATCH 는 현행 유지.
        applyCompletion(rawSn, deidFilePath, redeident);
    }

    /**
     * 폴링 건 'F' 처리 — 불완전 산출물(0바이트 등) 다운로드 실패 시 POLL_STTS 종료값 전이 + raw 'F' 마킹.
     * 재폴링 대상에서 제외하며 Y 전이는 수행하지 않는다(DEV_FIX HIGH 방어).
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void failPolling(Long procLogSn, Long rawSn) {
        procLogRepository.findById(procLogSn)
                .ifPresent(p -> p.fail("DEIDENT_INCOMPLETE", "deid file invalid"));
        videoRepository.findById(rawSn).ifPresent(v -> v.markDeidentified("F"));
        // AC-1135 — 선두 비식별 재시작이 잡은 잠금만 푼다(그 밖의 잠금은 이 경로에 없고, 있어도 건드리지 않는다).
        releaseDeidentRetryLock(rawSn, "DEIDENT_RETRY_POLL_FAILED");
        // ADR-052 / AC-1033 — 폴링이 terminal 실패로 종결됐다. 예약 마킹을 마감한다(적재는 유지).
        deidentReservationHook.closeAfterCommit(rawSn, DeidentReservationHook.REASON_DEIDENT_FAILED);
        log.warn("[KpstDeid] poll incomplete download rawSn={}", rawSn);
    }

    /**
     * REDEIDENT 완료 후처리 실패 종결 — DEV_FIX HIGH(결함1·결함2·M-1).
     *
     * <p>완료 감지 후 프레임 attach/검증 실패(해상도 불일치 등)는 KPST 비식별 자체는 성공했고 우리측
     * 후처리만 실패한 경우다. 재다운로드·재attach 를 반복해도 같은 입력이라 무의미하므로 <b>즉시 terminal
     * 종결</b>한다. 본 메서드는 메인 완료 트랜잭션({@link #finishDownloadAndComplete})이 롤백된 뒤 별도
     * {@code REQUIRES_NEW} 로 cross-bean 호출되어, 아래를 실제 커밋한다(롤백 분리).
     *
     * <ul>
     *   <li>procLog → FAILED + POLL_FAILED(terminal) — {@code findByPollSttsCdIn([WAITING,POLLING])}
     *       에서 제외되어 무한 재폴링이 멈춘다(결함2).</li>
     *   <li>de_ident_yn → 'F' — Y 미전이(라벨/검수상태는 attach 가 롤백되어 불변, APPROVED 유지).</li>
     *   <li>작업락 해제(있을 때만) — 재요청이 가능해진다(결함1, fail-closed→재처리 허용).</li>
     * </ul>
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void failRedeidentCompletion(Long procLogSn, Long rawSn, String errType) {
        procLogRepository.findById(procLogSn)
                .ifPresent(p -> p.fail("REDEIDENT_ATTACH_FAILED", errType));
        videoRepository.findById(rawSn).ifPresent(v -> v.markDeidentified("F"));
        if (workLockService.isRawLocked(rawSn)) {
            workLockService.releaseRaw(rawSn, "batch", "REDEIDENT_FAILED");
        }
        log.warn("[KpstDeid] redeident completion failed rawSn={} errType={} — terminal FAILED, lock released",
                rawSn, errType);
    }

    /**
     * raw 'F' 마킹만 별도 커밋 — M-1 보정. {@link #completeDeidentification} 내부 {@code verifyDeidFile}
     * 의 F-마킹은 예외 전파 시 같은 REQUIRES_NEW 트랜잭션이 롤백되어 취소된다. 비-REDEIDENT 경로의 파일
     * 무효 실패에서 F-마킹이 실제 커밋되도록 본 메서드(별도 REQUIRES_NEW)로 보정한다(라벨/상태 불변).
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markRawDeidentFailed(Long rawSn) {
        videoRepository.findById(rawSn).ifPresent(v -> v.markDeidentified("F"));
        // AC-1135 — 산출물 무효 확정 실패도 선두 비식별 재시작의 종결이다.
        releaseDeidentRetryLock(rawSn, "DEIDENT_RETRY_ARTIFACT_INVALID");
        // ADR-052 / AC-1033 — 산출물 무효로 Y 전이가 막힌 확정 실패의 보정 커밋 지점이다. 예약 마킹을
        //   함께 마감한다(적재는 유지 — 재마킹 가능).
        deidentReservationHook.closeAfterCommit(rawSn, DeidentReservationHook.REASON_DEIDENT_FAILED);
        log.warn("[KpstDeid] mark raw deident F rawSn={}", rawSn);
    }

    /** 진행중 — datasetId 보충 + 시도 증가. */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordPollingProgress(Long procLogSn, Long datasetId) {
        procLogRepository.findById(procLogSn).ifPresent(p -> {
            p.recordDatasetId(datasetId);
            p.markPolling();
        });
    }

    /**
     * 타임아웃 판정 — 시도 횟수 초과 또는 위탁 후 경과 시간 초과 시 'F' 마킹(무한 폴링 방지).
     * @return 타임아웃으로 처리되어 'F' 마킹되면 true.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean markTimeoutIfExpired(Long procLogSn, int maxAttempts, long timeoutMinutes) {
        LsDeidentProcLog procLog = procLogRepository.findById(procLogSn).orElse(null);
        if (procLog == null || LsDeidentProcLog.POLL_DOWNLOADED.equals(procLog.getPollSttsCd())) {
            return false;
        }
        boolean attemptExceeded = procLog.getPollAttemptCnt() != null
                && procLog.getPollAttemptCnt() >= maxAttempts;
        boolean elapsedExceeded = procLog.getReqDt() != null
                && procLog.getReqDt().plusMinutes(timeoutMinutes).isBefore(LocalDateTime.now());
        if (!attemptExceeded && !elapsedExceeded) {
            return false;
        }
        Long rawSn = procLog.getDataRawSn();
        procLog.fail("DEIDENT_TIMEOUT", "polling timeout");
        videoRepository.findById(rawSn).ifPresent(v -> v.markDeidentified("F"));
        // DEV_FIX HIGH-2: REDEIDENT 건이 끝내 procState=2 미도달(타임아웃)이면 위탁 시 잡은 작업락이 영구 잔존
        // 한다(완료/실패 분기를 안 타므로). 타임아웃 'F' 마킹 시 락 보유면 해제하여 재요청을 허용한다.
        // 비-REDEIDENT(배치)는 선두 비식별 재시작이 잡은 잠금만 있을 수 있어 아래 한정 해제가 맡는다.
        if (procLog.isRedeident() && workLockService.isRawLocked(rawSn)) {
            workLockService.releaseRaw(rawSn, "batch", "REDEIDENT_TIMEOUT");
        }
        // AC-1135 — 처리 시한 초과 종결.
        releaseDeidentRetryLock(rawSn, "DEIDENT_RETRY_TIMEOUT");
        // ADR-052 / AC-1033 — 타임아웃은 이 위탁 건의 종결이다(자동 재비식별 큐가 없어 스스로 되살아나지
        //   않는다). 예약 마킹을 마감해 사람이 그 영상을 다시 마킹할 수 있게 한다(적재는 유지).
        deidentReservationHook.closeAfterCommit(rawSn, DeidentReservationHook.REASON_DEIDENT_FAILED);
        log.warn("[KpstDeid] poll timeout rawSn={} attempts={} redeident={}",
                rawSn, procLog.getPollAttemptCnt(), procLog.isRedeident());
        return true;
    }

    /**
     * 비식별 완료 처리(공유) — 콜백/폴링 양 경로가 호출한다.
     *
     * <p>DE_IDNTF_YN='Y' → MARKING_READY → 작업락 해제 → OPEN 신고 RESOLVED → REVIEWER 알림.
     *
     * <p>Y 전이 전 비식별 파일 실재(존재 + >0바이트)를 재검증한다(DEV_FIX HIGH 방어/M-2 — defense-in-depth).
     * 미존재/0바이트 등 불완전 산출물이면 raw 를 'F' 마킹하고 예외를 던져 Y 전이를 차단한다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void completeDeidentification(Long rawSn, String deidFilePath) {
        verifyDeidFile(rawSn, deidFilePath);
        // 콜백 경로는 procLogSn 미상 — rawSn 기준 최신 procLog 의 REQ_KIND 로 분기.
        boolean redeident = procLogRepository.findAllByDataRawSnOrderByReqDtDesc(rawSn).stream()
                .findFirst()
                .map(LsDeidentProcLog::isRedeident)
                .orElse(false);
        applyCompletion(rawSn, deidFilePath, redeident);
    }

    /** REQ_KIND 분기 — REDEIDENT 면 검수완료 유지 경로, 아니면 기존 배치 완료 경로. */
    private void applyCompletion(Long rawSn, String deidFilePath, boolean redeident) {
        if (redeident) {
            applyRedeidentCompletion(rawSn, deidFilePath);
        } else {
            applyBatchCompletion(rawSn, deidFilePath);
        }
    }

    /**
     * 기존 배치 비식별 완료 경로 — Y/MARKING_READY/<b>승인보류 해제</b>/락해제/신고해소/알림 적용.
     *
     * <p>승인보류 해제 외에는 현행 무변경(회귀 금지). 해제는 이 저장소에서 <b>비식별 성공</b>을 뜻하는
     * 그 지점({@code DE_IDENT_YN='Y'} + 배치 단계 {@code MARKING_READY})에 붙어 있어 두 값이 갈리지
     * 않는다. 이 경로와 무관한 영상에서는 보류가 애초에 서 있지 않아 no-op 이다
     * ({@link DeidentApprovalHoldReleaser}).
     *
     * <p>완료가 커밋된 뒤 산출물의 재생 인덱스 재배치를 넘긴다(ADR-072) — 경로·파일명·완료 상태는 불변이다.
     *
     * @design ADR-048
     * @design AC-1063
     * @design AC-1064
     * @design ADR-052
     * @design SEQ-030
     * @design ADR-072
     */
    private void applyBatchCompletion(Long rawSn, String deidFilePath) {
        LsDataRaw managed = videoRepository.findById(rawSn).orElse(null);
        if (managed == null) {
            log.warn("[KpstDeid] raw not found rawSn={} (complete) — skip", rawSn);
            return;
        }
        managed.markDeidentified("Y");
        managed.markMarkingReady();
        // ADR-048 — 저작도구가 태운 비식별이 <b>성공으로 기록된</b> 바로 이 지점에서 검수 승인 보류를
        //   푼다. 외부 산출물을 원본이라고 지정해 이관하면서 영상 파일을 함께 준 영상만 보류가 서 있고
        //   (LS_RAW_DATA_STATUS.DE_IDNTF_CMPTN_YN='N'), 나머지 전 영상은 기본값이 완료라 no-op 이다.
        //   ★ 반드시 위 두 줄(DE_IDENT_YN='Y' + MARKING_READY) <b>뒤</b>이며 같은 트랜잭션이어야 한다.
        //     이 메서드 진입 자체가 verifyDeidFile(산출물 무결성)을 통과한 뒤이고, 해제기는 raw 가
        //     'Y' 인지 한 번 더 확인한다 — 산출물 없이 완료로 응답하는 거짓 성공에서는 그 앞 게이트가
        //     예외를 던져 여기까지 오지 않는다.
        deidentApprovalHoldReleaser.releaseOnDeidentSuccess(managed);
        if (workLockService.isRawLocked(rawSn)) {
            workLockService.releaseRaw(rawSn, "batch", "DEIDENT_SUCCEEDED");
        }
        if (deidentReportService != null) {
            deidentReportService.resolveOpenReports(rawSn);
        }
        if (notificationService != null) {
            notificationService.notifyReviewersOnLockRelease(managed);
        }
        // 스트림 메타 캐시 무효화 — 규약(CacheConfig javadoc "배치 비식별 완료") 준수 + defense-in-depth.
        // [정직한 도달성] 현재 코드 기준 이 evict 는 <b>도달 가능한 모든 흐름에서 no-op</b> 이다:
        //   ①stream-meta 는 비식별 완료(Y + SUCCEEDED procLog + 파일 실재) 후에만 적재되므로 최초 배치
        //     완료 시점에는 해당 rawSn 캐시 엔트리가 없다.
        //   ②이미 'Y' 인 영상의 배치 재위탁 경로가 없다 — 배치 procLog 는 DeidentifyStep(신규 적재/dev
        //     업로드=새 rawSn)에서만 생기고, 재비식별(REDEIDENT)은 ApprovedRedeidentService 가 'Y' 를
        //     409 로 거부한다(ApprovedRedeidentService:83-85, 해당 테스트로 고정).
        // 즉 "재구동/재위탁으로 경로가 바뀐다"는 구 주석의 근거는 사실이 아니었다(삭제).
        // 그럼에도 호출을 남기는 이유: 캐시 미스 시 evict 는 맵 조회 1회로 비용이 없고, 나중에 재드라이브
        // 경로가 생겼을 때 무효화 누락(privacy/Range 회귀)이 조용히 재발하는 쪽이 훨씬 비싸다. 반대로
        // "배치 완료는 evict 하지 않는다"를 테스트로 고정하면 그 안전한 동작을 미래에 금지하게 된다.
        streamMetaCacheEvictor.evictAfterCommit(rawSn);
        // ADR-052 — 외부 마킹 적재 경로가 담아 둔 예약 마킹을 깨운다. 이 메서드는 REQUIRES_NEW 안이라
        //   아직 커밋 전이므로 훅이 afterCommit 으로 미룬다 — 지금 바로 활성화하면 MarkingBatchBridge 가
        //   AFTER_COMMIT 으로 영상 행을 다시 읽을 때 위 두 줄(Y + MARKING_READY)이 보이지 않아 skip 으로
        //   판정하고, 그 skip 이 방금 깨운 마킹을 SKIPPED 로 종결시킨다.
        //   ★ 재비식별(REDEIDENT) 경로에는 붙이지 않는다 — 그쪽은 APPROVED 유지 경로라 마킹 단계로의
        //     재진입을 만들지 않으며(R1), 승인된 영상에 예약이 존재할 수 없다.
        deidentReservationHook.activateAfterCommit(rawSn);
        // ADR-072 — 커밋 이후 재생 인덱스 재배치(인덱스가 이미 앞이거나 판정 불가면 무동작, 실패는 fail-open).
        deidentFaststartService.scheduleAfterCommit(rawSn, managed.getRawFilePathNm(), deidFilePath);
        log.info("[KpstDeid] completed rawSn={}", rawSn);
    }

    /**
     * 검수완료(APPROVED) 영상 재비식별 완료 경로 (Phase 3 / R1 강등 금지) — 비식별 프레임 attach +
     * DE_IDNTF_YN='Y' + PRVC 정정 + 락해제만 수행한다.
     *
     * <p><b>R1(APPROVED 강등 금지)</b>: {@code markMarkingReady()}/상태머신 전이/MarkingCompletedEvent/
     * BatchOrchestrator 를 절대 호출하지 않는다. LS_RAW_DATA_STATUS(검수 워크플로우 상태)는 건드리지 않아
     * APPROVED 가 유지된다. 마킹 단계로의 재진입을 만들지 않으므로 검수 완료 작업이 라벨링/마킹으로
     * 되돌아가지 않는다.
     *
     * <p>{@link DeidentFrameAttacher} 가 해상도 불일치/추출 실패 시 예외를 던지면 그대로 전파되어 본
     * REQUIRES_NEW 트랜잭션 전체가 롤백된다 → DE_IDNTF_YN 미변경, 라벨/검수상태(APPROVED) 불변.
     * 롤백된 예외는 폴링 오케스트레이터({@link KpstDeidentService#pollOne})가 받아
     * {@link #failRedeidentCompletion}(별도 REQUIRES_NEW)로 terminal 종결한다 — procLog FAILED + 락 해제로
     * 무한 재폴링/영구 잠금을 차단한다(DEV_FIX 결함1·결함2). 따라서 성공 시에만 여기서 락을 해제한다.
     */
    private void applyRedeidentCompletion(Long rawSn, String deidFilePath) {
        LsDataRaw managed = videoRepository.findById(rawSn).orElse(null);
        if (managed == null) {
            log.warn("[KpstDeid] raw not found rawSn={} (redeident) — skip", rawSn);
            return;
        }
        // 1) 비식별 프레임 attach — 재비식별(SC-009)은 refreshExisting=true 로 기존 비식별 프레임을 강제
        //    재추출해 새 비식별본으로 교체한다(개인정보 누락 프레임 교체). 초기 파이프라인이 이미 모든 프레임에
        //    deident 경로를 설정해두므로 멱등 skip 이면 갱신이 무력화된다. 해상도 불일치 시 예외 전파(전체 롤백).
        //    라벨 보존(같은 SRC 행 갱신).
        int attached = deidentFrameAttacher.attachDeidentFrames(managed, Paths.get(deidFilePath), true);
        // 1-1) 정합 신호 — 재비식별은 프레임이 있어야 정상이다. 0건이면 데이터 정합 확인이 필요한
        //      비정상 신호로 WARN(예외로 막지는 않는다 — frames 자체가 없는 영상도 있을 수 있음).
        if (attached == 0) {
            log.warn("[KpstDeid] redeident attached 0 frames rawSn={} — 재비식별 프레임 0건, 데이터 정합 확인 필요", rawSn);
        }
        // 2) 비식별 완료 마킹.
        managed.markDeidentified("Y");
        // 3) PRVC 정정 — UNKNOWN(미상)이면 PRVC 로 확정.
        managed.correctPrvcTypeIfUnknown();
        // 4) 작업락 해제(있을 때만). MARKING_READY/상태전이/알림은 호출하지 않는다(APPROVED 유지).
        if (workLockService.isRawLocked(rawSn)) {
            workLockService.releaseRaw(rawSn, "batch", "REDEIDENT_SUCCEEDED");
        }
        // 5) 스트림 메타 캐시 무효화 (HIGH — 무결성/privacy) — 재비식별로 비식별본이 교체(동일 경로
        //    in-place 교체 시 옛 contentLength 로 Range 경계 오류·재생 잘림 가능)되었으므로 커밋 후 무효화.
        streamMetaCacheEvictor.evictAfterCommit(rawSn);
        // 6) ADR-072 — 재비식별 회차도 같은 완료 경로라 커밋 이후 재생 인덱스 재배치를 넘긴다
        //    (프레임 attach 는 위에서 이미 끝났다 — 재배치는 영상 내용을 바꾸지 않는다).
        deidentFaststartService.scheduleAfterCommit(rawSn, managed.getRawFilePathNm(), deidFilePath);
        log.info("[KpstDeid] redeident completed rawSn={}", rawSn);
    }

    /**
     * 비식별 산출물 무결성 검증(CWE-459/CWE-345 방어) — 입력 유효성 + 정규파일 + 크기 하한 +
     * 컨테이너 시그니처({@link DeidentArtifactIntegrity}).
     * 위반 시 raw 를 'F' 마킹하고 예외를 던져 Y 전이를 막는다(불완전/위장 비식별 차단).
     *
     * <p><b>Y 전이 직전의 마지막 게이트</b>다. 폴링 경로는 이미 {@code KpstDeidentService.isUsableDeidFile}
     * 로 걸러지지만, 콜백 경로({@code completeDeidentification})는 여기만 통과하면 'Y' 가 되므로 판정을
     * 동일 단일 지점에 위임해 우회로를 남기지 않는다(B-ISSUE-01).
     */
    private void verifyDeidFile(Long rawSn, String deidFilePath) {
        if (rawSn == null || deidFilePath == null || deidFilePath.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn/deidFilePath 는 필수입니다.");
        }
        if (!DeidentArtifactIntegrity.isValidVideoArtifact(deidFilePath)) {
            videoRepository.findById(rawSn).ifPresent(v -> v.markDeidentified("F"));
            log.warn("[KpstDeid] deid file invalid rawSn={} — mark F", rawSn);
            throw new CustomException(ErrorCode.INVALID_INPUT, "비식별 산출물이 유효하지 않습니다.");
        }
    }

    /**
     * 선두 비식별 재시작(배치 재시작)이 잡은 작업 잠금만 해제한다 — 본 서비스의 트랜잭션에 합류한다.
     * 트랙 병합·검수완료 재비식별이 잡은 잠금은 풀지 않는다. 잠금이 없으면 no-op.
     *
     * @design AC-1135
     */
    private void releaseDeidentRetryLock(Long rawSn, String reason) {
        workLockService.releaseDeidentRetryLock(rawSn, "batch", reason);
    }

    @Transactional(value = "controlTransactionManager", readOnly = true,
            propagation = Propagation.REQUIRES_NEW)
    public Optional<LsDeidentProcLog> refresh(Long procLogSn) {
        return procLogRepository.findById(procLogSn);
    }
}
