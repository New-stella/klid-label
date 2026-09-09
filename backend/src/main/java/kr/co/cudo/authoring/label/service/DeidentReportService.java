package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.cache.StreamMetaCacheEvictor;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.label.event.DeidentGateReopenedEvent;
import kr.co.cudo.authoring.label.event.DeidentReportResolvedEvent;
import kr.co.cudo.authoring.label.event.DeidentStageResumeEvent;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
 *       자동 재비식별 큐 적재는 제거되었고(외부 솔루션 수동 비식별화로 대체) 영상 잠금 +
 *       DE_IDNTF_YN='F' 는 유지.
 *       <b>★ 개인정보 3필드 리셋도 폐기됐다(2026-08-04 사용자 확정)</b> — 구 동작은 프레임 축·영상 축
 *       3필드를 NULL 로 되돌렸으나, 라벨 보존과 같은 취지로 <b>사람이 입력한 판정도 보존</b>한다
 *       (구 근거와 폐기 경위는 {@link #report} 의 5-1 주석에 보존).</li>
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
 *       ③ <b>검수 승인 영상 거부 경로</b> — {@link #requireNotApprovedVideo} 가 ②와 <b>같은 방식</b>으로
 *       {@link kr.co.cudo.authoring.common.util.LogSanitizer} 정제 후 WARN 으로 기록한다. 승인 영상은
 *       조치 수단이 0 이라(신고 412 · 재비식별 요청 409 · 화면 버튼 미노출) 신고 행도 REVIEWER 알림도
 *       생기지 않으므로, 로그를 빼면 사용자가 발견한 개인정보 노출 사실이 <b>완전히 소실</b>된다(CWE-778).
 *       ※ 로그 대상은 <b>사유 텍스트뿐</b> — 프레임 픽셀·경로 등 다른 PII 원본은 로그에 싣지 않는다.</li>
 *   <li><b>SQL Injection (CWE-89)</b>: 상태 전이/조회는 JPA 파라미터 바인딩 @Modifying 쿼리만 사용.</li>
 * </ul>
 *
 * <p>신고 저장 + 작업락 + 'F' 전이는 단일 트랜잭션 — 부분 실패 시 전체 롤백.
 * (구 서술의 "개인정보 리셋"은 2026-08-04 폐기 — 신고는 개인정보 3필드를 건드리지 않는다.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager")
public class DeidentReportService {

    /**
     * R3 — 해소 시 적재하는 신규 성공 원장의 등록자 태그({@code REG_ID}, 30자 이내).
     * 사람 식별자를 넣지 않는다(PII 최소화) — 다른 원장 생성부와 같은 "출처 태그" 관례를 따른다
     * ({@code batch} · {@code batch-mock} · {@code resolution-derivative}).
     */
    private static final String RESOLVE_PROC_REG_ID = "deident-resolve";

    private final LabelAccessGuard accessGuard;
    private final VideoRepository videoRepository;
    private final LsDeidentReportRepository reportRepository;
    private final NotificationService notificationService;
    private final WorkLockService workLockService;
    private final ReviewApprovalGate approvalGate;
    private final ApplicationEventPublisher eventPublisher;
    private final StreamMetaCacheEvictor streamMetaCacheEvictor;
    private final LsDeidentProcLogRepository procLogRepository;
    /** R3 — 재비식별 산출물 후보 열거(조회·수락 공용 단일 지점). */
    private final DeidentArtifactCandidateFinder candidateFinder;
    /**
     * 신고 목록의 신고자 표시명 해석용 — <b>저작도구 소유</b> 계정 마스터
     * {@code LS_ACNT_USER}(V169) READ 전용({@link #listReports}).
     *
     * <p>구 개인정보 3필드 리셋 감사용 필드({@code lblHstryRepository}·{@code taskEventLogRepository})는
     * 리셋 정책 폐기(2026-08-04)로 <b>참조처가 사라져 제거</b>했다 — 이 서비스는 개인정보 판정을
     * 건드리지 않으므로 감사할 대상 자체가 없다.
     */
    private final kr.co.cudo.authoring.user.service.UserNameResolver userNameResolver;

    /**
     * 비식별 누락 신고 등록 (R1 v1.14).
     *
     * <p>흐름: 권한검사 → 영상로드 → <b>파생영상 거부</b>({@link #requireReportableVideo})
     *        → <b>비식별 미수행 거부</b>({@link #requireDeidentAttempted})
     *        → <b>검수 승인 영상 거부</b>({@link #requireNotApprovedVideo}, R2) → 잠금 선점검
     *        → 신고 OPEN 저장 → 작업락 + DE_IDNTF_YN='F' → REVIEWER 알림.
     *        <b>라벨도 개인정보 3필드도 삭제·리셋하지 않는다</b>(2026-07-27 / 2026-08-04 정책 반전).
     *
     * <p><b>★ 개인정보 3필드 리셋은 폐기됐다 (2026-08-04 사용자 확정)</b>: 구 동작은 프레임 축
     * ({@code resetPrivacyMetaByRawSn})과 영상 축({@code changePrivacyMeta(null,null,null)})을 NULL 로
     * 되돌렸다. <b>라벨 보존 정책과 같은 취지</b>로 사람이 입력한 판정도 작업 결과이므로 보존하고,
     * 해제 후 <b>기존 판정을 그대로 이어서</b> 진행한다. 따라서 아래의 "파생 cross-stale 경계" 논의도
     * 함께 소멸한다 — 리셋 자체가 없으므로 부모→파생 캐스케이드 리셋이라는 대상이 존재하지 않는다
     * (기존 파생은 원본 신고와 무관하게 독립 취급한다는 결론은 그대로다 — 파생은 신고 접수 자체가
     * 412 로 거부된다, {@link #requireReportableVideo}).
     *
     * @return 생성된 신고 RPRT_SN
     */
    public Long report(Long srcSn, String reason, TokenClaims actor) {
        requireReason(reason);

        // 1) 권한 검사 + srcSn 의 rawSn 획득 (LabelAccessGuard: WORKER 는 본인 배정 영상만 통과)
        LsDataSrc src = accessGuard.verifyAndGet(srcSn, actor);
        Long reporterNo = accessGuard.parseUserNo(actor.sub());

        return doReport(src.getRawSn(), reason, reporterNo, actor, LsDeidentReport.STAGE_LABELING);
    }

    /**
     * B-ISSUE-28 — <b>마킹 단계</b> 비식별 누락 신고 (영상 단위 진입점, {@code POST /v1/videos/{rawSn}/deident-report}).
     *
     * <p>마킹 화면은 비식별 <b>영상</b>을 재생하며 프레임(srcSn) 컨텍스트가 없다. 구현이 라벨링 단계
     * ({@code POST /v1/labels/{srcSn}/deident-report})뿐이라, 마킹 중 개인정보 노출을 발견해도 라벨링
     * 단계까지 진행해야 신고할 수 있었다(CLAUDE.md 상 planned 였던 갭).
     *
     * <p><b>부수효과는 srcSn 경로와 완전히 동일</b>하다 — 아래 {@link #doReport} 하나로 수렴하므로 두 진입점이
     * 갈라질 수 없다(파생영상·비식별 미수행·<b>검수 승인</b> 412 거부·작업락·{@code 'F'} 전이·
     * 스트림 캐시 무효화 — 라벨과 개인정보 3필드는 양쪽 모두 <b>보존</b>). 차이는 <b>인가 축</b>과
     * <b>신고 단계</b> 둘뿐이다:
     * <ul>
     *   <li>인가 — 프레임이 없으므로 {@link LabelAccessGuard#verifyRawAccess}(영상 단위, 동일 규칙:
     *       REVIEWER 전체 / WORKER 본인 배정만)를 쓴다.</li>
     *   <li>단계 — {@link LsDeidentReport#STAGE_MARKING}(해소 후 마킹부터 재개, V171).</li>
     * </ul>
     * <p>(구 세 번째 차이 "통지의 프레임 식별자"는 R2 로 소멸했다 — 승인 영상은 접수 자체가 막혀
     * 신고 시점 {@code TaskModifiedEvent} 발행 분기가 도달 불가가 됐다.)
     *
     * @return 생성된 신고 RPRT_SN
     */
    public Long reportByVideo(Long rawSn, String reason, TokenClaims actor) {
        requireReason(reason);

        // 1) 인가 — 영상 단위(IDOR, CWE-639). 영상 조회보다 먼저 평가해 미인가자에게 존재 여부를 흘리지 않는다.
        accessGuard.verifyRawAccess(rawSn, actor);
        Long reporterNo = accessGuard.parseUserNo(actor.sub());

        return doReport(rawSn, reason, reporterNo, actor, LsDeidentReport.STAGE_MARKING);
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
     * <p>★ R2 — 구 인자 {@code srcSnForNotify}(TASK_MODIFIED 통지에 실을 프레임 ID)는 <b>제거됐다</b>.
     * 그 통지 분기 자체가 도달 불가가 되었기 때문이다(아래 5-2 주석). 두 진입점의 남은 차이는
     * <b>인가 축</b>과 <b>신고 단계</b> 둘뿐이다.
     *
     * @param stage 신고 단계({@link LsDeidentReport#STAGE_MARKING} |
     *              {@link LsDeidentReport#STAGE_LABELING}) — 해소 후 재개 지점 분기의 근거(V171).
     */
    private Long doReport(Long rawSnHint, String reason,
                          Long reporterNo, TokenClaims actor, String stage) {
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

        // 2-3) ★ 마킹 단계 신고는 MARKING_READY 에서만 접수한다 (V171).
        requireMarkingStageAllowed(raw, stage);

        // 2-4) ★ R2 — 검수가 승인된 영상은 신고를 접수하지 않는다.
        //      작업락 409 검사보다 <b>먼저</b> 평가한다 — 잠금 여부에 따라 412/409 로 갈리면
        //      응답이 잠금 상태를 알려주는 오라클이 된다(CWE-209).
        requireNotApprovedVideo(rawSn, reason);

        // 3) 이미 잠금 상태면 409 — 중복 신고 차단
        if (workLockService.isRawLocked(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 비식별 재처리 중인 영상입니다.");
        }

        // 4) 신고 row 저장 — REPORT_STTS_CD='OPEN' + DCLR_STP_CD=신고 단계(V171)
        LsDeidentReport report = reportRepository.save(
                LsDeidentReport.createReport(rawSn, reporterNo, reason, stage));

        // 5) D-25 (2026-07-27 사용자 확정) — <b>라벨을 삭제하지 않는다</b>. 스냅샷도 남기지 않는다.
        //    구 동작(전체 라벨 스냅샷 → 전량 삭제)은 폐기됐다: 신고는 "비식별이 잘못됐다"는 신호일 뿐
        //    라벨 작업 결과를 폐기할 근거가 아니며, 스냅샷은 rawSn 스코프(DATA_SRC_SN=NULL)라 복원 진입점이
        //    없는 write-only 이력이었다(D-ISSUE-25). 라벨은 보존되고, 신고~재비식별 구간의 PII 노출은
        //    라벨 조회 게이트({@code LabelAccessGuard.requireNotUnderDeidentReport}, S7)로 차단한다.
        //    resolve 로 'F'→'Y' 가 복원되면 게이트가 열려 보존된 라벨을 그대로 재사용한다.
        //    라벨을 지우지 않으므로 라벨셋 버전 bump(낙관적 락)도 하지 않는다 — bump 는 "열어둔 화면이
        //    방금 지운 라벨을 되살리는 것"을 막기 위한 장치였고, 삭제가 없으면 되살릴 대상 자체가 없다.
        //    (조회 게이트로 신고 구간 재조회가 막히고, 저장은 작업락으로 409 차단된다.)

        // 5-1) ★ 개인정보 3필드도 <b>보존</b>한다 (2026-08-04 사용자 확정 — 구 "리셋" 동작 폐기)
        //      구 동작: 신고 시 프레임 축(resetPrivacyMetaByRawSn)·영상 축(changePrivacyMeta(null,null,null))
        //        3필드를 전부 NULL 로 되돌리고 그 사실을 행 단위 감사(LS_DATA_LBL_HSTRY ·
        //        LS_TASK_EVNT_LOG PRIVACY_META_RESET)로 남겼다.
        //      구 근거(보존해 둔다): "그 판정은 <비식별이 잘못된 영상>에서 내려진 것이라 재판정 대상이고,
        //        남겨두면 재비식별 후에도 옛 판정이 export 에 stale 로 실린다(CWE-359)".
        //      ★ 폐기 사유: 위 5) 의 <b>라벨 보존 정책</b>(2026-07-27 확정 — 신고는 "비식별이 잘못됐다"는
        //        신호일 뿐 작업 결과를 폐기할 근거가 아니다)과 <b>같은 취지를 개인정보 3필드에도 적용</b>한다.
        //        사람이 입력한 판정도 라벨과 같은 작업 결과이므로 신고로 폐기하지 않으며, 해제(resolve)
        //        후 작업자가 <b>기존 판정을 그대로 이어서</b> 진행한다.
        //      stale 우려는 신고 구간 게이트가 이미 막는다 — 신고 중에는 export 산출 자체가 보류되고
        //        (DatasetExportService/TxService), 해제 시 재산출 + 관제 재통지가 트리거된다.
        //        해제 후 판정을 고쳐야 하면 기존 화면(PUT /v1/videos|frames/**/privacy-meta)으로 정정한다.

        // 5-2) ★ R2 — 구 동작(APPROVED 영상 신고 접수 시 TASK_MODIFIED(META_UPDATED) 발행)은 <b>제거됐다</b>.
        //      빠뜨린 것이 아니다: 위 2-4 게이트({@link #requireNotApprovedVideo})가 승인 영상의 접수
        //      자체를 412 로 막으므로 여기까지 오는 영상은 <b>정의상 미승인</b>이라 그 분기가 도달 불가다.
        //      (구 사유는 "비식별 상태 DE_IDNTF_YN 이 'F' 로 바뀌니 관제가 재픽업해야 한다"였다.)
        //      ⚠ {@link #resolveManually} 의 승인 분기는 <b>그대로 둔다</b> — 이 게이트 도입 이전에 접수돼
        //      아직 OPEN 인 신고(승인 영상 위)가 실재하며, 그 해소 경로까지 막으면 그 영상이
        //      작업락 + DE_IDNTF_YN='F' 로 영구 고착된다. 두 지점을 "대칭"을 이유로 함께 지우지 말 것.

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

        // privacyReset* 지표는 제거됐다 — 신고는 개인정보 3필드를 더 이상 리셋하지 않는다(2026-08-04).
        log.info("[DeidentReport] created rprtSn={} rawSn={} reporterNo={} labelsPreserved=true "
                        + "privacyMetaPreserved=true",
                report.getRprtSn(), rawSn, reporterNo);
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
     * ★ <b>마킹 단계 신고는 {@code MARKING_READY} 에서만 접수한다</b> (V171, 사용자 확정 — 구속).
     *
     * <h3>이 제한이 한 번에 없애는 문제 셋</h3>
     * <p>{@code MARKING_READY} 는 <b>선두 비식별 성공 직후 · 마킹 이전</b> 상태다. 프레임 추출은 마킹
     * 완료로 트리거되는 배치({@code FRAME_EXTRACT} 단계)에서 일어나고 그 배치는 진입 시 배치 단계를
     * {@code PROCESSING} 으로 전이하므로, {@code MARKING_READY} 인 영상에는 <b>프레임 행
     * ({@code LS_DATA_SRC})도 라벨({@code LS_DATA_LBL})도 아직 없다</b>. 따라서
     * <ol>
     *   <li>해소 후 <b>마킹 재실행이 파괴할 작업 결과가 없다</b>(라벨 유실 불가).</li>
     *   <li>검수 완료(APPROVED) 영상의 <b>강등 충돌이 발생하지 않는다</b> — 검수는 라벨링 이후 단계라
     *       APPROVED 영상의 배치 단계는 {@code COMPLETED} 이지 {@code MARKING_READY} 가 아니다.</li>
     *   <li><b>단계 판정이 상태로 확정된다</b> — 마킹 화면에서만 도달 가능한 상태이므로 진입점과
     *       실제 단계가 어긋날 수 없다.</li>
     * </ol>
     *
     * <h3>응답 코드·문구 — 상태 오라클이 되지 않게 (CWE-209)</h3>
     * <p>거부는 <b>412 {@link ErrorCode#PRECONDITION_FAILED}</b> 하나이며(이 프로젝트 신고 게이트 계열의
     * 표준 코드), 문구도 <b>하나</b>다. {@code PROCESSING}/{@code COMPLETED}/{@code FAILED}/{@code PENDING}
     * 을 구분해 알리지 않는다 — 구분하면 응답이 영상의 처리 단계를 알려주는 오라클이 된다(스트리밍만
     * 404 로 통일한 전례와 같은 취지). FE 는 애초에 {@code MARKING_READY} 가 아니면 버튼을 노출하지
     * 않으므로 이 경로는 API 직접 호출·낡은 화면에서만 도달한다.
     *
     * <p>라벨링 단계({@code srcSn} 진입점)는 <b>기존 동작 유지</b> — 프레임이 존재해야 도달하는 경로라
     * 배치 단계 제한을 걸면 정상 동선(검수 완료 후 신고 포함)이 막힌다.
     */
    private void requireMarkingStageAllowed(LsDataRaw raw, String stage) {
        if (!LsDeidentReport.STAGE_MARKING.equals(stage)) {
            return;
        }
        if (LsDataRaw.DATA_STTS_MARKING_READY.equals(raw.getDataSttsCd())) {
            return;
        }
        log.warn("[DeidentReport] rejected — marking-stage report requires MARKING_READY rawSn={} stage={}",
                raw.getRawSn(), kr.co.cudo.authoring.common.util.LogSanitizer.sanitize(raw.getDataSttsCd()));
        throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                "마킹 단계에서만 이 화면으로 비식별 누락을 신고할 수 있습니다. "
                        + "이미 다음 단계로 넘어간 영상은 라벨링 화면에서 신고해 주세요.");
    }

    // @design DFEAT-048 · @req R2 — 검수 승인 영상 신고 접수 차단 게이트.
    /**
     * ★ <b>검수가 승인된(APPROVED) 영상은 비식별 누락 신고를 접수하지 않는다</b> (R2, 사용자 확정 — 구속).
     *
     * <h3>판정은 재사용한다 — 새로 만들지 않는다</h3>
     * 승인 판정의 단일 원천은 {@link ReviewApprovalGate#isApproved(Long)} 다. 같은 쿼리가 14개 클래스에
     * 복제돼 있던 것을 그 컴포넌트 하나로 모은 것이 그 클래스의 존재 이유이므로, 여기서 상태 비교를
     * 재구현하지 않는다.
     *
     * <h3>배선 지점 — {@link #doReport} 한 곳</h3>
     * 두 진입점({@link #report} srcSn 축 · {@link #reportByVideo} rawSn 축)이 모두 {@link #doReport} 로
     * 수렴하므로 여기 한 번만 걸면 양쪽에 걸린다. 진입점마다 따로 배선하면 새는 것이 이 저장소의
     * 반복 결함이다({@link kr.co.cudo.authoring.video.service.DeidentReportGate} 가 그 해법의 선례).
     *
     * <h3>응답 규약 — 412, 역할 무관</h3>
     * 파생영상·비식별 미수행·마킹 단계 거부와 같은 계층의 <b>프리컨디션</b>이며 신고 게이트 계열 표준
     * 코드인 {@link ErrorCode#PRECONDITION_FAILED}(412)를 쓴다. <b>REVIEWER 도 막는다</b> — 인가 축이
     * 아니라 대상 리소스의 상태 때문에 거부되는 것이라 역할로 우회되지 않는다.
     *
     * <p>평가는 <b>작업락 409 검사보다 먼저</b> 한다. 잠금 여부에 따라 412/409 로 갈리면 응답이 잠금
     * 상태를 알려주는 오라클이 된다(CWE-209). 메시지도 처리 단계·잠금 상태를 유추할 정보를 담지 않는다.
     *
     * <h3>이미 접수된 신고는 건드리지 않는다</h3>
     * 이 게이트는 <b>신규 접수</b>만 막는다. 게이트 도입 이전에 승인 영상 위에 접수돼 아직 OPEN 인 신고는
     * {@link #resolveManually} 로 그대로 해소된다(그 경로의 승인 분기를 함께 막으면 그 영상이 작업락 +
     * {@code DE_IDNTF_YN='F'} 로 영구 고착된다).
     *
     * <h3>★ 거부해도 <b>사유는 감사 로그로 남긴다</b> — 로그가 유일한 기록이다 (CWE-778)</h3>
     * 파생영상 거부({@link #requireReportableVideo})와 <b>같은 관례</b>다. 승인 영상은 사용자가 취할 수
     * 있는 조치가 <b>하나도 없다</b>:
     * <ul>
     *   <li>신고 — 이 게이트가 412 로 막는다(신고 행 {@code LS_DEIDENT_REPORT} 미생성 → REVIEWER 알림도 없다).</li>
     *   <li>재비식별 요청 — {@code ApprovedRedeidentService.requestRedeident} 가 {@code DE_IDNTF_YN='Y'} 를
     *       409 로 배제한다.</li>
     *   <li>화면 — 재비식별 버튼 자체가 노출되지 않는다.</li>
     * </ul>
     * 따라서 이 로그를 지우면 <b>사용자가 발견한 개인정보 노출 사실이 어디에도 남지 않고 소실</b>된다.
     * "쓰이지 않는 로깅"으로 보고 제거하지 말 것. 사유는 사용자 자유 입력이라 반드시
     * {@link kr.co.cudo.authoring.common.util.LogSanitizer} 를 거친다(CWE-117 로그 인젝션 — 정제 함수를
     * 새로 만들지 않고 파생 거부 경로와 동일한 것을 재사용한다).
     */
    private void requireNotApprovedVideo(Long rawSn, String reason) {
        // ★ P2b — <b>지금 상태가 아니라 이력</b>으로 판정한다. ReviewStateMachine 이
        //   APPROVED → PENDING(WORKER 재검수 재제출)을 허용하므로, 현재 상태만 보면
        //   POST /v1/reviews/{videoId}/submit 한 번으로 상태를 내린 뒤 이 게이트가 그대로 뚫린다.
        //   막아야 하는 근거는 데이터마트 롤백 정합성이다(ReviewApprovalGate.hasEverApproved javadoc).
        //   판정은 그 단일 원천에만 두고 여기서 재유도하지 않는다.
        if (!approvalGate.hasEverApproved(rawSn)) {
            return;
        }
        // 신고 행도 알림도 생기지 않는 경로라 이 WARN 이 발견 사실의 유일한 기록이다(위 javadoc 참조).
        log.warn("[DeidentReport] rejected — review-approved video is out of the report workflow "
                        + "rawSn={} reason={}",
                rawSn, kr.co.cudo.authoring.common.util.LogSanitizer.sanitize(reason));
        throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                "검수가 완료된 영상은 비식별 누락을 신고할 수 없습니다.");
    }

    /**
     * R3 — 이 신고의 <b>재비식별 산출물 후보 목록</b> 조회
     * ({@code GET /v1/deident-reports/{rprtSn}/deident-candidates}).
     *
     * <p>인가는 {@link #resolveManually} 와 <b>동일</b>하다(WORKER 본인 배정 / REVIEWER 전체) — 고를 수
     * 있는 사람만 목록을 볼 수 있어야 한다. 디렉터리가 없거나 비었으면 <b>빈 목록 + 200</b> 이다
     * (에러가 아니다 — "아직 외부 비식별을 안 했다"는 정상 상태이며, 화면이 그 사실을 안내한다).
     *
     * <p>응답에는 <b>파일명만</b> 나가고 내부 저장 경로는 나가지 않는다(CWE-209 —
     * {@link kr.co.cudo.authoring.label.dto.DeidentCandidateResponse}).
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<kr.co.cudo.authoring.label.dto.DeidentCandidateResponse> listDeidentCandidates(
            Long rprtSn, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        LsDeidentReport report = reportRepository.findById(rprtSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "신고를 찾을 수 없습니다."));
        // IDOR — 조회도 해소와 같은 축으로 막는다(WORKER 본인 배정만, REVIEWER 전체).
        accessGuard.verifyRawAccess(report.getRawSn(), actor);

        return candidateFinder.find(report).stream()
                .map(kr.co.cudo.authoring.label.dto.DeidentCandidateResponse::from)
                .toList();
    }

    /**
     * 외부 솔루션 수동 비식별화 완료 후 신고 해소 (R1 v1.14 · R3 산출물 선택).
     *
     * <p>OPEN→RESOLVED 전이 + 작업락 해제를 동일 트랜잭션에서 처리(원자성).
     *
     * <ul>
     *   <li>미인증 → 401</li>
     *   <li>{@code fileName} 누락/공백 → 400 (<b>서버가 기본값을 고르지 않는다</b>)</li>
     *   <li>신고 없음 → 404</li>
     *   <li>WORKER 타인 영상 → 403 (verifyRawAccess), REVIEWER 전체 허용</li>
     *   <li>OPEN 아니면 → 409 (이미 RESOLVED/DISMISSED 재-resolve 차단)</li>
     *   <li>후보 목록에 없는 파일명 → 400 ({@link #selectArtifact} — 목록이 곧 허용목록, CWE-22)</li>
     *   <li>선택 파일이 <b>무결성</b> 미통과 → 409 (fail-closed: report OPEN·작업락·'F' 유지)</li>
     * </ul>
     *
     * <p>★ 산출물이 <b>신고 이후에 만들어졌는지는 보지 않는다</b>(@design ADR-027 · API-094) —
     * 신고 이전부터 있던 산출물, 곧 지금 쓰고 있는 비식별 영상을 그대로 골라도 해소가 성립한다.
     * 그 시간 조건으로 거부하던 경로는 2026-09-09 에 사라졌다(판정 지점은
     * {@code DeidentArtifactCandidateFinder.isEligibleForResolve} 한 곳이다).
     *
     * <h3>★ 선택 결과를 원장에 반영한다 — 안 하면 선택이 반쪽이 된다</h3>
     * <p>해소 이후의 프레임 재추출({@code DeidentFrameAttacher})·영상 스트리밍은 모두
     * {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM} 을 읽는다. 다른 이름의 새 산출물을 골라도
     * 원장을 갱신하지 않으면 <b>하류가 옛 파일을 계속 쓴다</b>. 그래서 {@link #recordResolvedArtifact}
     * 로 <b>새 SUCCESS 행을 INSERT</b> 한다(UPDATE 아님 — 이력 보존).
     */
    public void resolveManually(Long rprtSn, String fileName, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        // R3 — 선택 누락은 요청 자체가 불완전하다(컨트롤러 @Valid 우회 호출 방어).
        requireFileName(fileName);

        LsDeidentReport report = reportRepository.findById(rprtSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "신고를 찾을 수 없습니다."));

        // IDOR — WORKER 는 본인 배정 영상만, REVIEWER 는 전체 허용.
        accessGuard.verifyRawAccess(report.getRawSn(), actor);

        // OPEN 선제 검증 — 이미 RESOLVED/DISMISSED 면 409 (원자성: 전이+락해제는 동일 트랜잭션).
        if (!LsDeidentReport.REPORT_OPEN.equals(report.getReportSttsCd())) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 처리된 신고입니다.");
        }

        // 비식별 산출물 검증 게이트 — resolve 진행(RESOLVED 전이/락해제/'Y' 복원) 전에 선택 산출물의
        // 실재·무결성을 확인하고, 실패 시 즉시 거부한다. 예외 전파 시 트랜잭션이 롤백되어 report 는
        // OPEN, 작업락은 유지된다(fail-closed).
        // ★ 시간 조건(신고 이후 산출물인가)은 판정에 없다 — @design ADR-027 (2026-09-09).
        DeidentArtifactCandidateFinder.Candidate selected = selectArtifact(report, fileName);

        // ★ 원자 클레임 (CWE-362 — 2노드 Active-Active, V171): 위 OPEN 검증은 read-then-write 라
        //   두 노드가 동시에 통과할 수 있다. 전이를 조건부 UPDATE 로 수행하고 영향행수 1 을 받은
        //   <b>클레임 성공자만</b> 이후 부수효과(락 해제 · 'Y' 복원 · 재개 이벤트)를 진행한다.
        //   0행 = 다른 주체가 방금 처리 → 선제 검증과 동일한 409 로 수렴한다(계약 불변).
        int claimed = reportRepository.claimResolve(rprtSn,
                LsDeidentReport.REPORT_OPEN, LsDeidentReport.REPORT_RESOLVED, LocalDateTime.now());
        if (claimed != 1) {
            log.warn("[DeidentReport] resolve claim lost — already handled by another caller rprtSn={}", rprtSn);
            throw new CustomException(ErrorCode.CONFLICT, "이미 처리된 신고입니다.");
        }
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
                        raw -> {
                            raw.markDeidentified("Y");
                            // R3 — 선택한 산출물을 원장의 <b>새 성공 행</b>으로 적재한다. 클레임 성공
                            //   이후이므로 경쟁에서 진 노드는 여기까지 오지 않는다(고아 행 없음).
                            recordResolvedArtifact(raw, selected);
                        },
                        () -> log.warn("[DeidentReport] raw video not found on resolve rawSn={}",
                                report.getRawSn()));

        // 외부 수동 재비식별로 비식별본이 교체되었을 수 있으므로 스트림 메타 캐시를 커밋 후 무효화한다
        // (대상은 이 영상 하나 — 파생영상의 비식별본 파일은 파생 생성 시점 사본이라 바뀌지 않는다).
        streamMetaCacheEvictor.evictAfterCommit(report.getRawSn());

        // M1 — 신고 구간에 보류(차단)됐던 export·통지 복구를 트리거한다.
        publishResolvedForExportRecovery(report.getRawSn());

        // V171 — 신고 단계별 작업 재개(마킹 되감기 / 프레임 재추출). 단계 미상(NULL)이면 발행하지 않는다.
        publishStageResume(report.getRawSn(), report.getDclrStpCd());

        log.info("[DeidentReport] resolved-manually rprtSn={} rawSn={} stage={} actor={}",
                rprtSn, report.getRawSn(), report.getDclrStpCd(), actor.sub());
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
     *   <li>신고자 표시명({@code reporterName})은 페이지의 {@code USER_NO} 를 <b>단일 IN 쿼리</b>로 한 번에
     *       해석해 채운다 — 행마다 조회하면 N+1 이다({@code ReviewService}·{@code IssueThreadService} 와
     *       동일 패턴). 마스터에 없는 번호(탈퇴 등)는 null 로 남기고 목록 자체는 그대로 반환한다.</li>
     * </ul>
     *
     * @return 신고 목록 페이지 (DTO 변환 — Entity 직접 노출 금지)
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public org.springframework.data.domain.Page<kr.co.cudo.authoring.label.dto.DeidentReportListResponse> listReports(
            String status, org.springframework.data.domain.Pageable pageable) {
        String normalized = normalizeStatus(status);
        org.springframework.data.domain.Page<LsDeidentReport> page =
                reportRepository.findByReportSttsCd(normalized, pageable);
        kr.co.cudo.authoring.user.service.UserNameResolver.UserNames names =
                resolveReporterNames(page.getContent());
        return page.map(r -> kr.co.cudo.authoring.label.dto.DeidentReportListResponse.from(
                r, names.nameOf(r.getReporterNo())));
    }

    /**
     * 신고자 번호 → 표시명({@code LS_ACNT_USER.USER_NM}) 매핑을 단일 IN 쿼리로 조회한다(N+1 회피).
     *
     * <p>{@code LS_ACNT_USER}(V169, 저작도구 소유)는 여기서 <b>조회만</b> 한다 — 쓰기는 역할 클레임
     * 시점의 원자 upsert({@code UserRepository.upsertUser}) 한 곳뿐이다. 마스터에 없는 번호는
     * 맵에서 누락되어 호출 측이 자연히 null 로 처리한다.
     */
    private kr.co.cudo.authoring.user.service.UserNameResolver.UserNames resolveReporterNames(
            List<LsDeidentReport> reports) {
        java.util.List<Long> userNos = new java.util.ArrayList<>(reports.size());
        for (LsDeidentReport r : reports) {
            userNos.add(r.getReporterNo());
        }
        return userNameResolver.resolveAllByNo(userNos);
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
        // V171 — 재개할 단계 집합을 전이 <b>이전에</b> 수집한다(전이 후에는 OPEN 조회로 다시 얻을 수 없다).
        //        중복 단계는 한 번만 발행한다 — 같은 영상·같은 단계의 재개 작업은 동일하기 때문.
        java.util.Set<String> stages = new java.util.LinkedHashSet<>();
        for (LsDeidentReport r : opens) {
            if (r.getDclrStpCd() != null) {
                stages.add(r.getDclrStpCd());
            }
            r.resolve();
        }
        workLockService.releaseRaw(rawSn, "system", "DEIDENT_SUCCEEDED");
        // 배치/스텝 자동 재비식별 성공으로 비식별본이 교체되었으므로 스트림 메타 캐시를 커밋 후 무효화.
        streamMetaCacheEvictor.evictAfterCommit(rawSn);
        if (!opens.isEmpty()) {
            // M1 — 자동(배치) 해소 경로도 동일하게 보류됐던 export·통지를 복구한다. 실제로 해소한
            //      신고가 있을 때만 발행한다(신고가 없던 정상 비식별 성공은 재산출 대상이 아니다).
            publishResolvedForExportRecovery(rawSn);
            // V171 — 수동 경로와 동일하게 단계별 재개도 트리거한다. 단계 미상(NULL)만 있으면 발행 0건.
            stages.forEach(stage -> publishStageResume(rawSn, stage));
            log.info("[DeidentReport] resolved rawSn={} count={} stages={}", rawSn, opens.size(), stages);
        }
        return opens.size();
    }

    /**
     * M1 → Phase 7a-2 재배선 — 신고 해소로 export 게이트가 열렸음을 알린다.
     *
     * <p>신고 구간 export 차단은 {@code LS_DATASET_EXPORT} 행을 남기지 않아 실패 회수기(FAILED 행만
     * 스캔)가 집지 못한다. 따라서 <b>해제 시점 재트리거가 유일한 복구 경로</b>다.
     *
     * <h3>★ 복구 범위 = <b>해제된 영상 자신뿐</b> (2026-07-29 확정 정책과 대칭)</h3>
     * 차단 게이트({@link DeidentReportGate})가 <b>자기 rawSn 행만</b> 보므로, 어떤 신고가 막는 노드는
     * 정확히 <b>그 신고된 영상 하나</b>다. 파생영상은 원본 신고에 영향받지 않으므로 복구 대상도 아니다
     * (자손 팬아웃·상한·"다른 조상이 아직 신고 중인가" 판정이 모두 불필요해졌다 — 게이트 javadoc 의
     * "폐기된 안" 참조).
     *
     * <h3>Phase 7a-2 — 승인 영상은 <b>즉시 강제 재생성이 아니라</b> 재검토 표시만 세운다 (EVT-008)</h3>
     * <b>구 동작(폐기)</b>: 승인 영상이면 {@link DeidentReportResolvedEvent} 를 발행해
     * {@code DatasetExportBridge#onDeidentReportResolved} 가 <b>즉시</b> {@code force=true} 재생성 +
     * {@code TASK_COMPLETED} 통지를 트리거했다 — REVIEWER 의 재승인 없이 산출·통지가 자동으로 나갔다.
     * <p><b>새 동작</b>: {@link TaskModifiedEvent}(exportRegenerated=true, needsRecheck=true) 를 발행한다.
     * 이 한 이벤트가 <b>두 리스너 모두</b>를 동시에 태운다({@code TaskModifiedAccumulateListener} 가
     * 무조건 {@code ControlNotifyDebouncer} 에 축적 · {@code ReviewRecheckMarkListener} 가
     * {@code REVLT_YN='Y'} 로 표시) — 발행 1회로 "보류할 변경"과 "보류하라는 표시"가 함께 생긴다. 표시가
     * 서 있는 한 그 윈도우는 {@code LsMonNotiAcmlRepository#findFlushableAnchors} 의 {@code NOT EXISTS}
     * 필터에 걸려 flush 되지 않고(축적은 계속 쌓인다, 유실 아님), REVIEWER 가 재승인해 표시를 지우면
     * 다음 tick 에서 <b>force 재생성 + TASK_MODIFIED</b>(그 사이 축적분 포함)로 나간다.
     * ★ 강제 재생성(콘텐츠 해시 멱등 skip 우회) 자체는 그대로 필요하다 — 비식별 이미지가 disk 상에서
     * 교체됐는데 라벨 내용 해시는 그대로라 멱등 skip 이 그 교체를 반영하지 않기 때문이다. 이 요구는
     * {@code send()} 의 {@code runReExportThenNotify(rawSn, true, ...)} 호출(항상 force)로 이미 충족된다.
     * <p><b>인지·수용한 대가</b>: 재승인 전까지 관제는 마스킹 실패가 남은 직전 산출물을 계속 본다
     * (CLAUDE.md "재생성·통지의 트리거는 「검수 승인」한 곳이다" 절의 명시적 수용 사항과 동일 축).
     * <p>{@link DeidentReportResolvedEvent}/{@code DatasetExportBridge#onDeidentReportResolved} 는
     * 삭제하지 않는다 — 이 메서드가 더는 발행하지 않을 뿐, {@code DatasetReExportEvent} 와 같은
     * 휴면(dormant) 확장점으로 존치한다.
     *
     * <h3>{@link DeidentGateReopenedEvent} 는 <b>항상</b> — 별개 축, 손대지 않는다</h3>
     * 승인 여부와 무관하게 보류됐던 파이프라인 작업(특히 <b>VLM 시계열 위탁</b>)을 재개시킨다. VLM 보류는
     * 파이프라인 진행 중(=대개 미승인) 영상에서 일어나므로, 승인 영상에만 발행하면 시계열 메타가 영구
     * 결손된다.
     */
    private void publishResolvedForExportRecovery(Long rawSn) {
        if (rawSn == null) {
            return;
        }
        eventPublisher.publishEvent(new DeidentGateReopenedEvent(rawSn));
        if (approvalGate.isApproved(rawSn)) {
            eventPublisher.publishEvent(new TaskModifiedEvent(
                    rawSn, null, ChangeType.META_UPDATED, null, true, true));
        }
    }

    /**
     * V171 — 신고 단계별 작업 재개 이벤트 발행. 소비자는
     * {@link kr.co.cudo.authoring.label.listener.DeidentStageResumeBridge}(AFTER_COMMIT).
     *
     * <p><b>단계 미상(NULL)은 발행하지 않는다</b> — 컬럼 신설 이전 레거시 신고는 어디서 접수됐는지
     * 알 수 없고, 지어내면 마킹으로 오판정 시 <b>라벨이 있는 영상을 재마킹 대기로 되감는다</b>.
     * 발행하지 않으면 단계와 무관한 나머지({@link DeidentGateReopenedEvent} 항상 + 승인 시
     * {@code TaskModifiedEvent})만 도는 현행 동작이 그대로 유지된다(백필하지 않는다는 V171 정책과 세트).
     *
     * <p>그 2종은 이 이벤트와 <b>무관하게 그대로</b> 발행된다 — 각각 VLM 재개·재검토 표시 구독자의
     * 계약이며 신고 단계와 상관없이 필요하다.
     *
     * <p>⚠ {@link DeidentReportResolvedEvent} 는 <b>발행처가 없는 휴면 확장점</b>이라 이 서술의
     * "나머지"에 포함되지 않는다 — 위 {@link #publishResolvedForExportRecovery} javadoc 참조.
     */
    private void publishStageResume(Long rawSn, String stage) {
        if (rawSn == null || stage == null) {
            return;
        }
        eventPublisher.publishEvent(new DeidentStageResumeEvent(rawSn, stage));
    }

    // ---------- 내부 ----------

    /** R3 — 해소 요청의 산출물 선택 필수 검증(컨트롤러 {@code @Valid} 우회 호출 방어). */
    private void requireFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "재비식별 산출물 파일을 선택해 주세요.");
        }
    }

    /**
     * 비식별 산출물 검증 게이트 (CWE-359, fail-closed) — <b>사람이 고른</b> 산출물이 실제 재비식별
     * 결과일 때만 통과하고, 통과한 후보(실경로 포함)를 돌려준다.
     *
     * <h3>왜 "기록된 경로 1개" 판정을 버렸나 (R3)</h3>
     * <p>구 게이트는 원장에 기록된 경로 하나의 mtime 만 봤다 — 즉 외부 솔루션이 <b>같은 이름으로 제자리
     * 덮어쓰기</b> 하는 것을 전제한다. 실제 외부 솔루션(KPST)은 {@code {원본stem}-mask{ext}} 처럼 다른
     * 이름으로 산출하므로, 그런 경우 기록된 파일은 바뀌지 않아 <b>그 신고는 영원히 해소되지 않았다</b>
     * (작업락 + {@code DE_IDNTF_YN='F'} 영구 고착). 이제 서버가 산출 디렉터리를 열거해 후보를 만들고
     * 사람이 고른다.
     *
     * <h3>수락 규약 — 목록 대조로만 (CWE-22)</h3>
     * <p>{@code fileName} 으로 경로를 <b>조립하지 않는다</b>. 조회 API 와 <b>같은 열거 코드</b>
     * ({@link DeidentArtifactCandidateFinder})를 요청 시점에 다시 돌려, 그 결과에 이름이 있을 때만
     * 수락한다. 목록 키는 basename 이라 {@code ../…}·절대경로·심링크명은 어떤 항목과도 일치할 수 없다.
     *
     * <h3>판정은 위임한다 — 여기서 재구현하지 않는다</h3>
     * <ul>
     *   <li>경로 실재/실경로 — {@code VideoArtifactRootResolver.resolveRealPathUnder}(열거 시점)</li>
     *   <li>무결성 — {@code DeidentArtifactIntegrity.isValidVideoArtifact}(정규파일 + 크기 하한 +
     *       컨테이너 시그니처). 구 판정("&gt;0바이트")이 18바이트 텍스트 스텁을 통과시켜 실제 비식별
     *       없이 {@code 'F'→'Y'} 가 복원되던 결함(B-ISSUE-01, CWE-345)을 막는 단일 지점이다.</li>
     * </ul>
     *
     * <h3>시간 조건은 없다 (@design ADR-027 — 2026-09-09)</h3>
     * <p>구 게이트는 무결성에 더해 「신고 이후에 만들어졌는가」(mtime 또는 원장 완료시각 &gt; 신고시각)를
     * 요구했다. 그 조건을 충족시킬 새 산출물을 만드는 경로가 저작도구 안에 없어(재비식별 창구는 검수
     * 완료 영상 전용) 신고된 영상이 작업락 + {@code 'F'} 로 묶인 채 <b>해소할 문이 없었다</b>. 이제
     * 신고 이전부터 있던 산출물도 고를 수 있다 — 대가(재비식별 없이도 해소 가능)는 인지·수용했고,
     * 되돌릴 자리는 {@code DeidentArtifactCandidateFinder.isEligibleForResolve} 한 곳이다.
     *
     * <p>목록에 없는 이름은 400(요청이 가리키는 대상이 존재하지 않음), 목록에는 있으나 자격 미달이면
     * 409 다. 두 경우 모두 <b>내부 경로를 노출하지 않는다</b>(CWE-209). 예외 전파 → 트랜잭션 롤백 →
     * report OPEN 유지 + 작업락 유지 + deIdntfYn 'F' 유지(fail-closed).
     */
    private DeidentArtifactCandidateFinder.Candidate selectArtifact(LsDeidentReport report, String fileName) {
        Long rawSn = report.getRawSn();
        DeidentArtifactCandidateFinder.Candidate selected = candidateFinder.select(report, fileName)
                .orElseThrow(() -> {
                    // 파일명은 사용자 자유 입력이라 정제 후 남긴다(CWE-117). 경로는 남기지 않는다.
                    log.warn("[DeidentReport] resolve blocked — selected artifact is not a listed candidate "
                                    + "rawSn={} selected={}",
                            rawSn, kr.co.cudo.authoring.common.util.LogSanitizer.sanitize(fileName));
                    return new CustomException(ErrorCode.INVALID_INPUT,
                            "선택한 파일을 찾을 수 없습니다. 목록을 새로 고친 뒤 다시 선택해 주세요.");
                });
        if (!selected.eligible()) {
            throw deidentNotVerified(rawSn);
        }
        return selected;
    }

    /**
     * ★ R3 — 선택한 산출물을 {@code LS_DEIDENT_PROC_LOG} 의 <b>새 SUCCESS 행</b>으로 적재한다.
     *
     * <h3>왜 필수인가</h3>
     * <p>해소 이후의 프레임 재추출({@code DeidentFrameAttacher})·영상 스트리밍
     * ({@code VideoStreamService})은 전부 {@code DE_IDNTF_FILE_PATH_NM} 을 읽는다. 다른 이름의 새
     * 산출물을 골라도 이 적재가 없으면 <b>하류가 옛 파일을 계속 쓴다</b> — 목록 선택이 반쪽이 된다.
     *
     * <h3>왜 UPDATE 가 아니라 INSERT 인가</h3>
     * <p>이력 보존 + {@code findLatestSuccessByDataRawSn}(REQ_DT DESC, PROC_LOG_SN DESC)가 자연히 새 행을
     * 집는다. 기존 행을 갱신하면 어느 산출물이 이전 것이었는지 사라진다.
     *
     * <p>생성 방식은 다른 원장 생성부와 <b>동일</b>하다 — {@code LsDeidentProcLog.request(...)} 로
     * REQUESTED 행을 만들고 {@code succeed(경로)} 로 SUCCEEDED 로 마감한 뒤 저장한다
     * ({@code DeidentifyStep.runMock} · {@code ResolutionPersistService.persist} 와 같은 절차).
     * 원본 경로({@code ORGNL_FILE_PATH_NM}, NOT NULL)는 영상 자신의 {@code RAW_FILE_PATH_NM} 을 쓰고,
     * 그 값이 비어 있으면 직전 성공 원장의 값으로 폴백한다. 둘 다 없으면 <b>적재를 건너뛰고 WARN</b>
     * 한다 — 여기서 예외를 던지면 이미 클레임된 해소가 롤백되어 신고가 고착되기 때문이다(그 경우
     * 하류는 종전 경로를 계속 쓰며, 이는 이 변경 이전 동작과 같다).
     */
    private void recordResolvedArtifact(LsDataRaw raw, DeidentArtifactCandidateFinder.Candidate selected) {
        Long rawSn = raw.getRawSn();
        String orgnlPath = raw.getRawFilePathNm();
        if (orgnlPath == null || orgnlPath.isBlank()) {
            orgnlPath = procLogRepository.findLatestSuccessByDataRawSn(rawSn)
                    .map(LsDeidentProcLog::getOrgnlFilePathNm)
                    .orElse(null);
        }
        if (orgnlPath == null || orgnlPath.isBlank()) {
            log.warn("[DeidentReport] skip proc-log record — original video path unknown rawSn={}", rawSn);
            return;
        }
        LsDeidentProcLog procLog = LsDeidentProcLog.request(rawSn, null, orgnlPath, RESOLVE_PROC_REG_ID);
        procLog.succeed(selected.path().toString());
        procLogRepository.save(procLog);
        log.info("[DeidentReport] deident artifact re-pointed rawSn={} selected={}",
                rawSn, kr.co.cudo.authoring.common.util.LogSanitizer.sanitize(selected.fileName()));
    }

    /** 비식별 산출물 미검증 거부 예외 — 내부 경로 미노출, 외부 비식별 완료 후 재시도 안내. */
    private CustomException deidentNotVerified(Long rawSn) {
        log.warn("[DeidentReport] resolve blocked — deident artifact not verified rawSn={}", rawSn);
        return new CustomException(ErrorCode.CONFLICT,
                "비식별 산출물이 확인되지 않습니다. 외부 솔루션으로 비식별을 완료한 뒤 다시 시도하세요.");
    }

}
