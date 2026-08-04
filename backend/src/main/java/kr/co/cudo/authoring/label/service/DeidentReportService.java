package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.cache.StreamMetaCacheEvictor;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.storage.DeidentArtifactIntegrity;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.label.event.DeidentGateReopenedEvent;
import kr.co.cudo.authoring.label.event.DeidentReportResolvedEvent;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Phase 2 (R1 v1.14) — 비식별 누락 신고 워크플로우 서비스.
 *
 * <p>R1 v1.14 정합 변경:
 * <ul>
 *   <li>{@link #report} — <b>라벨을 삭제하지 않는다(2026-07-27 정책 반전, 사용자 확정)</b>. 신고는
 *       "비식별이 잘못됐다"는 신호이므로 라벨 작업 결과는 <b>보존</b>하고, 신고~재비식별 구간의 PII
 *       노출은 라벨 조회 게이트({@link LabelAccessGuard#requireNotUnderDeidentReport}, S7)로 차단한다.
 *       resolve 로 DE_IDNTF_YN 이 'F'→'Y' 복원되면 게이트가 열려 보존된 라벨을 그대로 재사용한다.
 *       구 동작(LS_LABEL_VERSION SAVE_REASON='DEIDENT_REPORT' 비활성 스냅샷 + 전량 삭제 + 삭제 이력)은
 *       폐기됐다 — 그 스냅샷은 DATA_SRC_SN=NULL 이라 복원 진입점이 없는 write-only 이력이었다(D-ISSUE-25).
 *       자동 재비식별 큐 적재는 제거되었고(외부 솔루션 수동 비식별화로 대체) 영상 잠금 + DE_IDNTF_YN='F'
 *       + 개인정보 3필드 리셋은 유지.</li>
 *   <li>{@link #resolveManually} — 외부 솔루션 수동 비식별화 완료 후 OPEN→RESOLVED 전이 + 작업락 해제.</li>
 *   <li>{@link #resolveOpenReports} — DeidentifyStep(배치 자동 비식별) 성공 시 OPEN 신고 일괄 RESOLVED.
 *       (TODO: 수동 resolveManually 와 동시 호출 시 경쟁 가능 — 둘 다 멱등 처리되어 데이터 정합은 유지되나,
 *        장기적으로 자동 경로 일원화 검토.)</li>
 * </ul>
 *
 * <p>보안:
 * <ul>
 *   <li><b>IDOR (CWE-639)</b>: report 는 LabelAccessGuard(srcSn), resolveManually 는 verifyRawAccess(rawSn) 로
 *       WORKER 본인 배정 영상만 통과. REVIEWER 는 전체 허용.</li>
 *   <li><b>Race (CWE-362)</b>: 동시 신고 시 WorkLock UNIQUE 제약이 최후 방어 —
 *       {@link DataIntegrityViolationException} 을 409 CONFLICT 로 변환.</li>
 *   <li><b>Privacy (CWE-359) / Log Injection (CWE-117)</b>: 신고 사유(reason)는 사용자 자유 입력이라 PII 가
 *       섞일 수 있으나, <b>발견 사실이 유실되면 안 되므로 정제 후 감사 로그로 남긴다</b>(미출력 아님).
 *       ① 정상 접수 경로 — {@code NotificationService.notifyReviewersOnDeidentReport} 구현체
 *       ({@code LogNotificationService})가 개행/탭 치환 + 200자 절단 후 INFO 로 기록.
 *       ② 파생영상 거부 경로 — {@link #requireReportableVideo} 가
 *       {@link kr.co.cudo.authoring.common.util.LogSanitizer} 로 제어문자 제거 + 200자 절단 후 WARN 으로 기록
 *       (신고 행이 생성되지 않는 경로라 로그가 유일한 기록이다).
 *       ※ 로그 대상은 <b>사유 텍스트뿐</b> — 프레임 픽셀·경로 등 다른 PII 원본은 로그에 싣지 않는다.</li>
 *   <li><b>SQL Injection (CWE-89)</b>: 리셋/조회는 JPA 파라미터 바인딩 @Modifying 쿼리만 사용.</li>
 * </ul>
 *
 * <p>신고 저장 + 개인정보 리셋 + 작업락 + 'F' 전이는 단일 트랜잭션 — 부분 실패 시 전체 롤백.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager")
public class DeidentReportService {

    private final LabelAccessGuard accessGuard;
    private final VideoRepository videoRepository;
    private final LsDeidentReportRepository reportRepository;
    private final NotificationService notificationService;
    private final WorkLockService workLockService;
    private final kr.co.cudo.authoring.batch.repository.LsDataSrcRepository srcRepository;
    private final LsRawDataStatusRepository rawDataStatusRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final StreamMetaCacheEvictor streamMetaCacheEvictor;
    private final LsDeidentProcLogRepository procLogRepository;
    /** DEV_FIX-B(M5) — <b>프레임 축</b> 개인정보 3필드 리셋의 행 단위 감사(신규 테이블 없음). */
    private final LsDataLblHstryRepository lblHstryRepository;
    /** DEV_FIX 2차 — <b>영상 축</b> 개인정보 3필드 리셋의 행 단위 감사(rawSn 스코프 이력 축 재사용). */
    private final LsTaskEventLogRepository taskEventLogRepository;

    /**
     * 비식별 누락 신고 등록 (R1 v1.14).
     *
     * <p>흐름: 권한검사 → 영상로드 → <b>파생영상 거부</b>({@link #requireReportableVideo})
     *        → <b>비식별 미수행 거부</b>({@link #requireDeidentAttempted}) → 잠금 선점검
     *        → 신고 OPEN 저장 → 개인정보 3필드 리셋(<b>프레임 축 + 영상 축 모두</b>)
     *        → APPROVED 면 TASK_MODIFIED 통지 → 작업락 + DE_IDNTF_YN='F' → REVIEWER 알림.
     *        <b>라벨은 삭제하지 않는다</b>(2026-07-27 정책 반전 — 클래스 javadoc 참조).
     *
     * <p><b>파생 프레임 개인정보 cross-stale 경계(후속 백로그)</b>: 아래 개인정보 3필드 리셋
     * ({@code resetPrivacyMetaByRawSn})은 <b>신고 대상 rawSn 의 프레임만</b> NULL 로 되돌린다. 이 영상을
     * 부모로 이미 생성된 증강·해상도 파생본(다른 rawSn)에 생성 시점 복사된 3필드 값은 리셋하지 않는다.
     * 이는 의도된 아키텍처 경계다 — 신규 파생은 생성 시점 stale-PII 게이트로 방어하고, <b>기존 파생은 원본
     * 신고와 무관하게 독립 취급</b>한다(파생영상은 신고 접수 자체가 412 로 거부된다 —
     * {@link #requireReportableVideo}). 부모→기존 파생 캐스케이드 리셋은 미지원이며, 파생본의 stale 3필드는
     * 파생본 자체의 라벨링·검수에서 수동 정정한다. 상세는 v2-wiki 24-dataset-export.md 참조.
     *
     * @return 생성된 신고 RPRT_SN
     */
    public Long report(Long srcSn, String reason, TokenClaims actor) {
        requireReason(reason);

        // 1) 권한 검사 + srcSn 의 rawSn 획득 (LabelAccessGuard: WORKER 는 본인 배정 영상만 통과)
        LsDataSrc src = accessGuard.verifyAndGet(srcSn, actor);
        Long reporterNo = accessGuard.parseUserNo(actor.sub());

        return doReport(src.getRawSn(), src.getSrcSn(), reason, reporterNo, actor);
    }

    /**
     * B-ISSUE-28 — <b>마킹 단계</b> 비식별 누락 신고 (영상 단위 진입점, {@code POST /v1/videos/{rawSn}/deident-report}).
     *
     * <p>마킹 화면은 비식별 <b>영상</b>을 재생하며 프레임(srcSn) 컨텍스트가 없다. 구현이 라벨링 단계
     * ({@code POST /v1/labels/{srcSn}/deident-report})뿐이라, 마킹 중 개인정보 노출을 발견해도 라벨링
     * 단계까지 진행해야 신고할 수 있었다(CLAUDE.md 상 planned 였던 갭).
     *
     * <p><b>부수효과는 srcSn 경로와 완전히 동일</b>하다 — 아래 {@link #doReport} 하나로 수렴하므로 두 진입점이
     * 갈라질 수 없다(파생영상·비식별 미수행 412 거부·작업락·{@code 'F'} 전이·개인정보 3필드 리셋·스트림 캐시 무효화·
     * APPROVED 통지). 차이는 <b>인가 축</b>과 <b>통지의 프레임 식별자</b> 둘뿐이다:
     * <ul>
     *   <li>인가 — 프레임이 없으므로 {@link LabelAccessGuard#verifyRawAccess}(영상 단위, 동일 규칙:
     *       REVIEWER 전체 / WORKER 본인 배정만)를 쓴다.</li>
     *   <li>통지 — {@code TaskModifiedEvent.srcSn=null}(영상 단위 변경). 개인정보 리셋 자체가 영상 전
     *       프레임 대상이라 특정 프레임을 지목할 근거가 없다.</li>
     * </ul>
     *
     * @return 생성된 신고 RPRT_SN
     */
    public Long reportByVideo(Long rawSn, String reason, TokenClaims actor) {
        requireReason(reason);

        // 1) 인가 — 영상 단위(IDOR, CWE-639). 영상 조회보다 먼저 평가해 미인가자에게 존재 여부를 흘리지 않는다.
        accessGuard.verifyRawAccess(rawSn, actor);
        Long reporterNo = accessGuard.parseUserNo(actor.sub());

        return doReport(rawSn, null, reason, reporterNo, actor);
    }

    /** 신고 사유 필수 검증 — 두 진입점 공통(컨트롤러 @Valid 우회 호출 방어). */
    private void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "신고 사유는 필수입니다.");
        }
    }

    /**
     * 신고 접수 공통 본체 — 인가만 진입점이 다르고 그 뒤 부수효과는 전부 여기 한 곳이다.
     *
     * @param srcSnForNotify TASK_MODIFIED 통지에 실을 프레임 ID. 영상 단위 진입(마킹)은 null.
     */
    private Long doReport(Long rawSnHint, Long srcSnForNotify, String reason,
                          Long reporterNo, TokenClaims actor) {
        // 2) 영상 로드 — 부모 RAW 행을 PESSIMISTIC_WRITE(SELECT … FOR UPDATE)로 잠금 조회한다
        //    (HIGH — PII TOCTOU 차단). 비잠금 findById 로 읽으면 read→markDeidentified('F') flush 사이
        //    창에서 동시 증강 콜백(AugmentResultService.createAugmentedVideo)의 findByRawSnForUpdate 가
        //    아직 커밋된 'Y' 를 읽어 PII 파생 증강본을 'Y'+MARKING_READY 로 확정·스트리밍하는 사고가 난다
        //    (CWE-359). read 시점부터 커밋까지 부모 row 잠금을 유지하면, 증강 tx 는 신고가 'F' 를 커밋할
        //    때까지 같은 row 에서 직렬화되어 대기 후 'F' 를 관측→자식 생성을 게이트에서 보류한다.
        //    본 서비스는 @Transactional("controlTransactionManager")(readOnly 아님) 안이므로 락이 유지된다.
        LsDataRaw raw = videoRepository.findByRawSnForUpdate(rawSnHint)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        Long rawSn = raw.getRawSn();

        // 2-1) ★ 파생영상(증강·해상도 변환본)은 신고 체계 밖이다 — 접수하지 않는다.
        requireReportableVideo(raw, reason);

        // 2-2) DEV_FIX(L-2) — 비식별을 아직 수행하지 않은 영상은 신고 대상이 아니다.
        requireDeidentAttempted(raw);

        // 3) 이미 잠금 상태면 409 — 중복 신고 차단
        if (workLockService.isRawLocked(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 비식별 재처리 중인 영상입니다.");
        }

        // 4) 신고 row 저장 — REPORT_STTS_CD='OPEN'
        LsDeidentReport report = reportRepository.save(
                LsDeidentReport.createReport(rawSn, reporterNo, reason));

        // 5) D-25 (2026-07-27 사용자 확정) — <b>라벨을 삭제하지 않는다</b>. 스냅샷도 남기지 않는다.
        //    구 동작(전체 라벨 스냅샷 → 전량 삭제)은 폐기됐다: 신고는 "비식별이 잘못됐다"는 신호일 뿐
        //    라벨 작업 결과를 폐기할 근거가 아니며, 스냅샷은 rawSn 스코프(DATA_SRC_SN=NULL)라 복원 진입점이
        //    없는 write-only 이력이었다(D-ISSUE-25). 라벨은 보존되고, 신고~재비식별 구간의 PII 노출은
        //    라벨 조회 게이트({@code LabelAccessGuard.requireNotUnderDeidentReport}, S7)로 차단한다.
        //    resolve 로 'F'→'Y' 가 복원되면 게이트가 열려 보존된 라벨을 그대로 재사용한다.
        //    라벨을 지우지 않으므로 라벨셋 버전 bump(낙관적 락)도 하지 않는다 — bump 는 "열어둔 화면이
        //    방금 지운 라벨을 되살리는 것"을 막기 위한 장치였고, 삭제가 없으면 되살릴 대상 자체가 없다.
        //    (조회 게이트로 신고 구간 재조회가 막히고, 저장은 작업락으로 409 차단된다.)

        // 5-1) Phase 3 #5 — 해당 영상 전체 프레임의 개인정보 3필드(익명/가명/개인정보 포함여부)를 NULL 로
        //      초기화(파생 폴백 복귀)한다. 신고→재비식별 후 옛 수동값이 남으면 '개인정보 없음' 등으로 stale
        //      오표기(CWE-359)되어 export 에 실릴 수 있으므로, 라벨 삭제와 동일 트랜잭션에서 함께 리셋한다.
        //      벌크 JPQL(파라미터 바인딩) — clearAutomatically 미지정이라 아래 raw dirty-update 는 유지된다.
        //
        //      DEV_FIX-B(M5, 보안 M-3) — PII 표기를 되돌리는 행위이므로 <b>행 단위 감사</b>가 필요하다
        //      (OWASP A09). 구 동작은 집계 로그 한 줄(privacyReset=N)뿐이라 "어느 프레임이 언제 누구에
        //      의해 리셋됐는가"를 사후 추적할 수 없었다. 신규 테이블/컬럼 없이 기존 이력 축
        //      (LS_DATA_LBL_HSTRY)에 프레임당 1행을 남긴다 — 라벨 델타 0건 이벤트라 데이터마트 뷰
        //      V_COMPLETED_LABEL_CHANGE 에는 V139 필터로 노출되지 않는다(관제 팬텀 행 방지).
        //      대상은 리셋 <b>직전</b>에 확정한다(리셋 후에는 전부 NULL 이라 구분 불가).
        List<Long> privacyResetSrcSns = srcRepository.findSrcSnsWithPrivacyMeta(rawSn);
        int privacyReset = srcRepository.resetPrivacyMetaByRawSn(rawSn);
        //      영상 단위 개인정보 3필드(LS_DATA_RAW.*_INCL_YN, V163)도 같은 근거로 함께 리셋한다 —
        //      그 판정은 <비식별이 잘못된 영상>에서 내려진 것이라 재판정 대상이고, 남겨두면 재비식별 후에도
        //      옛 판정이 export 의 video 블록에 stale 로 실린다(CWE-359). 프레임 축만 리셋하면 두 축이
        //      비대칭이 되어(video=옛 판정 / image=NULL) 같은 문서 안에서 근거 없는 차이가 생긴다.
        //      영속 엔티티 dirty checking — 위 벌크 JPQL 은 clearAutomatically 미지정이라 이 변경이 유지된다.
        //
        //      DEV_FIX 2차(감사, OWASP A09) — 영상 축 리셋도 <b>프레임 축과 같은 기준으로 행 단위 감사</b>한다.
        //      ⚠ 1차 DEV_FIX 의 "영상 축은 행 단위 이력이 불가능하다"는 결론은 <틀렸다>. 참인 것은
        //      "LS_DATA_LBL_HSTRY 로는 불가능하다"(SRC_SN NOT NULL = 프레임 스코프)까지이고,
        //      LS_TASK_EVENT_LOG 가 이미 <rawSn 스코프 + actor + EVNT_TYPE_CD + RSN> 을 갖추고 있어
        //      (배정·승인·반려가 쓰는 축) 그대로 재사용할 수 있다. 구 동작은 종결 로그의
        //      privacyReset=(프레임 수)뿐이라 <프레임 0건인데 영상 축만 Y 였던 영상>이 privacyReset=0 으로
        //      남아 판정 소멸이 소리 없이 묻혔다.
        boolean videoPrivacyReset = raw.getAnonyInclYn() != null
                || raw.getPsdoInclYn() != null
                || raw.getPrvcInclYn() != null;
        raw.changePrivacyMeta(null, null, null);
        if (videoPrivacyReset) {
            // 실제로 지워진 판정이 있을 때만 남긴다 — 없는 사실을 이력에 만들지 않는다.
            // 판단값(Y/N)은 담지 않는다(CWE-359): 남기는 것은 누가·언제·어느 영상·어느 신고인지뿐.
            taskEventLogRepository.save(
                    LsTaskEventLog.privacyMetaReset(rawSn, reporterNo, report.getRprtSn()));
        }
        if (!privacyResetSrcSns.isEmpty()) {
            String reporterId = String.valueOf(reporterNo);
            lblHstryRepository.saveAll(privacyResetSrcSns.stream()
                    .map(frameSn -> LsDataLblHstry.recordPrivacyMetaResetEvent(
                            frameSn, reporterId, report.getRprtSn()))
                    .toList());
        }

        // 5-2) 검수 완료(APPROVED) 영상이면 개인정보 메타 리셋이 수정 통지 대상 —
        //      TASK_MODIFIED(META_UPDATED) 발행. 라벨은 보존되므로 구 LABEL_DELETED 는 더 이상 맞지 않다.
        if (isReviewApproved(rawSn)) {
            eventPublisher.publishEvent(new TaskModifiedEvent(
                    rawSn, srcSnForNotify, ChangeType.META_UPDATED, reporterNo));
        }

        // 6) 영상 잠금 + 비식별 상태 'F' 마킹. 동시 신고 unique 위반 → 409.
        //    (R1 v1.14: 자동 재비식별 큐 적재 제거 — 외부 솔루션 수동 비식별화로 대체)
        try {
            workLockService.lockRawForRedeident(rawSn, actor.sub());
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 비식별 재처리 중인 영상입니다.");
        }
        raw.markDeidentified("F");

        // 6-1) 스트림 메타 캐시 무효화 (HIGH — privacy) — 'F' 전이가 커밋 후 즉시 반영되어
        //      옛 비식별본(노출본)이 stream-meta TTL 동안 계속 서빙되지 않도록 한다.
        //      대상은 <b>이 영상 하나</b>다: 파생영상은 원본 신고에 영향받지 않는 것이 확정 정책이므로
        //      (DeidentReportGate javadoc) 파생 캐시를 건드릴 이유가 없다.
        streamMetaCacheEvictor.evictAfterCommit(rawSn);

        // 7) REVIEWER 알림
        notificationService.notifyReviewersOnDeidentReport(raw, reporterNo, reason);

        log.info("[DeidentReport] created rprtSn={} rawSn={} reporterNo={} labelsPreserved=true "
                        + "privacyReset={} privacyResetAudited={} videoPrivacyReset={}",
                report.getRprtSn(), rawSn, reporterNo, privacyReset, privacyResetSrcSns.size(),
                videoPrivacyReset);
        return report.getRprtSn();
    }

    /**
     * ★ 신고 접수 대상 판정 — <b>비파생 영상에서만 신고를 접수한다</b> (2026-07-29 사용자 확정, 구속).
     *
     * <h3>왜 파생영상에서는 신고를 받지 않는가</h3>
     * 파생영상(증강 {@code WINTER/NIGHT/RAIN} · 해상도 {@code RESL_*})의 프레임은 <b>원본의 비식별
     * 산출물을 복사·리스케일한 사본</b>이다. 재비식별은 외부 솔루션이 <b>원본 영상</b>을 다시 처리하는
     * 방식뿐이라 <b>파생본 자체를 다시 비식별할 수단이 없다</b>. 접수해봐야 해소할 방법이 없는 신고
     * (작업락 + {@code 'F'} 고착)만 남으므로 접수 자체를 하지 않는다.
     *
     * <h3>★ 원본으로 유도하지 않는다 (구 안내 문구 폐기)</h3>
     * 구 문구는 "원본 영상(영상번호 N)에서 신고해 주세요" 였으나 <b>사실과 맞지 않았다</b>:
     * <ul>
     *   <li><b>원본을 신고해도 이 파생영상은 달라지지 않는다</b> — 차단 게이트({@link DeidentReportGate})는
     *       자기 rawSn 행만 보므로 원본 신고는 파생에 아무 영향이 없다(확정 정책).</li>
     *   <li>파생영상에 배정된 WORKER 는 원본에 대한 접근 권한이 없어({@code LabelAccessGuard} 403)
     *       따라갈 수도 없는 안내였다.</li>
     * </ul>
     * 그래서 <b>사실만</b> 알린다 — 이 화면(파생영상)에서는 재비식별을 요청할 수 없다는 것. 원본 rawSn 도
     * 내려주지 않는다(따라가면 막다른 길이므로 유도 자체가 잘못된 정보다).
     *
     * <h3>응답 규약 — 412</h3>
     * 요청 자체는 형식상 유효하고(400 아님) 시간이 지나면 풀리는 일시적 충돌도 아니며(409 는 이 API 에서
     * 이미 "재비식별 진행 중"이 점유한다 — 같은 코드로 내면 화면이 구분할 수 없다), <b>대상 리소스의 영구
     * 속성</b> 때문에 거부되는 프리컨디션이다. 프로젝트의 신고 게이트 계열 표준 코드인
     * {@link ErrorCode#PRECONDITION_FAILED}(412)를 쓴다({@code LabelAccessGuard.requireNotUnderDeidentReport}
     * · {@code FrameImageEncoder} · {@code DatasetExportService} · {@code AutolabelOnlineService} 동일).
     *
     * <h3>REVIEWER 알림은 보내지 않는다 — 대신 사유를 감사 로그로 남긴다</h3>
     * 정상 신고는 {@code notificationService.notifyReviewersOnDeidentReport} 를 태우지만, 거부 경로는
     * <b>신고 행({@code LS_DEIDENT_REPORT})이 생성되지 않는다</b>. 같은 알림을 쏘면 REVIEWER 가 존재하지
     * 않는 신고를 찾게 되고(현 구현은 로그 한 줄짜리 placeholder 라 "접수됨"과 구분도 되지 않는다),
     * 정책상 파생은 신고 대상이 아니므로 처리할 워크플로도 없다. 다만 <b>발견 사실 자체는 유실되면 안
     * 되므로</b> 사용자가 적은 사유를 {@link kr.co.cudo.authoring.common.util.LogSanitizer} 로 정제해
     * WARN 감사 로그에 남긴다(CWE-117 개행 제거 + 길이 절단). FE 는 애초에 파생영상에서 신고 버튼을
     * 비활성화하므로 이 경로는 API 직접 호출·낡은 화면에서만 도달한다.
     */
    private void requireReportableVideo(LsDataRaw raw, String reason) {
        if (!raw.isDerivative()) {
            return;
        }
        log.warn("[DeidentReport] rejected — derivative video is out of the report workflow "
                        + "rawSn={} orgnlRawSn={} reason={}",
                raw.getRawSn(), raw.getOrgnlRawSn(),
                kr.co.cudo.authoring.common.util.LogSanitizer.sanitize(reason));
        throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                "이 영상은 원본 영상의 비식별 결과를 복사해 만든 파생영상(증강·해상도 변환본)이라 "
                        + "이 화면에서는 비식별 재처리를 요청할 수 없습니다.");
    }

    /**
     * DEV_FIX(L-2) — <b>비식별 산출물이 있는 영상에서만</b> 신고를 접수한다(프리컨디션).
     *
     * <h3>왜 필요한가</h3>
     * {@code doReport} 는 무조건 {@code markDeidentified("F")} 로 전이한다. 라벨링 단계(srcSn) 진입점은
     * 프레임이 존재해야 도달하므로 사실상 비식별·프레임추출 완료가 전제였지만, 신설된 <b>마킹 단계
     * (rawSn) 진입점</b>은 {@code PENDING}(비식별 미실행) 영상에도 직접 도달한다. 그 상태에서 접수하면
     * <ul>
     *   <li>{@code 'N' → 'F'} 로 바뀌어 {@link LsDataRaw#hasDeidentArtifact()} 가 true 가 된다 —
     *       "산출물이 있다"는 뜻인데 실제로는 없다(증강·해상도 파생 부모 게이트가 통과된다.
     *       뒤의 산출물 실재 fail-closed 검사가 막긴 하지만, 판정 원천이 거짓이 되는 것 자체가 결함이다).</li>
     *   <li>파이프라인 진행 중 영상에 작업락이 고착된다 — 해소는 "외부 솔루션이 <b>재</b>비식별했다"는
     *       전제의 {@code resolve} 뿐인데, 애초에 비식별을 한 적이 없어 그 전제가 성립하지 않는다.</li>
     * </ul>
     *
     * <h3>판정·응답</h3>
     * 판정은 {@link LsDataRaw#hasDeidentArtifact()} <b>단일 원천</b>({@code 'Y'} | {@code 'F'})을 재사용한다
     * (여기서 {@code "F"} 비교를 재구현하지 않는다). 따라서 <b>이미 신고된 {@code 'F'} 는 통과</b>하며,
     * 그 중복 신고는 기존 409(이미 재비식별 진행 중) 경로가 그대로 처리한다 — 여기서 412 로 바꾸면 기존
     * 계약이 깨진다. {@code 'N'}·null 만 412 {@link ErrorCode#PRECONDITION_FAILED}(신고 게이트 계열 표준 코드).
     */
    private void requireDeidentAttempted(LsDataRaw raw) {
        if (raw.hasDeidentArtifact()) {
            return;
        }
        log.warn("[DeidentReport] rejected — video has no deidentification artifact rawSn={} deIdntfYn={}",
                raw.getRawSn(), raw.getDeIdntfYn());
        throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                "아직 비식별 처리가 완료되지 않은 영상입니다. 비식별 완료 후 신고할 수 있습니다.");
    }

    /**
     * 외부 솔루션 수동 비식별화 완료 후 신고 해소 (R1 v1.14).
     *
     * <p>OPEN→RESOLVED 전이 + 작업락 해제를 동일 트랜잭션에서 처리(원자성).
     *
     * <ul>
     *   <li>미인증 → 401</li>
     *   <li>신고 없음 → 404</li>
     *   <li>WORKER 타인 영상 → 403 (verifyRawAccess), REVIEWER 전체 허용</li>
     *   <li>OPEN 아니면 → 409 (이미 RESOLVED/DISMISSED 재-resolve 차단)</li>
     *   <li>비식별 산출물 미검증 → 409 ({@link #verifyDeidentArtifact} — 실제 비식별 없이 resolve 시
     *       PII 재노출 차단, fail-closed: report OPEN·작업락·'F' 유지)</li>
     * </ul>
     */
    public void resolveManually(Long rprtSn, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        LsDeidentReport report = reportRepository.findById(rprtSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "신고를 찾을 수 없습니다."));

        // IDOR — WORKER 는 본인 배정 영상만, REVIEWER 는 전체 허용.
        accessGuard.verifyRawAccess(report.getRawSn(), actor);

        // OPEN 선제 검증 — 이미 RESOLVED/DISMISSED 면 409 (원자성: 전이+락해제는 동일 트랜잭션).
        if (!LsDeidentReport.REPORT_OPEN.equals(report.getReportSttsCd())) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 처리된 신고입니다.");
        }

        // 비식별 산출물 검증 게이트 (CWE-359) — 실제 외부 수동 비식별 없이 resolve 를 호출하면
        // deIdntfYn 'F'→'Y' 복원으로 마킹 게이트·영상 스트리밍이 재개방되어 PII 가 재노출된다.
        // resolve 진행(RESOLVED 전이/락해제/'Y' 복원) 전에 비식별 산출물 실재를 확인하고, 실패 시
        // 즉시 거부한다. 예외 전파 시 트랜잭션이 롤백되어 report 는 OPEN, 작업락은 유지된다(fail-closed).
        verifyDeidentArtifact(report);

        report.resolve();
        workLockService.releaseRaw(report.getRawSn(), actor.sub(), "MANUAL_DEIDENT_DONE");

        // B1 — 외부 솔루션 수동 비식별화가 완료되었으므로 DE_IDENT_YN 을 'F'→'Y' 로 복원한다.
        // report() 가 신고 시 'F' 로 내린 값을 되돌리지 않으면 마킹 게이트(MarkingService.create 의
        // deIdntfYn=='Y' 조건)가 영구 폐쇄되어 해당 영상의 재마킹이 불가능해진다. 자동 배치 경로
        // (resolveOpenReports)는 DeidentifyStep 이 'Y' 로 복원하지만, 수동 경로에는 복원 주체가 없어
        // 누락되던 결함(HIGH)을 수정한다. report() 와 동일하게 부모 RAW row 를 SELECT … FOR UPDATE 로
        // 잠금 조회해 증강 콜백과의 TOCTOU(CWE-359)를 차단한다.
        //
        // 배치 단계 상태(DATA_STTS_CD)는 되감지 않는다(CWE-664 상태 역행 방지):
        //  - 마킹 단계 신고는 report() 가 MARKING_READY 를 보존하므로 'Y' 복원만으로 게이트를 통과한다.
        //  - COMPLETED/검수완료(APPROVED) 후기 단계 신고는 COMPLETED 를 유지해 배치 단계를 역행시키지 않는다.
        videoRepository.findByRawSnForUpdate(report.getRawSn())
                .ifPresentOrElse(
                        raw -> raw.markDeidentified("Y"),
                        () -> log.warn("[DeidentReport] raw video not found on resolve rawSn={}",
                                report.getRawSn()));

        // 외부 수동 재비식별로 비식별본이 교체되었을 수 있으므로 스트림 메타 캐시를 커밋 후 무효화한다
        // (대상은 이 영상 하나 — 파생영상의 비식별본 파일은 파생 생성 시점 사본이라 바뀌지 않는다).
        streamMetaCacheEvictor.evictAfterCommit(report.getRawSn());

        // M1 — 신고 구간에 보류(차단)됐던 export·통지 복구를 트리거한다.
        publishResolvedForExportRecovery(report.getRawSn());

        log.info("[DeidentReport] resolved-manually rprtSn={} rawSn={} actor={}",
                rprtSn, report.getRawSn(), actor.sub());
    }

    /**
     * 비식별 신고 목록 조회 (G-1 — REVIEWER 신고 관리 화면).
     *
     * <p>권한은 Controller {@code @PreAuthorize("hasRole('REVIEWER')")} 로 강제하며,
     * 본 메서드는 상태(status) 필터 정규화 + 페이징 조회만 수행한다.
     *
     * <ul>
     *   <li>status null/blank → 기본 OPEN.</li>
     *   <li>status 는 OPEN/RESOLVED/DISMISSED allowlist 만 허용 — 그 외는 400 (CWE-20 입력 검증).</li>
     * </ul>
     *
     * @return 신고 목록 페이지 (DTO 변환 — Entity 직접 노출 금지)
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public org.springframework.data.domain.Page<kr.co.cudo.authoring.label.dto.DeidentReportListResponse> listReports(
            String status, org.springframework.data.domain.Pageable pageable) {
        String normalized = normalizeStatus(status);
        return reportRepository.findByReportSttsCd(normalized, pageable)
                .map(kr.co.cudo.authoring.label.dto.DeidentReportListResponse::from);
    }

    /** 상태 필터 정규화 — null/blank → OPEN, allowlist 밖이면 400. */
    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return LsDeidentReport.REPORT_OPEN;
        }
        String upper = status.trim().toUpperCase();
        if (!LsDeidentReport.REPORT_OPEN.equals(upper)
                && !LsDeidentReport.REPORT_RESOLVED.equals(upper)
                && !LsDeidentReport.REPORT_DISMISSED.equals(upper)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "status 는 OPEN/RESOLVED/DISMISSED 만 허용됩니다.");
        }
        return upper;
    }

    /**
     * 배치 자동 재비식별 성공 시 — 해당 영상의 OPEN 신고 일괄 RESOLVED (기존 DeidentifyStep 경로 유지).
     */
    public int resolveOpenReports(Long rawSn) {
        if (rawSn == null) {
            return 0;
        }
        List<LsDeidentReport> opens = reportRepository.findAllByDataRawSnAndReportSttsCd(
                rawSn, LsDeidentReport.REPORT_OPEN);
        for (LsDeidentReport r : opens) {
            r.resolve();
        }
        workLockService.releaseRaw(rawSn, "system", "DEIDENT_SUCCEEDED");
        // 배치/스텝 자동 재비식별 성공으로 비식별본이 교체되었으므로 스트림 메타 캐시를 커밋 후 무효화.
        streamMetaCacheEvictor.evictAfterCommit(rawSn);
        if (!opens.isEmpty()) {
            // M1 — 자동(배치) 해소 경로도 동일하게 보류됐던 export·통지를 복구한다. 실제로 해소한
            //      신고가 있을 때만 발행한다(신고가 없던 정상 비식별 성공은 재산출 대상이 아니다).
            publishResolvedForExportRecovery(rawSn);
            log.info("[DeidentReport] resolved rawSn={} count={}", rawSn, opens.size());
        }
        return opens.size();
    }

    /**
     * M1 — 신고 해소로 export 게이트가 열렸음을 알려 <b>보류됐던 산출·통지</b>를 복구시킨다.
     *
     * <p>신고 구간 export 차단은 {@code LS_DATASET_EXPORT} 행을 남기지 않아 실패 회수기(FAILED 행만
     * 스캔)가 집지 못한다. 따라서 <b>해제 시점 재트리거가 유일한 복구 경로</b>다. 소비자는
     * {@code DatasetExportBridge#onDeidentReportResolved}(AFTER_COMMIT) 이며, 'Y' 복원이 커밋된 뒤에
     * 실행되므로 export 진입부 게이트에 스스로 막히지 않는다.
     *
     * <h3>★ 복구 범위 = <b>해제된 영상 자신뿐</b> (2026-07-29 확정 정책과 대칭)</h3>
     * 차단 게이트({@link DeidentReportGate})가 <b>자기 rawSn 행만</b> 보므로, 어떤 신고가 막는 노드는
     * 정확히 <b>그 신고된 영상 하나</b>다. 파생영상은 원본 신고에 영향받지 않으므로 복구 대상도 아니다
     * (자손 팬아웃·상한·"다른 조상이 아직 신고 중인가" 판정이 모두 불필요해졌다 — 게이트 javadoc 의
     * "폐기된 안" 참조).
     *
     * <h3>두 이벤트를 함께 발행한다</h3>
     * <ul>
     *   <li>{@link DeidentGateReopenedEvent} — <b>항상</b>. 승인 여부와 무관하게 보류됐던 파이프라인 작업
     *       (특히 <b>VLM 시계열 위탁</b>)을 재개시킨다. VLM 보류는 파이프라인 진행 중(=대개 미승인)
     *       영상에서 일어나므로, 승인 영상에만 발행하면 시계열 메타가 영구 결손된다.</li>
     *   <li>{@link DeidentReportResolvedEvent} — <b>검수 승인(APPROVED)일 때만</b>. export 재산출·관제
     *       재통지 대상이라 미승인 영상에 발행하면 불필요한 v1 을 만든다.</li>
     * </ul>
     */
    private void publishResolvedForExportRecovery(Long rawSn) {
        if (rawSn == null) {
            return;
        }
        eventPublisher.publishEvent(new DeidentGateReopenedEvent(rawSn));
        if (isReviewApproved(rawSn)) {
            eventPublisher.publishEvent(new DeidentReportResolvedEvent(rawSn));
        }
    }

    // ---------- 내부 ----------

    /**
     * 비식별 산출물 검증 게이트 (CWE-359, fail-closed) — 수동 resolve 시 실제 비식별본이
     * <b>신고 이후 재비식별</b>된 것일 때만 통과.
     *
     * <p>검증 절차:
     * <ol>
     *   <li>해당 rawSn 의 최신 성공(SUCCEEDED) 처리 이력에 비식별 파일 경로(DE_IDNTF_FILE_PATH_NM)가
     *       기록되어 있는가 — 없으면 거부.</li>
     *   <li>기록된 경로의 파일이 <b>유효한 비식별 산출 영상</b>인가 —
     *       판정은 {@link DeidentArtifactIntegrity}(정규파일 + 크기 하한 + 컨테이너 시그니처)
     *       <b>단일 지점에 위임</b>한다. 아니면 거부.</li>
     *   <li><b>시간 조건</b> — 아래 중 하나라도 충족해야 통과. 둘 다 신고 이전이면 신고를 유발한
     *       그 비식별본으로 판단하여 거부한다.
     *     <ul>
     *       <li>(1) procLog 완료시각(RSPNS_DT, 없으면 REQ_DT) &gt; 신고시각 — 신고 후 자동 재비식별 성공 케이스.</li>
     *       <li>(2) 비식별 파일 mtime &gt; 신고시각 — 외부 도구가 파일을 제자리 교체한 케이스(주 경로).</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p><b>무결성 판정 단일화(B-ISSUE-01)</b>: 구 판정("정규파일 + &gt;0바이트")은 외부 목/솔루션이 남긴
     * 18바이트 텍스트 스텁도 통과시켜, 실제 비식별 없이 {@code 'F'→'Y'} 복원이 가능했다(CWE-345). 이 복원은
     * 라벨 조회·export·스트리밍 게이트를 <b>한꺼번에 여는</b> 지점이라 위장 산출물 통과 = PII 재노출이다.
     * 따라서 다른 회수 경로({@code KpstDeidentService#isUsableDeidFile},
     * {@code KpstDeidentTxService#verifyDeidFile})와 <b>동일한 판정 함수</b>를 쓴다 — 판정 로직을 여기서
     * 자체 구현하지 않는다.
     *
     * <p>시간 조건이 필요한 이유: {@code findLatestSuccessByDataRawSn} 가 반환하는 최신 성공 procLog 는
     * <b>신고 이전 비식별본</b>(누출 신고를 유발한 그 파일 — 디스크에 실존·&gt;0바이트)일 수 있어, 파일 존재만으로는
     * 실제 재비식별 없이 'Y' 복원이 통과된다. 외부 수동 재비식별은 파일을 <b>제자리 교체</b>할 뿐 새 procLog 를
     * 삽입하지 않으므로 procLog 시각만으로는 판정 불가 — 그래서 파일 mtime 을 주 판정으로 사용한다.
     *
     * <p>경로는 <b>DB 에 적재된 값만</b> 사용한다(사용자 입력으로 경로를 구성하지 않음 — Path Manipulation 방지).
     * 검증 실패 시 내부 경로를 노출하지 않는 안내 메시지로 409 를 던진다(외부 솔루션 비식별 완료 후 재시도 취지).
     * 예외 전파 → 트랜잭션 롤백 → report OPEN 유지 + 작업락 유지 + deIdntfYn 'F' 유지(fail-closed).
     */
    private void verifyDeidentArtifact(LsDeidentReport report) {
        Long rawSn = report.getRawSn();
        LsDeidentProcLog procLog = procLogRepository.findLatestSuccessByDataRawSn(rawSn)
                .orElseThrow(() -> deidentNotVerified(rawSn));
        String deidPath = procLog.getDeIdntfFilePathNm();
        if (deidPath == null || deidPath.isBlank()) {
            throw deidentNotVerified(rawSn);
        }

        // 산출물 무결성 — 판정은 DeidentArtifactIntegrity 단일 지점에 위임한다(자체 판정 금지).
        // 잘못된 경로/IO 오류도 그 안에서 false 로 수렴하므로 여기서는 결과만 게이팅한다(fail-closed).
        if (!DeidentArtifactIntegrity.isValidVideoArtifact(deidPath)) {
            throw deidentNotVerified(rawSn);
        }

        // 신고시각 — 시간 판정 불가(null)면 보수적으로 거부(fail-closed).
        LocalDateTime reportTime = report.getReportDt();
        if (reportTime == null) {
            throw deidentNotVerified(rawSn);
        }

        // (1) procLog 완료시각 > 신고시각 (엄격 비교 — 신고를 유발한 옛 성공 이력을 배제).
        LocalDateTime procTime = procLog.getResDt() != null ? procLog.getResDt() : procLog.getReqDt();
        boolean procAfterReport = procTime != null && procTime.isAfter(reportTime);

        // (2) 비식별 파일 mtime > 신고시각 (엄격 비교 — (1) procLog 비교와 동일 기준).
        //
        // B-ISSUE-42(1차 B-ISSUE-102 이월) — 구식 `reportTime.minusSeconds(CLOCK_SKEW_TOLERANCE_SECONDS)`
        // 는 클럭 스큐 관용을 <감산> 방향으로 열어, mtime 이 신고시각보다 최대 60초 <과거>인 파일
        // (= 신고 이전부터 있던, 재비식별되지 않은 그 산출물)까지 통과시켰다. 이 게이트의 통과는
        // 라벨 조회·export·스트리밍 게이트를 한꺼번에 여는 지점이라 곧 PII 재노출이다(CWE-359).
        //
        // 재비식별 산출물은 원칙적으로 신고 <이후>에 생성되므로 감산 관용에는 근거가 없다. 관용을
        // 가산 방향으로 옮기는 안(mtime > 신고시각 + 60초)도 채택하지 않는다 — 신고 직후 즉시
        // 재비식별한 정상 건을 60초간 근거 없이 거부해 fail-closed 를 넘어선 오탐이 되기 때문이다.
        // 따라서 관용을 제거하고, 같은 메서드의 (1) procLog 비교가 이미 쓰는 엄격 비교로 통일한다.
        //
        // 경계값(mtime == 신고시각)은 <거부>다. 동일 시각의 파일은 신고 시점에 이미 존재하던
        // 산출물이라 '신고 이후 교체' 증거가 아니며, 증거 없음은 fail-closed 로 거부에 수렴한다.
        boolean fileAfterReport = false;
        try {
            Path file = Paths.get(deidPath);
            LocalDateTime mtime = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());
            fileAfterReport = mtime.isAfter(reportTime);
        } catch (IOException | InvalidPathException e) {
            fileAfterReport = false;
        }

        if (!procAfterReport && !fileAfterReport) {
            throw deidentNotVerified(rawSn);
        }
    }

    /** 비식별 산출물 미검증 거부 예외 — 내부 경로 미노출, 외부 비식별 완료 후 재시도 안내. */
    private CustomException deidentNotVerified(Long rawSn) {
        log.warn("[DeidentReport] resolve blocked — deident artifact not verified rawSn={}", rawSn);
        return new CustomException(ErrorCode.CONFLICT,
                "비식별 산출물이 확인되지 않습니다. 외부 솔루션으로 비식별을 완료한 뒤 다시 시도하세요.");
    }

    /**
     * 영상(rawSn) 의 검수 상태가 APPROVED(검수 완료) 인지 판정. 상태 row 가 없으면 미검수로 간주.
     */
    private boolean isReviewApproved(Long rawSn) {
        return rawDataStatusRepository.findByRawDataIdIn(List.of(rawSn)).stream()
                .findFirst()
                .map(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()))
                .orElse(false);
    }
}
