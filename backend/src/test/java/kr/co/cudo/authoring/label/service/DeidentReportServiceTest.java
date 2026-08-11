package kr.co.cudo.authoring.label.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.common.cache.StreamMetaCacheEvictor;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.label.event.DeidentGateReopenedEvent;
import kr.co.cudo.authoring.label.event.DeidentReportResolvedEvent;
import kr.co.cudo.authoring.label.event.DeidentStageResumeEvent;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.support.TestVideoFixtures;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2 (R1 v1.14) — DeidentReportService 단위 테스트 (Mockito 기반).
 *
 * <p>R1 v1.14 정합 변경:
 * <ul>
 *   <li><b>D-25(2026-07-27 정책 반전)</b>: 신고 시 라벨을 <b>삭제하지 않고 보존</b>한다 — 스냅샷·삭제
 *       이력도 남기지 않는다. 신고 구간의 PII 노출은 라벨 조회 게이트(S7)로 차단한다.</li>
 *   <li>자동 재비식별 큐 적재(retryQueue) 제거 — 외부 솔루션 수동 비식별화로 대체.</li>
 *   <li>{@link DeidentReportService#resolveManually} — OPEN→RESOLVED + 작업락 해제 + IDOR 검증.</li>
 * </ul>
 */
class DeidentReportServiceTest {

    private LabelAccessGuard accessGuard;
    private VideoRepository videoRepository;
    private LsDeidentReportRepository reportRepository;
    private BatchRetryQueue retryQueue;
    private NotificationService notificationService;
    private WorkLockService workLockService;
    private kr.co.cudo.authoring.batch.repository.LsDataSrcRepository srcRepository;
    private ReviewApprovalGate approvalGate;
    private ApplicationEventPublisher eventPublisher;
    private StreamMetaCacheEvictor streamMetaCacheEvictor;
    private LsDeidentProcLogRepository procLogRepository;
    private LsDataLblHstryRepository lblHstryRepository;
    private LsTaskEventLogRepository taskEventLogRepository;
    private kr.co.cudo.authoring.user.repository.UserRepository userRepository;
    /** R3 — 실물 후보 열거기(협력자만 목). 수락 규약을 우회하지 않기 위해 목으로 두지 않는다. */
    private DeidentArtifactCandidateFinder candidateFinder;
    private DeidentReportService service;

    /**
     * 테스트용 산출물 루트 리졸버 — 비식별 저장소 base 를 {@link #tempDir} 로 둔다.
     *
     * <p>대부분의 시나리오는 디렉터리 열거가 아니라 <원장이 가리키는 현재 산출물> 판정을 다루므로,
     * 산출물을 {@code tempDir} <b>바로 아래</b>에 두어 열거 대상({@code {base}/videos/{rawSn}/})과
     * 겹치지 않게 한다 — 그 시나리오들의 열거 결과는 0건이고 후보는 현재 산출물 하나로 수렴한다.
     * 반대로 "다른 이름의 새 산출물" 회귀 가드는 {@code {base}/videos/{rawSn}/} 에 파일을 만들어
     * 열거 경로를 실제로 태운다.
     */
    private kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver testArtifactRootResolver() {
        return new kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver(
                "", "", tempDir.toString(), tempDir.toString(),
                tempDir.resolve("labeling").toString(), "co-locate");
    }

    private TokenClaims workerActor;
    private TokenClaims reviewerActor;

    /**
     * 거부 경로의 감사 로그 캡처 — 신고 행이 생성되지 않는 경로(파생영상·검수 승인)는 로그가
     * <b>유일한 기록</b>이라 그 존재 자체를 테스트로 고정한다(CWE-778).
     */
    private ListAppender<ILoggingEvent> logAppender;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        accessGuard = mock(LabelAccessGuard.class);
        videoRepository = mock(VideoRepository.class);
        reportRepository = mock(LsDeidentReportRepository.class);
        retryQueue = mock(BatchRetryQueue.class);
        notificationService = mock(NotificationService.class);
        workLockService = mock(WorkLockService.class);
        srcRepository = mock(kr.co.cudo.authoring.batch.repository.LsDataSrcRepository.class);
        approvalGate = mock(ReviewApprovalGate.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        streamMetaCacheEvictor = mock(StreamMetaCacheEvictor.class);
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        // D-25 (2026-07-27 정책 반전) — 신고는 라벨을 삭제하지 않으므로 라벨/스냅샷/이력 협력자
        //   (VersionService·LsDataLblRepository·ATTR_VAL·AI_INFO·LBL_HSTRY)가 의존성에서 제거됐다.
        // ★ 2026-08-04 — 개인정보 3필드 <b>리셋도 폐기</b>되면서 그 리셋의 행 단위 감사 협력자
        //   (LS_DATA_LBL_HSTRY · LS_TASK_EVENT_LOG)와 프레임 리포지토리 의존성이 함께 제거됐다.
        // 2026-07-29 — 신고 해소 복구 범위가 "해제된 영상 하나"로 축소되면서(파생영상은 원본 신고와
        //   무관) 자손 전개·게이트 재판정 의존성이 제거됐다.
        // 신고 목록의 신고자 표시명 해석용 — 본 단위 테스트는 목록 경로를 다루지 않아 stub 만 주입한다
        // (목록 응답의 reporterName 은 DeidentReportControllerTest 가 실 DB 로 검증).
        userRepository = mock(kr.co.cudo.authoring.user.repository.UserRepository.class);
        // R3 — 산출물 후보 열거는 <실물>을 쓴다. 이 컴포넌트를 목으로 바꾸면 "목록이 곧 허용목록"이라는
        //   수락 규약(CWE-22)이 테스트에서 통째로 우회되어, 경로 순회·미등재 파일 거부가 미검증이 된다.
        //   협력자(videoRepository·procLogRepository)만 목이므로 아래 시나리오에서는
        //   ① 영상 조회가 비어 co-locate 디렉터리가 도출되지 않고 ② 저장소 base 하위 디렉터리도 없어
        //   열거 결과가 0건이며, 후보는 <원장이 가리키는 현재 산출물> 하나로 수렴한다(구 판정과 동일 범위).
        //   디렉터리 열거 자체는 DeidentArtifactCandidateFinderTest 가 실파일로 검증한다.
        candidateFinder = new DeidentArtifactCandidateFinder(
                videoRepository, procLogRepository, testArtifactRootResolver());
        service = new DeidentReportService(accessGuard, videoRepository, reportRepository,
                notificationService, workLockService,
                approvalGate, eventPublisher,
                streamMetaCacheEvictor, procLogRepository, candidateFinder,
                new kr.co.cudo.authoring.user.service.UserNameResolver(userRepository));

        workerActor = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(60));
        reviewerActor = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
        when(accessGuard.parseUserNo("100")).thenReturn(100L);
        when(accessGuard.parseUserNo("1")).thenReturn(1L);
        // V171 — resolve 전이는 조건부 UPDATE(원자 클레임)로 수행된다. 기본 스텁은 "클레임 성공(1행)".
        //   0행(동시 resolve 패배) 케이스는 전용 테스트에서 개별 스텁한다.
        when(reportRepository.claimResolve(anyLong(), anyString(), anyString(), any())).thenReturn(1);

        // 로거는 JVM 전역 싱글턴이라 테스트마다 붙였다 떼지 않으면 appender 가 누적된다(@AfterEach 참조).
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger().addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger().detachAppender(logAppender);
        logAppender.stop();
    }

    private Logger serviceLogger() {
        return (Logger) org.slf4j.LoggerFactory.getLogger(DeidentReportService.class);
    }

    private LsDataSrc src(long srcSn, long rawSn) {
        LsDataSrc s = LsDataSrc.create(rawSn, 0, "/raw/0.jpg", LocalDateTime.now());
        setField(s, "srcSn", srcSn);
        return s;
    }

    /**
     * 신고 대상 영상 픽스처.
     *
     * <p>DEV_FIX(L-2) — 비식별 완료('Y') 상태로 만든다. {@code createFromIngest} 기본값은
     * {@code 'N'}(비식별 미수행)인데, 그 상태의 영상은 신고 대상이 아니다(프리컨디션 412) — 마킹·라벨링
     * 어느 화면도 비식별 전 영상을 보여주지 않으므로 사용자가 개인정보 노출을 발견할 수 없다.
     * 즉 구 픽스처가 비현실적이었고 프로덕션 가드가 맞다. {@code 'N'} 케이스는 전용 테스트에서 다룬다.
     *
     * <p>V171 — 배치 단계는 {@code MARKING_READY}(선두 비식별 성공 직후) 로 만든다. 마킹 단계
     * ({@code rawSn}) 신고는 이 상태에서만 접수되기 때문이다. 라벨링 단계({@code srcSn}) 신고는 배치
     * 단계를 보지 않으므로 이 값에 영향받지 않는다. {@code MARKING_READY} 가 아닌 케이스는 전용
     * 테스트에서 다룬다.
     */
    private LsDataRaw raw(long rawSn, String prvc) {
        LsDataRaw r = LsDataRaw.createFromIngest(
                "C-" + rawSn, "CCTV", "EVT", "GOV",
                prvc, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
        setField(r, "rawSn", rawSn);
        r.markDeidentified("Y");
        r.markMarkingReady();
        return r;
    }

    private void stubReportSave() {
        when(reportRepository.save(any(LsDeidentReport.class))).thenAnswer(inv -> {
            LsDeidentReport arg = inv.getArgument(0);
            setField(arg, "deidentReportSn", 555L);
            return arg;
        });
    }

    /**
     * P2b — 신고 게이트의 판정축은 <b>이력</b>({@code hasEverApproved})이다. 현재 상태를 보던 구
     * 판정({@code isApproved})은 재검수 재제출({@code APPROVED → PENDING})로 상태가 내려간 구간에
     * 뚫렸다.
     */
    private void stubApproved(long rawSn, boolean approved) {
        // ⚠ 두 축을 <b>함께</b> 스텁한다 — 프로덕션에서 "지금 승인"이면 "한번이라도 승인"도 반드시 참이다
        //   (hasEverApproved 가 현재 상태를 먼저 본다). 한쪽만 스텁하면 불가능한 조합이 되어, 그 조합에
        //   의존하는 단언이 실제로는 성립할 수 없는 상태를 검증하게 된다.
        //   · 신고 접수 게이트(requireNotApprovedVideo) → hasEverApproved (이력 축, P2b)
        //   · 해소 후 재산출 통지(publishResolvedForExportRecovery) → isApproved (현재 상태 축, 별개)
        when(approvalGate.hasEverApproved(rawSn)).thenReturn(approved);
        org.mockito.Mockito.lenient().when(approvalGate.isApproved(rawSn)).thenReturn(approved);
    }

    @Test
    @DisplayName("비식별_신고시_작업락과_DE_IDNTF_YN_F_전이는_유지되고_개인정보는_보존된다")
    void reportKeepsLockFlagAndPrivacyReset() {
        // given — D-25 정책 반전 회귀 방어: 라벨 삭제만 없어지고 나머지 부작용은 그대로여야 한다.
        LsDataSrc s = src(1L, 9001L);
        LsDataRaw r = raw(9001L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(1L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9001L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9001L)).thenReturn(false);
        stubReportSave();
        stubApproved(9001L, false);

        // when
        Long rprtSn = service.report(1L, "얼굴 미블러", workerActor);

        // then — 신고 저장 + 작업락 + 'F' 전이 + 스트림 캐시 무효화 유지. 개인정보 3필드는 <보존>된다
        //   (2026-08-04 리셋 폐기 — 아래 never() 가 회귀를 막는다).
        assertThat(rprtSn).isEqualTo(555L);
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService).lockRawForRedeident(9001L, "100");
        verify(srcRepository, never()).resetPrivacyMetaByRawSn(anyLong());  // 개인정보 보존(2026-08-04)
        verify(streamMetaCacheEvictor).evictAfterCommit(9001L);
        // 라벨을 지우지 않으므로 라벨셋 버전 bump(낙관적 락)도 하지 않는다.
        verify(srcRepository, never()).bumpLabelVersionByRawSn(anyLong());
    }

    /**
     * ★ 구 테스트 2건 폐기(2026-08-04) — {@code 영상축_개인정보_선언_리셋은_작업이벤트로그에_행단위로_감사된다} ·
     * {@code 영상축_수동_판정이_없으면_리셋_감사행을_만들지_않는다}.
     *
     * <p>폐기 사유: 신고가 <b>개인정보 3필드를 리셋하지 않게</b> 됐다(사용자 확정 — 라벨 보존 정책과
     * 같은 취지로 사람이 입력한 판정도 작업 결과이므로 보존). 리셋이 없으면 "리셋 감사"도 대상이 없다.
     * 구 근거("그 판정은 비식별이 잘못된 영상에서 내려진 것이라 재판정 대상")는 {@code DeidentReportService}
     * 주석에 보존돼 있다. 아래 대체 테스트가 <b>보존</b>을 고정한다.
     */
    @Test
    @DisplayName("신고해도_개인정보_3필드가_보존된다")
    void privacyMetaPreservedOnReport() {
        // given — 영상 축 수동 판정이 저장된 상태에서 신고
        LsDataSrc s = src(1L, 9401L);
        LsDataRaw r = raw(9401L, LsDataRaw.PRVC_TYPE_PRVC);
        r.changePrivacyMeta("N", "Y", "Y");
        when(accessGuard.verifyAndGet(eq(1L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9401L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9401L)).thenReturn(false);
        stubReportSave();
        stubApproved(9401L, false);

        // when
        service.report(1L, "얼굴 미블러", workerActor);

        // then — 값이 그대로 남는다. 해제(resolve) 후 작업자가 기존 판정을 그대로 이어서 진행한다.
        assertThat(r.getAnonyInclYn()).isEqualTo("N");
        assertThat(r.getPsdoInclYn()).isEqualTo("Y");
        assertThat(r.getPrvcInclYn()).isEqualTo("Y");
        // and — 프레임 축 리셋 벌크 UPDATE 도 호출되지 않는다(축이 비대칭이 되지 않게 둘 다 보존).
        verify(srcRepository, never()).resetPrivacyMetaByRawSn(anyLong());
    }

    @Test
    @DisplayName("해제_후_기존_판정을_그대로_이어간다")
    void privacyMetaSurvivesResolve() {
        // given — 신고로 'F' 가 된 영상 + 사람이 입력한 판정
        LsDataRaw r = raw(9403L, LsDataRaw.PRVC_TYPE_PRVC);
        r.changePrivacyMeta("N", "Y", "Y");
        r.markDeidentified("F");

        // when — 외부 솔루션 수동 비식별화 완료 → 'F'→'Y' 복원(게이트 자동 해제)
        r.markDeidentified("Y");

        // then — 별도 복원 API 없이 보존된 판정을 그대로 재사용한다(라벨 보존과 동일 시맨틱).
        assertThat(r.getAnonyInclYn()).isEqualTo("N");
        assertThat(r.getPsdoInclYn()).isEqualTo("Y");
        assertThat(r.getPrvcInclYn()).isEqualTo("Y");
    }

    // ───────────── 파생영상 신고 차단 (2026-07-29 사용자 확정) ─────────────

    @Test
    @DisplayName("파생영상은_비식별_누락_신고가_412로_거부된다 — 원본으로 유도하지 않는다")
    void reportRejectedForDerivativeVideo() {
        // given — 해상도/증강 파생본(ORGNL_RAW_SN=9200). 파생 프레임은 부모 비식별본의 사본이라
        //         재비식별 수단이 부모에만 있다 → 여기서 신고를 받으면 해소할 방법이 없다.
        LsDataSrc s = src(1L, 9201L);
        LsDataRaw derivative = raw(9201L, LsDataRaw.PRVC_TYPE_ANONY);
        setField(derivative, "orgnlRawSn", 9200L);
        when(accessGuard.verifyAndGet(eq(1L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9201L)).thenReturn(Optional.of(derivative));

        // when / then — 412. 원본을 신고해도 이 파생본은 달라지지 않으므로(자기 행 판정) 원본 영상번호를
        //   안내하지 않는다 — 따라가면 막다른 길(파생 배정 WORKER 는 원본에 403)이라 잘못된 정보다.
        assertThatThrownBy(() -> service.report(1L, "얼굴 미블러", workerActor))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                    assertThat(ce.getMessage()).contains("파생영상");
                    assertThat(ce.getMessage()).doesNotContain("9200");
                    assertThat(ce.getDetails()).isNull();
                });

        // then — 부작용이 하나도 일어나지 않는다(신고행·작업락·'F'·개인정보 리셋 전부 없음).
        //   REVIEWER 알림도 보내지 않는다 — 신고 행이 없어 처리할 워크플로가 없다(사유는 감사 로그로 보존).
        verify(notificationService, never()).notifyReviewersOnDeidentReport(any(), any(), any());
        verify(reportRepository, never()).save(any());
        verify(workLockService, never()).lockRawForRedeident(anyLong(), anyString());
        verify(srcRepository, never()).resetPrivacyMetaByRawSn(anyLong());
        assertThat(derivative.getDeIdntfYn()).isNotEqualTo("F");
    }

    @Test
    @DisplayName("비파생_원본영상은_기존대로_정상_신고된다")
    void reportAcceptedForNonDerivativeVideo() {
        // given — ORGNL_RAW_SN 이 없는 원본 영상(신고 진입점은 여기 하나다).
        LsDataSrc s = src(1L, 9210L);
        LsDataRaw origin = raw(9210L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(1L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9210L)).thenReturn(Optional.of(origin));
        when(workLockService.isRawLocked(9210L)).thenReturn(false);
        stubReportSave();
        stubApproved(9210L, false);

        // when
        Long rprtSn = service.report(1L, "얼굴 미블러", workerActor);

        // then
        assertThat(rprtSn).isEqualTo(555L);
        assertThat(origin.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService).lockRawForRedeident(9210L, "100");
    }

    // ───────────── DEV_FIX(L-2): 비식별 미수행 영상은 신고 접수 대상이 아니다 ─────────────

    @Test
    @DisplayName("비식별_미수행_영상은_신고가_412로_거부되고_N이_F로_전이되지_않는다")
    void reportRejectedWhenDeidentNotAttempted() {
        // given — deIdntfYn='N'(비식별 미실행, PENDING). rawSn 진입점(마킹)은 이 상태에 직접 도달한다.
        //   접수하면 'N'→'F' 로 hasDeidentArtifact() 가 거짓으로 true 가 되고(파생 부모 게이트 통과),
        //   재비식별한 적이 없어 resolve 전제가 성립하지 않는 작업락이 고착된다.
        LsDataRaw pending = LsDataRaw.createFromIngest(
                "C-9401", "CCTV", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
        setField(pending, "rawSn", 9401L);
        assertThat(pending.getDeIdntfYn()).isEqualTo("N");
        when(videoRepository.findByRawSnForUpdate(9401L)).thenReturn(Optional.of(pending));

        // when / then — 412 (파생 거부와 동일한 신고 게이트 계열 표준 코드).
        assertThatThrownBy(() -> service.reportByVideo(9401L, "얼굴 미블러", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        // then — 부작용 0. 특히 'N' 이 그대로 유지되어야 한다(판정 원천이 거짓이 되지 않는다).
        assertThat(pending.getDeIdntfYn()).isEqualTo("N");
        verify(reportRepository, never()).save(any());
        verify(workLockService, never()).lockRawForRedeident(anyLong(), anyString());
        verify(srcRepository, never()).resetPrivacyMetaByRawSn(anyLong());
        verify(notificationService, never()).notifyReviewersOnDeidentReport(any(), any(), any());
    }

    @Test
    @DisplayName("이미_신고된_F_영상의_재신고는_412가_아니라_기존_409_경로를_그대로_탄다")
    void reportOnAlreadyReportedVideoStillConflicts() {
        // given — 'F' 는 "산출물 있음 + 신고 중"이다. 여기서 412 로 바꾸면 기존 계약(409)이 깨진다.
        LsDataRaw reported = raw(9402L, LsDataRaw.PRVC_TYPE_PRVC);
        reported.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9402L)).thenReturn(Optional.of(reported));
        when(workLockService.isRawLocked(9402L)).thenReturn(true);

        assertThatThrownBy(() -> service.reportByVideo(9402L, "또 있음", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ───────────── B-ISSUE-28: 마킹 단계 rawSn 신고 진입점 ─────────────

    @Test
    @DisplayName("마킹단계_rawSn_신고시_작업락과_F전이가_srcSn경로와_동일하게_수행된다")
    void reportByVideoAppliesSameSideEffects() {
        // given — 마킹 화면에는 프레임(srcSn) 컨텍스트가 없다. rawSn 만으로 신고할 수 있어야 한다.
        LsDataRaw r = raw(9301L, LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findByRawSnForUpdate(9301L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9301L)).thenReturn(false);
        stubReportSave();
        stubApproved(9301L, false);

        // when
        Long rprtSn = service.reportByVideo(9301L, "번호판 미블러", workerActor);

        // then — srcSn 경로와 동일 부수효과(신고행·작업락·'F'·캐시 무효화·REVIEWER 알림).
        //   개인정보 3필드는 양쪽 경로 모두 <보존>된다(2026-08-04 리셋 폐기).
        assertThat(rprtSn).isEqualTo(555L);
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
        verify(reportRepository).save(any(LsDeidentReport.class));
        verify(workLockService).lockRawForRedeident(9301L, "100");
        verify(srcRepository, never()).resetPrivacyMetaByRawSn(anyLong());  // 개인정보 보존(2026-08-04)
        verify(streamMetaCacheEvictor).evictAfterCommit(9301L);
        verify(notificationService).notifyReviewersOnDeidentReport(any(), eq(100L), anyString());
        // 프레임 컨텍스트가 없으므로 srcSn 조회(IDOR 가드의 프레임 경로)는 타지 않는다.
        verify(accessGuard, never()).verifyAndGet(anyLong(), any());
        verify(accessGuard).verifyRawAccess(9301L, workerActor);
    }

    @Test
    @DisplayName("마킹단계_rawSn_신고도_파생영상이면_412로_거부되고_부작용이_없다")
    void reportByVideoRejectedForDerivative() {
        // given — 파생영상(증강·해상도). 재비식별 수단이 없어 접수 자체를 하지 않는다(확정 정책).
        LsDataRaw derivative = raw(9302L, LsDataRaw.PRVC_TYPE_ANONY);
        setField(derivative, "orgnlRawSn", 9300L);
        when(videoRepository.findByRawSnForUpdate(9302L)).thenReturn(Optional.of(derivative));

        // when / then — 412 + 원본 rawSn 미노출(따라가면 막다른 길이라 유도 자체가 잘못된 정보다).
        assertThatThrownBy(() -> service.reportByVideo(9302L, "얼굴 미블러", workerActor))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                    assertThat(ce.getMessage()).contains("파생영상");
                    assertThat(ce.getMessage()).doesNotContain("9300");
                });

        verify(reportRepository, never()).save(any());
        verify(workLockService, never()).lockRawForRedeident(anyLong(), anyString());
        verify(srcRepository, never()).resetPrivacyMetaByRawSn(anyLong());
        verify(notificationService, never()).notifyReviewersOnDeidentReport(any(), any(), any());
        assertThat(derivative.getDeIdntfYn()).isNotEqualTo("F");
    }

    @Test
    @DisplayName("마킹단계_rawSn_신고는_본인배정_아닌_WORKER를_403으로_차단한다")
    void reportByVideoForbiddenForNotAssignedWorker() {
        doThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."))
                .when(accessGuard).verifyRawAccess(eq(9303L), eq(workerActor));

        assertThatThrownBy(() -> service.reportByVideo(9303L, "사유", workerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        // 인가 실패는 영상 조회 이전에 끝난다(IDOR 우선).
        verify(videoRepository, never()).findByRawSnForUpdate(anyLong());
        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("마킹단계_rawSn_신고_이미_잠금이면_409")
    void reportByVideoAlreadyLockedConflict() {
        LsDataRaw r = raw(9304L, LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findByRawSnForUpdate(9304L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9304L)).thenReturn(true);

        assertThatThrownBy(() -> service.reportByVideo(9304L, "사유", workerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(reportRepository, never()).save(any());
        assertThat(r.getDeIdntfYn()).isNotEqualTo("F");
    }

    @Test
    @DisplayName("마킹단계_rawSn_신고_존재하지_않는_영상은_404")
    void reportByVideoUnknownVideoNotFound() {
        when(videoRepository.findByRawSnForUpdate(9305L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reportByVideo(9305L, "사유", workerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("마킹단계_rawSn_신고_사유가_비면_400")
    void reportByVideoBlankReasonRejected() {
        assertThatThrownBy(() -> service.reportByVideo(9306L, "   ", workerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(videoRepository, never()).findByRawSnForUpdate(anyLong());
    }

    // ───────────── R2: 검수 승인 영상은 신고를 접수하지 않는다 (2026-08-10 확정) ─────────────
    //
    // ★ 구 테스트 2건 폐기 — {@code APPROVED_영상_신고시_TASK_MODIFIED_통지_발행} ·
    //   {@code 마킹단계_rawSn_신고도_APPROVED_영상이면_TASK_MODIFIED_통지가_발행된다}.
    //   폐기 사유: 승인 영상은 이제 접수 자체가 412 로 막혀 그 통지 분기가 <b>도달 불가</b>다.
    //   아래 3건이 그 반전을 고정한다(양 진입점 412 + 통지 미발행).

    @Test
    @DisplayName("검수가_승인된_영상은_라벨링축_신고가_412로_거부된다")
    void reportRejectedForReviewApprovedVideo() {
        // given — 검수 완료(APPROVED) 영상. 승인된 학습데이터 위에 신고를 새로 받지 않는다.
        LsDataSrc s = src(1L, 9501L);
        LsDataRaw approved = raw(9501L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(1L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9501L)).thenReturn(Optional.of(approved));
        when(workLockService.isRawLocked(9501L)).thenReturn(false);
        stubApproved(9501L, true);

        // when / then — 412 + 확정 문구. 처리 단계·잠금 상태를 유추할 정보를 담지 않는다(CWE-209).
        assertThatThrownBy(() -> service.report(1L, "얼굴 미블러", workerActor))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                    assertThat(ce.getMessage()).isEqualTo("검수가 완료된 영상은 비식별 누락을 신고할 수 없습니다.");
                });

        // then — 부작용 0(신고행·작업락·'F' 전이·REVIEWER 알림 전부 없음).
        verify(reportRepository, never()).save(any());
        verify(workLockService, never()).lockRawForRedeident(anyLong(), anyString());
        verify(notificationService, never()).notifyReviewersOnDeidentReport(any(), any(), any());
        assertThat(approved.getDeIdntfYn()).isNotEqualTo("F");
    }

    @Test
    @DisplayName("한번이라도_승인된_영상은_신고를_받지_않는다 — 지금_상태가_아니라_이력으로_판정한다")
    void 한번이라도_승인된_영상은_신고를_받지_않는다() {
        // given — 지금은 승인 상태가 <b>아니지만</b>(현재 상태 판정은 false) 승인 이력이 있는 영상.
        LsDataSrc s = src(1L, 9601L);
        LsDataRaw video = raw(9601L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(1L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9601L)).thenReturn(Optional.of(video));
        when(approvalGate.hasEverApproved(9601L)).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> service.report(1L, "얼굴 미블러", workerActor))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRECONDITION_FAILED);
        verify(reportRepository, never()).save(any());
        // ★ 현재 상태(isApproved)를 보지 않는다 — 그 축으로 판정하면 재제출 구간이 뚫린다.
        verify(approvalGate, never()).isApproved(anyLong());
    }

    @Test
    @DisplayName("재제출로_상태가_내려간_구간에도_신고를_받지_않는다 — 실증된_구멍")
    void 재제출로_상태가_내려간_구간에도_신고를_받지_않는다() {
        // given — ReviewStateMachine 이 APPROVED → PENDING 을 허용하므로 WORKER 가 재제출하면 현재
        //   상태는 PENDING 이다. 그래도 이력 판정은 true 이므로 막혀야 한다.
        LsDataSrc s = src(1L, 9602L);
        LsDataRaw video = raw(9602L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(1L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9602L)).thenReturn(Optional.of(video));
        when(approvalGate.isApproved(9602L)).thenReturn(false);   // 현재 상태: 미승인
        when(approvalGate.hasEverApproved(9602L)).thenReturn(true); // 이력: 승인됨

        assertThatThrownBy(() -> service.report(1L, "얼굴 미블러", workerActor))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRECONDITION_FAILED);
        verify(reportRepository, never()).save(any());
        assertThat(video.getDeIdntfYn()).isNotEqualTo("F");
    }

    @Test
    @DisplayName("검수가_승인된_영상은_마킹축_신고도_412로_거부된다 — 두_진입점이_같은_게이트를_탄다")
    void reportByVideoRejectedForReviewApprovedVideo() {
        // given — 두 진입점이 doReport 한 곳으로 수렴하므로 게이트도 한 번만 배선하면 양쪽에 걸린다.
        //   (진입점마다 따로 배선하면 새는 것이 이 저장소의 반복 결함이라 이 테스트가 그 수렴을 고정한다.)
        LsDataRaw approved = raw(9502L, LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findByRawSnForUpdate(9502L)).thenReturn(Optional.of(approved));
        when(workLockService.isRawLocked(9502L)).thenReturn(false);
        stubApproved(9502L, true);

        // REVIEWER 로 호출한다 — 인가 축이 아니라 프리컨디션이므로 역할로 우회되지 않는다.
        assertThatThrownBy(() -> service.reportByVideo(9502L, "얼굴 미블러", reviewerActor))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                    assertThat(ce.getMessage()).isEqualTo("검수가 완료된 영상은 비식별 누락을 신고할 수 없습니다.");
                });

        verify(reportRepository, never()).save(any());
        verify(workLockService, never()).lockRawForRedeident(anyLong(), anyString());
        assertThat(approved.getDeIdntfYn()).isNotEqualTo("F");
    }

    @Test
    @DisplayName("검수_승인_영상_신고시_TASK_MODIFIED_통지가_발행되지_않는다 — 구_통지_분기_도달불가_고정")
    void approvedVideoReportPublishesNothing() {
        // given — 구 동작은 여기서 TaskModifiedEvent(META_UPDATED) 를 쐈다. 접수가 막히면 그 분기는
        //   도달 불가다. 분기를 되살리면(=게이트를 걷어내면) 이 never() 가 RED 가 된다.
        LsDataRaw approved = raw(9503L, LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findByRawSnForUpdate(9503L)).thenReturn(Optional.of(approved));
        when(workLockService.isRawLocked(9503L)).thenReturn(false);
        stubApproved(9503L, true);

        assertThatThrownBy(() -> service.reportByVideo(9503L, "사유", workerActor))
                .isInstanceOf(CustomException.class);

        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("검수_승인_영상_신고_거부시_사유가_정제되어_감사로그에_남는다 — 로그가_유일한_기록이다")
    void approvedVideoRejectionLogsSanitizedReason() {
        // given — 승인 영상은 사용자가 취할 수 있는 조치가 0 이다: 신고는 여기서 412 · 재비식별 요청은
        //   ApprovedRedeidentService 가 DE_IDNTF_YN='Y' 를 409 로 배제 · 화면 버튼도 안 뜬다.
        //   신고 행(LS_DEIDENT_REPORT)도 REVIEWER 알림도 생기지 않으므로, 이 WARN 이 사라지면
        //   사용자가 발견한 개인정보 노출 사실이 어디에도 남지 않는다(CWE-778 감사 누락).
        LsDataRaw approved = raw(9505L, LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findByRawSnForUpdate(9505L)).thenReturn(Optional.of(approved));
        when(workLockService.isRawLocked(9505L)).thenReturn(false);
        stubApproved(9505L, true);
        // 사유는 사용자 자유 입력 — 개행·제어문자로 가짜 로그 라인을 위조할 수 있다(CWE-117).
        String forgingReason = "00:12 얼굴 미블러\r\n[DeidentReport] created rprtSn=999\007";

        // when
        assertThatThrownBy(() -> service.reportByVideo(9505L, forgingReason, workerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        // then — 사유 본문이 기록된다(파생영상 거부 경로와 같은 관례).
        String logged = warnLogContaining("review-approved video");
        assertThat(logged).contains("00:12 얼굴 미블러");
        assertThat(logged).contains("rawSn=9505");
        // and — 개행·제어문자는 LogSanitizer 가 제거해 로그 라인 위조가 불가능하다(정제 함수 재사용).
        assertThat(logged).doesNotContain("\n").doesNotContain("\r").doesNotContain("\007");
    }

    /** 캡처된 WARN 중 keyword 를 포함하는 첫 메시지(placeholder 치환 후). 없으면 실패시킨다. */
    private String warnLogContaining(String keyword) {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(keyword))
                .findFirst()
                .orElseGet(() -> {
                    throw new AssertionError("기대한 WARN 로그가 없다: " + keyword
                            + " / captured=" + logAppender.list);
                });
    }

    @Test
    @DisplayName("미승인_영상은_종전대로_신고가_접수된다 — R2_게이트가_정상_동선을_막지_않는다")
    void reportStillAcceptedForNotApprovedVideo() {
        // given — 검수 전(미승인) 영상. R2 게이트는 승인 영상만 막는다.
        LsDataSrc s = src(1L, 9504L);
        LsDataRaw notApproved = raw(9504L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(1L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9504L)).thenReturn(Optional.of(notApproved));
        when(workLockService.isRawLocked(9504L)).thenReturn(false);
        stubReportSave();
        stubApproved(9504L, false);

        // when
        Long rprtSn = service.report(1L, "얼굴 미블러", workerActor);

        // then — 신고행 + 작업락 + 'F' 전이 그대로.
        assertThat(rprtSn).isEqualTo(555L);
        assertThat(notApproved.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService).lockRawForRedeident(9504L, "100");
    }

    @Test
    @DisplayName("비식별누락신고_처리후_프레임_개인정보값_보존 (구 기대 '초기화' 폐기 — 2026-08-04)")
    void reportResetsFramePrivacyMeta() {
        // given
        LsDataSrc s = src(1L, 9101L);
        LsDataRaw r = raw(9101L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(1L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9101L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9101L)).thenReturn(false);
        stubReportSave();
        stubApproved(9101L, false);

        // when
        service.report(1L, "얼굴 미블러", workerActor);

        // then — ★ 구 기대(전체 프레임 3필드 NULL 리셋) 폐기: 라벨 보존 정책(D-25)과 <같은 취지>로
        //   사람이 입력한 판정도 작업 결과이므로 보존한다(사용자 확정 2026-08-04). 리셋 벌크 UPDATE 는
        //   더 이상 호출되지 않는다.
        verify(srcRepository, never()).resetPrivacyMetaByRawSn(anyLong());
    }

    @Test
    @DisplayName("신고시_재비식별_큐에_적재되지_않음")
    void reportDoesNotEnqueueRetry() {
        LsDataSrc s = src(3L, 9003L);
        LsDataRaw r = raw(9003L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(3L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9003L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9003L)).thenReturn(false);
        stubReportSave();
        stubApproved(9003L, false);

        service.report(3L, "사유", workerActor);

        verify(retryQueue, never()).enqueueIfRetryable(anyLong());
    }

    @Test
    @DisplayName("라벨_0건_영상_신고시에도_정상_처리")
    void reportWithNoLabelsSucceeds() {
        LsDataSrc s = src(4L, 9004L);
        LsDataRaw r = raw(9004L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(4L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9004L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9004L)).thenReturn(false);
        stubReportSave();
        stubApproved(9004L, false);

        Long rprtSn = service.report(4L, "사유", workerActor);

        assertThat(rprtSn).isEqualTo(555L);
        // 신고 저장·잠금·DE_IDNTF_F 는 정상.
        verify(reportRepository).save(any(LsDeidentReport.class));
        verify(workLockService).lockRawForRedeident(9004L, "100");
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
    }

    @Test
    @DisplayName("이미_잠금_영상_신고_거부_시_부작용이_없다")
    void rejectedReportHasNoSideEffects() {
        // given — 이미 잠금 → CONFLICT 거부
        LsDataSrc s = src(42L, 9042L);
        LsDataRaw r = raw(9042L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(42L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9042L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9042L)).thenReturn(true);

        // when / then — 거부 시 'F' 전이 없음(fail-closed). 리셋 호출이 없는 것은 거부와 무관하게
        //   상시 참이다(2026-08-04 리셋 폐기) — 되살아나면 이 never() 가 RED 가 된다.
        assertThatThrownBy(() -> service.report(42L, "사유", workerActor))
                .isInstanceOf(CustomException.class);
        verify(srcRepository, never()).resetPrivacyMetaByRawSn(anyLong());
        assertThat(r.getDeIdntfYn()).isNotEqualTo("F");
    }

    // ★ 구 테스트 폐기(R2, 2026-08-10) — {@code APPROVED_영상_신고시_TASK_MODIFIED_통지_발행}.
    //   승인 영상은 접수 자체가 412 로 막혀 통지 분기가 도달 불가다. 대체 검증은 위 R2 절의
    //   {@code approvedVideoReportPublishesNothing} 이 담당한다(구 기대 → 폐기, 근거 보존).

    @Test
    @DisplayName("미승인_영상_신고시_통지_미발행")
    void notApprovedVideoReportDoesNotPublish() {
        LsDataSrc s = src(6L, 9006L);
        LsDataRaw r = raw(9006L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(6L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9006L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9006L)).thenReturn(false);
        stubReportSave();
        stubApproved(9006L, false);

        service.report(6L, "사유", workerActor);

        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("동시_신고_unique_위반시_409")
    void concurrentReportUniqueViolationConflict() {
        LsDataSrc s = src(7L, 9007L);
        LsDataRaw r = raw(9007L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(7L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9007L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9007L)).thenReturn(false);
        stubReportSave();
        stubApproved(9007L, false);
        // 잠금 INSERT 시 동시 신고로 unique 제약 위반.
        doThrow(new DataIntegrityViolationException("unique"))
                .when(workLockService).lockRawForRedeident(9007L, "100");

        assertThatThrownBy(() -> service.report(7L, "사유", workerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("이미_잠금_영상_신고시_CONFLICT_409_+_저장_없음_+_잠금_재획득_없음")
    void alreadyLockedConflict() {
        LsDataSrc s = src(8L, 9008L);
        LsDataRaw r = raw(9008L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(8L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9008L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9008L)).thenReturn(true);

        assertThatThrownBy(() -> service.report(8L, "재신고", workerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(reportRepository, never()).save(any());
        verify(workLockService, never()).lockRawForRedeident(anyLong(), anyString());
    }

    @Test
    @DisplayName("존재하지_않는_영상_신고시_NOT_FOUND_404")
    void unknownVideoNotFound() {
        LsDataSrc s = src(9L, 9999L);
        when(accessGuard.verifyAndGet(eq(9L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.report(9L, "사유", workerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("reason_누락_빈문자열은_INVALID_INPUT_400")
    void blankReasonRejected() {
        assertThatThrownBy(() -> service.report(1L, "  ", workerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> service.report(1L, null, workerActor))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("원본_영상_경로는_변경되지_않음")
    void rawFilePathUnchanged() {
        LsDataSrc s = src(10L, 9010L);
        LsDataRaw r = raw(9010L, LsDataRaw.PRVC_TYPE_PRVC);
        String originalPath = r.getRawFilePathNm();
        when(accessGuard.verifyAndGet(eq(10L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9010L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9010L)).thenReturn(false);
        stubReportSave();
        stubApproved(9010L, false);

        service.report(10L, "사유", workerActor);

        assertThat(r.getRawFilePathNm()).isEqualTo(originalPath);
    }

    // ============================================================
    // resolveManually — 수동 비식별화 완료 후 OPEN→RESOLVED 전이
    // ============================================================

    private LsDeidentReport report(long rprtSn, long rawSn, String status) {
        LsDeidentReport rep = LsDeidentReport.createReport(rawSn, 100L, "사유");
        setField(rep, "deidentReportSn", rprtSn);
        setField(rep, "reportSttsCd", status);
        return rep;
    }

    /**
     * 비식별 산출물 검증 게이트 통과용 픽스처 — <b>실제 재생 가능한 최소 mp4</b>(1,546바이트)를 만들고,
     * 해당 rawSn 의 최신 성공 procLog 가 그 경로를 가리키도록 스텁한다.
     *
     * <p>판정이 {@code DeidentArtifactIntegrity}(정규파일 + 크기 하한 + 컨테이너 시그니처)로 단일화되어
     * 구 픽스처({@code new byte[]{1,2,3}} 같은 3바이트 더미)는 더 이상 통과하지 않는다 — 실제 산출물을
     * 대표하는 {@link TestVideoFixtures} 를 쓴다.
     */
    private void stubDeidentArtifact(long rawSn) {
        stubDeidentArtifact(rawSn, TestVideoFixtures.writeTinyMp4(tempDir.resolve("deid-" + rawSn + ".mp4")));
    }

    /** 지정한 산출물 파일을 가리키는 최신 성공 procLog 스텁(무결성 판정 케이스별 파일 주입용). */
    private void stubDeidentArtifact(long rawSn, Path artifact) {
        LsDeidentProcLog procLog = LsDeidentProcLog.request(
                rawSn, "req-" + rawSn, "/orgnl/" + rawSn + ".mp4", "system");
        procLog.succeed(artifact.toString());
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn)).thenReturn(Optional.of(procLog));
    }

    @Test
    @DisplayName("수동_비식별화_완료시_신고가_RESOLVED로_전이되고_작업락_해제")
    void resolveManuallyTransitionsAndReleasesLock() {
        LsDeidentReport rep = report(700L, 9700L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(700L)).thenReturn(Optional.of(rep));
        stubDeidentArtifact(9700L);

        service.resolveManually(700L, "deid-9700.mp4", reviewerActor);

        // V171 — 전이는 엔티티 setter 가 아니라 <b>조건부 UPDATE(원자 클레임)</b>로 수행된다.
        verify(reportRepository).claimResolve(eq(700L),
                eq(LsDeidentReport.REPORT_OPEN), eq(LsDeidentReport.REPORT_RESOLVED), any());
        verify(workLockService).releaseRaw(eq(9700L), anyString(), anyString());
        // 수동 재비식별 완료로 비식별본이 교체될 수 있으므로 커밋 후 스트림 메타 캐시 무효화 훅 호출.
        verify(streamMetaCacheEvictor).evictAfterCommit(9700L);
    }

    // ---------- 신고 해소 → 복구 발행 범위(해제된 영상 하나) ----------
    //
    // 구 테스트 2건(resolveRetriggersApprovedDescendantExport ·
    // resolveSkipsDescendantStillUnderAnotherAncestorReport)은 "부모 신고가 파생 export 도 막는다"는
    // 전제 위에 있었다. 2026-07-29 확정으로 그 전파 자체가 철회되어 전제가 사라졌으므로, 아래 두
    // 테스트(자기 영상만 복구 / 파생에는 발행하지 않음)로 대체했다.

    @Test
    @DisplayName("resolve시_승인영상은_자기_rawSn으로만_재검토_표시_통지가_발행된다")
    void resolveRetriggersOwnExportOnly() {
        // given — 신고 해소 대상(9740)은 검수 완료(APPROVED). 파생(9741)이 존재하지만 대상이 아니다.
        LsDeidentReport rep = report(740L, 9740L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(740L)).thenReturn(Optional.of(rep));
        stubDeidentArtifact(9740L);
        stubApproved(9740L, true);

        // when
        service.resolveManually(740L, "deid-9740.mp4", reviewerActor);

        // then — 자기 rawSn 으로만 재개 이벤트가 나가고, 파생영상 전개 조회 자체가 없다.
        //   Phase 7a-2(EVT-008) — 즉시 강제 재생성(DeidentReportResolvedEvent, 폐기된 구 배선)이 아니라
        //   재검토 표시만 세우는 TaskModifiedEvent(exportRegenerated=true, needsRecheck=true) 를 발행한다.
        //   이 한 이벤트가 디바운스 축적(TaskModifiedAccumulateListener)과 REVLT_YN='Y' 표시
        //   (ReviewRecheckMarkListener) 를 동시에 태워, 재승인 시점까지 산출·통지를 보류시킨다.
        verify(eventPublisher).publishEvent(
                new TaskModifiedEvent(9740L, null, ChangeType.META_UPDATED, null, true, true));
        verify(eventPublisher).publishEvent(new DeidentGateReopenedEvent(9740L));
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.<Object>argThat(
                arg -> arg instanceof TaskModifiedEvent e && java.util.Objects.equals(e.rawSn(), 9741L)));
        verify(streamMetaCacheEvictor).evictAfterCommit(9740L);
        verify(streamMetaCacheEvictor, never()).evictAfterCommit(9741L);
    }

    /**
     * ★ R2 회귀 가드 (2026-08-10) — <b>이 테스트를 지우지 말 것</b>.
     *
     * <p>승인 영상의 <b>신규 접수</b>는 412 로 막히지만({@code requireNotApprovedVideo}),
     * 게이트 도입 <b>이전에</b> 접수돼 아직 OPEN 인 신고는 실재한다. {@code resolveManually} 의
     * 승인 분기까지 "대칭"을 이유로 함께 막으면 그 영상들이 작업락 + {@code DE_IDNTF_YN='F'} 로
     * <b>영구 고착</b>된다(해소 경로가 사라지므로 되돌릴 수단이 없다).
     */
    @Test
    @DisplayName("승인영상에_이미_OPEN인_신고는_R2_게이트와_무관하게_여전히_해소된다")
    void resolveStillWorksForApprovedVideoWithLegacyOpenReport() {
        // given — 승인(APPROVED) 영상 위에 이미 열려 있는 신고 + 재비식별 산출물 검증 통과.
        LsDeidentReport rep = report(744L, 9780L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(744L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9780L, LsDataRaw.PRVC_TYPE_PRVC);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9780L)).thenReturn(Optional.of(r));
        stubDeidentArtifact(9780L);
        stubApproved(9780L, true);

        // when
        service.resolveManually(744L, "deid-9780.mp4", reviewerActor);

        // then — 전이·락 해제·'Y' 복원이 모두 종전대로 수행된다(고착 없음).
        verify(reportRepository).claimResolve(eq(744L),
                eq(LsDeidentReport.REPORT_OPEN), eq(LsDeidentReport.REPORT_RESOLVED), any());
        verify(workLockService).releaseRaw(eq(9780L), anyString(), anyString());
        assertThat(r.getDeIdntfYn()).isEqualTo("Y");
        // and — 승인 영상의 재검토 표시 통지도 그대로 발행된다(resolve 경로는 무변경).
        verify(eventPublisher).publishEvent(
                new TaskModifiedEvent(9780L, null, ChangeType.META_UPDATED, null, true, true));
    }

    @Test
    @DisplayName("미승인_영상_해소시_게이트_재개방만_발행되고_재검토_표시_통지는_없다 — VLM 보류 재개 경로 보존")
    void resolvePublishesReopenEvenWhenNotApproved() {
        // given — 파이프라인 진행 중(미승인) 영상. VLM 보류 재개는 이 경우에도 필요하다.
        LsDeidentReport rep = report(742L, 9760L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(742L)).thenReturn(Optional.of(rep));
        stubDeidentArtifact(9760L);
        stubApproved(9760L, false);

        // when
        service.resolveManually(742L, "deid-9760.mp4", reviewerActor);

        // then — Phase 7a-2: 미승인 영상은 여전히 재검토 표시 통지(TaskModifiedEvent) 대상이 아니다
        //   (approvalGate.isApproved 가드는 그대로 유지 — 불필요한 v1 생성 방지).
        verify(eventPublisher).publishEvent(new DeidentGateReopenedEvent(9760L));
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("OPEN이_아닌_신고_resolve_요청시_409")
    void resolveNonOpenConflict() {
        LsDeidentReport rep = report(701L, 9701L, LsDeidentReport.REPORT_RESOLVED);
        when(reportRepository.findById(701L)).thenReturn(Optional.of(rep));

        assertThatThrownBy(() -> service.resolveManually(701L, "deid-9701.mp4", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("타인_배정_영상_신고_WORKER가_resolve_요청시_403")
    void resolveByNotAssignedWorkerForbidden() {
        LsDeidentReport rep = report(702L, 9702L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(702L)).thenReturn(Optional.of(rep));
        // accessGuard 가 본인 배정 아님 → FORBIDDEN.
        doThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."))
                .when(accessGuard).verifyRawAccess(eq(9702L), eq(workerActor));

        assertThatThrownBy(() -> service.resolveManually(702L, "deid-9702.mp4", workerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("REVIEWER는_모든_신고_resolve_가능")
    void reviewerCanResolveAnyReport() {
        LsDeidentReport rep = report(703L, 9703L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(703L)).thenReturn(Optional.of(rep));
        stubDeidentArtifact(9703L);

        service.resolveManually(703L, "deid-9703.mp4", reviewerActor);

        verify(reportRepository).claimResolve(eq(703L),
                eq(LsDeidentReport.REPORT_OPEN), eq(LsDeidentReport.REPORT_RESOLVED), any());
        verify(workLockService).releaseRaw(eq(9703L), anyString(), anyString());
    }

    @Test
    @DisplayName("비식별신고_수동해소시_deIdntfYn이_Y로_복원된다")
    void resolveManuallyRestoresDeidentifiedFlag() {
        // given — 신고로 DE_IDENT_YN='F' 내려간 영상.
        LsDeidentReport rep = report(710L, 9710L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(710L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9710L, LsDataRaw.PRVC_TYPE_PRVC);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9710L)).thenReturn(Optional.of(r));
        stubDeidentArtifact(9710L);

        // when
        service.resolveManually(710L, "deid-9710.mp4", reviewerActor);

        // then — 수동 비식별화 완료 → 'Y' 복원 (마킹 게이트 재개방).
        assertThat(r.getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("비식별신고_수동해소후_마킹_생성이_허용된다")
    void resolveManuallyReopensMarkingGate() {
        // given — 마킹 단계 신고: dataSttsCd=MARKING_READY 유지, DE_IDENT_YN='F'.
        LsDeidentReport rep = report(711L, 9711L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(711L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9711L, LsDataRaw.PRVC_TYPE_PRVC);
        setField(r, "dataSttsCd", LsDataRaw.DATA_STTS_MARKING_READY);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9711L)).thenReturn(Optional.of(r));
        stubDeidentArtifact(9711L);

        // when
        service.resolveManually(711L, "deid-9711.mp4", reviewerActor);

        // then — 마킹 게이트 두 조건(deIdntfYn=='Y' && dataSttsCd==MARKING_READY) 모두 충족.
        assertThat(r.getDeIdntfYn()).isEqualTo("Y");
        assertThat(r.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
    }

    @Test
    @DisplayName("APPROVED_영상_신고_해소시_배치단계가_되감기지_않는다")
    void resolveManuallyDoesNotRewindCompletedStage() {
        // given — 검수완료(COMPLETED 배치 단계) 영상 신고 후 해소.
        LsDeidentReport rep = report(712L, 9712L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(712L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9712L, LsDataRaw.PRVC_TYPE_PRVC);
        setField(r, "dataSttsCd", LsDataRaw.DATA_STTS_COMPLETED);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9712L)).thenReturn(Optional.of(r));
        stubDeidentArtifact(9712L);

        // when
        service.resolveManually(712L, "deid-9712.mp4", reviewerActor);

        // then — 'Y' 복원은 하되 배치 단계는 COMPLETED 유지(MARKING_READY 역행 금지).
        assertThat(r.getDeIdntfYn()).isEqualTo("Y");
        assertThat(r.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_COMPLETED);
    }

    // ============================================================
    // 비식별 산출물 검증 게이트 (CWE-359, fail-closed)
    // ============================================================

    @Test
    @DisplayName("비식별파일_없이_resolve시_400_거부되고_deIdntfYn은_F유지_report는_OPEN유지")
    void resolveWithoutDeidentFileRejectedAndFailClosed() {
        // ★ R3 — 거부 코드가 409 → 400 으로 바뀌었다(계약 변경, 의도된 것):
        //   해소는 이제 "서버가 열거한 후보 목록에 그 파일명이 있을 때만" 수락한다. 파일이 실재하지
        //   않으면 애초에 후보로 열거되지 않으므로, 요청은 <존재하지 않는 대상>을 가리킨 것이라 400 이다.
        //   409 는 "목록에는 있으나 무결성·시간조건 미달"에 남는다. 어느 쪽이든 fail-closed 는 동일하다.
        // given — procLog 에 비식별 경로는 기록되어 있으나 실제 파일이 스토리지에 없음.
        LsDeidentReport rep = report(720L, 9720L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(720L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9720L, LsDataRaw.PRVC_TYPE_PRVC);
        r.markDeidentified("F");
        // 부재 파일 경로를 가리키는 procLog.
        LsDeidentProcLog procLog = LsDeidentProcLog.request(9720L, "req", "/orgnl/9720.mp4", "system");
        procLog.succeed(tempDir.resolve("does-not-exist.mp4").toString());
        when(procLogRepository.findLatestSuccessByDataRawSn(9720L)).thenReturn(Optional.of(procLog));

        // when / then — 400 거부 (실재하지 않는 파일은 후보로 열거되지 않는다).
        assertThatThrownBy(() -> service.resolveManually(720L, "does-not-exist.mp4", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // fail-closed — report OPEN 유지, deIdntfYn 'F' 유지, 작업락 미해제, 'Y' 미복원.
        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_OPEN);
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("후보가_하나도_없으면_resolve는_400으로_거부된다")
    void resolveWithoutProcLogRejected() {
        // given — 성공 처리 이력(procLog)도 없고 산출 디렉터리에도 파일이 없음 → 후보 0건.
        // ★ R3 — 구 계약은 409("비식별 산출물 미검증")였다. 이제 후보가 0건이면 어떤 파일명도 목록에
        //   없으므로 400 이다(화면은 후보 0건일 때 확인 버튼을 비활성화해 이 요청 자체를 막는다).
        LsDeidentReport rep = report(721L, 9721L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(721L)).thenReturn(Optional.of(rep));
        when(procLogRepository.findLatestSuccessByDataRawSn(9721L)).thenReturn(Optional.empty());

        // when / then — 400 거부.
        assertThatThrownBy(() -> service.resolveManually(721L, "deid-9721.mp4", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_OPEN);
        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("비식별파일_존재시_resolve성공_deIdntfYn_Y복원_마킹게이트_통과")
    void resolveWithValidDeidentFileSucceeds() {
        // given — 실존하는 비식별 파일(>0바이트) + 마킹 단계 신고.
        LsDeidentReport rep = report(722L, 9722L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(722L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9722L, LsDataRaw.PRVC_TYPE_PRVC);
        setField(r, "dataSttsCd", LsDataRaw.DATA_STTS_MARKING_READY);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9722L)).thenReturn(Optional.of(r));
        stubDeidentArtifact(9722L);

        // when
        service.resolveManually(722L, "deid-9722.mp4", reviewerActor);

        // then — 게이트 통과 → RESOLVED 전이 + 'Y' 복원 + 마킹 게이트 두 조건 충족.
        verify(reportRepository).claimResolve(eq(722L),
                eq(LsDeidentReport.REPORT_OPEN), eq(LsDeidentReport.REPORT_RESOLVED), any());
        assertThat(r.getDeIdntfYn()).isEqualTo("Y");
        assertThat(r.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        verify(workLockService).releaseRaw(eq(9722L), anyString(), anyString());
    }

    // ------------------------------------------------------------
    // 산출물 무결성 판정 단일화 (B-ISSUE-01) — DeidentArtifactIntegrity 위임
    //   구 판정("정규파일 + >0바이트")은 위장 산출물로도 'F'→'Y' 복원을 허용해,
    //   라벨 조회·export·스트리밍 게이트가 한꺼번에 열렸다(CWE-345 → PII 재노출).
    // ------------------------------------------------------------

    /** 목/외부 솔루션이 원본 없이 남기던 18바이트 텍스트 스텁 — 구 판정을 통과하던 대표 위장 산출물. */
    private static final byte[] TEXT_STUB_18B = "MOCK_DEIDENTIFIED\n".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("18바이트_스텁으로는_비식별_신고가_해제되지_않는다")
    void resolveWithTextStubArtifactRejected() throws Exception {
        // given — 신고로 'F' 내려간 영상 + 산출물 자리에 18바이트 텍스트 스텁(정규파일·>0바이트).
        LsDeidentReport rep = report(740L, 9740L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(740L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9740L, LsDataRaw.PRVC_TYPE_PRVC);
        setField(r, "dataSttsCd", LsDataRaw.DATA_STTS_MARKING_READY);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9740L)).thenReturn(Optional.of(r));
        Path stub = tempDir.resolve("stub-9740.mp4");
        Files.write(stub, TEXT_STUB_18B);
        stubDeidentArtifact(9740L, stub);
        // RED 고정 — 이 스텁은 구 판정("정규파일 + >0바이트")을 그대로 통과한다. 즉 아래 거부는
        // 파일이 없어서가 아니라 무결성 판정이 단일 지점에 위임됐기 때문임을 증명한다.
        assertThat(Files.size(stub)).isEqualTo(18L);
        assertThat(Files.isRegularFile(stub) && Files.size(stub) > 0).isTrue();

        // when / then — 409 거부. 위장 산출물로 게이트가 열리지 않는다.
        assertThatThrownBy(() -> service.resolveManually(740L, "stub-9740.mp4", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_OPEN);
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
        // Phase 7a-2 — 게이트 실패로 트랜잭션이 롤백되므로 재검토 표시 통지(TaskModifiedEvent)도
        //   나가지 않는다(구 DeidentReportResolvedEvent 배선을 대체한 것과 동일 지점).
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("시그니처가_없는_파일로는_복원되지_않는다")
    void resolveWithoutContainerSignatureRejected() throws Exception {
        // given — 크기 하한(512B)은 넘지만 알려진 영상 컨테이너 시그니처가 없는 파일(텍스트 덤프 등).
        LsDeidentReport rep = report(741L, 9741L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(741L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9741L, LsDataRaw.PRVC_TYPE_PRVC);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9741L)).thenReturn(Optional.of(r));
        byte[] noSignature = new byte[4096];
        java.util.Arrays.fill(noSignature, (byte) 'A');
        Path fake = tempDir.resolve("no-signature-9741.mp4");
        Files.write(fake, noSignature);
        stubDeidentArtifact(9741L, fake);
        // RED 고정 — 구 판정(정규파일 + >0바이트)은 물론 크기 하한까지도 통과하는 파일이다.
        assertThat(Files.isRegularFile(fake) && Files.size(fake) > 0).isTrue();

        // when / then — 크기만으로는 통과하지 못한다.
        assertThatThrownBy(() -> service.resolveManually(741L, "no-signature-9741.mp4", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_OPEN);
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("유효한_비식별_영상이면_정상적으로_해제되고_게이트가_풀린다")
    void resolveWithRealVideoArtifactSucceeds() {
        // given — 실제 재생 가능한 최소 mp4(1,546B)가 신고 이후 제자리 교체된 상태.
        //         판정 강화가 정상 산출물을 오탐 거부하지 않음을 고정한다(회귀 방어).
        LsDeidentReport rep = report(742L, 9742L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(742L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9742L, LsDataRaw.PRVC_TYPE_PRVC);
        setField(r, "dataSttsCd", LsDataRaw.DATA_STTS_MARKING_READY);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9742L)).thenReturn(Optional.of(r));
        stubDeidentArtifact(9742L);

        // when
        service.resolveManually(742L, "deid-9742.mp4", reviewerActor);

        // then — RESOLVED 전이 + 'Y' 복원(라벨 조회·export·스트리밍 게이트 자동 해제) + 작업락 해제.
        verify(reportRepository).claimResolve(eq(742L),
                eq(LsDeidentReport.REPORT_OPEN), eq(LsDeidentReport.REPORT_RESOLVED), any());
        assertThat(r.getDeIdntfYn()).isEqualTo("Y");
        assertThat(r.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        verify(workLockService).releaseRaw(eq(9742L), anyString(), anyString());
        verify(streamMetaCacheEvictor).evictAfterCommit(9742L);
    }

    @Test
    @DisplayName("해제_실패시_deIdntfYn_은_F_로_유지된다")
    void resolveFailureKeepsDeidentFlagF() throws Exception {
        // given — 시그니처는 mp4(ftyp)지만 크기 하한 미달로 잘린 산출물(전송 중단 등).
        //         검수완료(APPROVED) 영상이라 통과 시 export 복구까지 트리거되는 경로다.
        LsDeidentReport rep = report(743L, 9743L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(743L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9743L, LsDataRaw.PRVC_TYPE_PRVC);
        setField(r, "dataSttsCd", LsDataRaw.DATA_STTS_COMPLETED);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9743L)).thenReturn(Optional.of(r));
        stubApproved(9743L, true);
        byte[] truncated = java.util.Arrays.copyOf(TestVideoFixtures.tinyMp4Bytes(), 100);
        Path partial = tempDir.resolve("truncated-9743.mp4");
        Files.write(partial, truncated);
        stubDeidentArtifact(9743L, partial);

        // when / then — fail-closed: 예외 전파(트랜잭션 롤백) + 상태 무변경.
        assertThatThrownBy(() -> service.resolveManually(743L, "truncated-9743.mp4", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        assertThat(r.getDeIdntfYn()).isEqualTo("F");
        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_OPEN);
        assertThat(rep.getResolvedDt()).isNull();
        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
        verify(streamMetaCacheEvictor, never()).evictAfterCommit(anyLong());
        // Phase 7a-2 — 실패로 롤백되면 재검토 표시 통지(TaskModifiedEvent)도 나가지 않는다.
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    // ------------------------------------------------------------
    // 시간 조건 보강 (CWE-359) — 신고 이후 재비식별된 산출물만 통과
    // ------------------------------------------------------------

    @Test
    @DisplayName("신고이전_비식별본만_존재시_resolve_거부된다")
    void resolveWithPreReportArtifactRejected() throws Exception {
        // given — 신고를 유발한 그 비식별본(신고 이전 mtime + 신고 이전 procLog)만 존재.
        //         파일은 실존·>0바이트라 기존 존재 게이트는 통과하지만, 시간 조건에서 걸러져야 한다.
        LsDeidentReport rep = report(730L, 9730L, LsDeidentReport.REPORT_OPEN);
        LocalDateTime reportTime = LocalDateTime.now();
        setField(rep, "reportDt", reportTime);
        when(reportRepository.findById(730L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9730L, LsDataRaw.PRVC_TYPE_PRVC);
        r.markDeidentified("F");

        Path deidFile = TestVideoFixtures.writeTinyMp4(tempDir.resolve("pre-report-9730.mp4"));
        // 파일 mtime 을 신고보다 10분 과거로 강제 (스큐 60초를 훨씬 넘는 과거).
        Files.setLastModifiedTime(deidFile, FileTime.from(
                reportTime.minusMinutes(10).atZone(ZoneId.systemDefault()).toInstant()));
        LsDeidentProcLog procLog = LsDeidentProcLog.request(9730L, "req", "/orgnl/9730.mp4", "system");
        procLog.succeed(deidFile.toString());
        // procLog 완료시각도 신고 이전으로 강제 (옛 성공 이력).
        setField(procLog, "resDt", reportTime.minusMinutes(10));
        when(procLogRepository.findLatestSuccessByDataRawSn(9730L)).thenReturn(Optional.of(procLog));

        // when / then — 신고 이후 재비식별 산출물 미확인 → 409 거부, fail-closed.
        assertThatThrownBy(() -> service.resolveManually(730L, "pre-report-9730.mp4", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_OPEN);
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("신고이후_파일교체시_resolve_성공한다")
    void resolveWithPostReportFileReplacementSucceeds() throws Exception {
        // given — 외부 도구가 신고 이후 비식별본을 제자리 교체(mtime 최신). procLog 은 옛것(신고 이전).
        LsDeidentReport rep = report(731L, 9731L, LsDeidentReport.REPORT_OPEN);
        LocalDateTime reportTime = LocalDateTime.now().minusHours(1);
        setField(rep, "reportDt", reportTime);
        when(reportRepository.findById(731L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9731L, LsDataRaw.PRVC_TYPE_PRVC);
        setField(r, "dataSttsCd", LsDataRaw.DATA_STTS_MARKING_READY);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9731L)).thenReturn(Optional.of(r));

        // mtime = now (신고보다 1시간 후)
        Path deidFile = TestVideoFixtures.writeTinyMp4(tempDir.resolve("replaced-9731.mp4"));
        LsDeidentProcLog procLog = LsDeidentProcLog.request(9731L, "req", "/orgnl/9731.mp4", "system");
        procLog.succeed(deidFile.toString());
        // 파일 교체는 새 procLog 를 만들지 않음 — 완료시각은 신고 이전(옛 성공 이력).
        setField(procLog, "resDt", reportTime.minusMinutes(5));
        when(procLogRepository.findLatestSuccessByDataRawSn(9731L)).thenReturn(Optional.of(procLog));

        // when
        service.resolveManually(731L, "replaced-9731.mp4", reviewerActor);

        // then — mtime 조건으로 통과 → RESOLVED + 'Y' 복원.
        verify(reportRepository).claimResolve(eq(731L),
                eq(LsDeidentReport.REPORT_OPEN), eq(LsDeidentReport.REPORT_RESOLVED), any());
        assertThat(r.getDeIdntfYn()).isEqualTo("Y");
        verify(workLockService).releaseRaw(eq(9731L), anyString(), anyString());
    }

    @Test
    @DisplayName("신고이후_자동재비식별_procLog가_있으면_성공한다")
    void resolveWithPostReportProcLogSucceeds() throws Exception {
        // given — 신고 이후 자동 재비식별 성공(procLog 완료시각 최신). 파일 mtime 은 신고 이전이어도 통과.
        LsDeidentReport rep = report(732L, 9732L, LsDeidentReport.REPORT_OPEN);
        LocalDateTime reportTime = LocalDateTime.now().minusHours(1);
        setField(rep, "reportDt", reportTime);
        when(reportRepository.findById(732L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9732L, LsDataRaw.PRVC_TYPE_PRVC);
        setField(r, "dataSttsCd", LsDataRaw.DATA_STTS_MARKING_READY);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9732L)).thenReturn(Optional.of(r));

        Path deidFile = TestVideoFixtures.writeTinyMp4(tempDir.resolve("auto-9732.mp4"));
        // 파일 mtime 은 신고 이전으로 강제 (procLog 완료시각 단독으로 통과함을 격리 검증).
        Files.setLastModifiedTime(deidFile, FileTime.from(
                reportTime.minusMinutes(10).atZone(ZoneId.systemDefault()).toInstant()));
        LsDeidentProcLog procLog = LsDeidentProcLog.request(9732L, "req", "/orgnl/9732.mp4", "system");
        procLog.succeed(deidFile.toString());
        // 신고 이후 자동 재비식별 성공 → 완료시각 최신.
        setField(procLog, "resDt", LocalDateTime.now());
        when(procLogRepository.findLatestSuccessByDataRawSn(9732L)).thenReturn(Optional.of(procLog));

        // when
        service.resolveManually(732L, "auto-9732.mp4", reviewerActor);

        // then — procLog 완료시각 조건으로 통과 → RESOLVED + 'Y' 복원.
        verify(reportRepository).claimResolve(eq(732L),
                eq(LsDeidentReport.REPORT_OPEN), eq(LsDeidentReport.REPORT_RESOLVED), any());
        assertThat(r.getDeIdntfYn()).isEqualTo("Y");
        verify(workLockService).releaseRaw(eq(9732L), anyString(), anyString());
    }

    // ------------------------------------------------------------
    // 스큐 관용 방향 (B-ISSUE-42 / 1차 B-ISSUE-102 이월) — CWE-359
    //   구 비교식 mtime > (신고시각 - 60초) 은 "신고보다 최대 60초 과거"인 파일,
    //   즉 신고를 유발한 옛 비식별본까지 통과시켜 재비식별 없이 게이트를 열었다.
    //   관용을 감산 방향으로 두지 않는다 — procLog 완료시각 비교와 동일한 엄격 비교로 통일.
    // ------------------------------------------------------------

    /**
     * 신고시각을 초 단위로 절삭해 만든다 — 파일시스템 mtime 정밀도(초/밀리초 단위 절삭)에 좌우되지 않는
     * 결정적 경계값 비교를 위해서다(밀리초 이하 정밀도를 쓰면 FS 절삭만으로 경계 판정이 흔들린다).
     */
    private static LocalDateTime secondAlignedReportTime() {
        return LocalDateTime.now().minusMinutes(5).truncatedTo(ChronoUnit.SECONDS);
    }

    /** mtime 단독 판정을 격리하기 위해 procLog 완료시각은 항상 신고 이전으로 고정한 산출물 스텁. */
    private Path stubArtifactWithMtime(long rawSn, LocalDateTime reportTime, LocalDateTime mtime)
            throws Exception {
        Path deidFile = TestVideoFixtures.writeTinyMp4(tempDir.resolve("skew-" + rawSn + ".mp4"));
        Files.setLastModifiedTime(deidFile, FileTime.from(
                mtime.atZone(ZoneId.systemDefault()).toInstant()));
        LsDeidentProcLog procLog = LsDeidentProcLog.request(
                rawSn, "req-" + rawSn, "/orgnl/" + rawSn + ".mp4", "system");
        procLog.succeed(deidFile.toString());
        setField(procLog, "resDt", reportTime.minusMinutes(10));
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn)).thenReturn(Optional.of(procLog));
        return deidFile;
    }

    @Test
    @DisplayName("resolve_검증시_비식별본_mtime이_신고시각보다_이전이면_거부된다")
    void resolveRejectedWhenArtifactMtimeIsBeforeReportTime() throws Exception {
        // given — mtime = 신고시각 - 30초. 구 스큐 관용(60초)의 감산 창 안이라 예전엔 통과하던 케이스이며,
        //         실체는 "신고 이전부터 있던 그 비식별본"이다(재비식별 없음).
        LsDeidentReport rep = report(750L, 9750L, LsDeidentReport.REPORT_OPEN);
        LocalDateTime reportTime = secondAlignedReportTime();
        setField(rep, "reportDt", reportTime);
        when(reportRepository.findById(750L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9750L, LsDataRaw.PRVC_TYPE_PRVC);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9750L)).thenReturn(Optional.of(r));
        Path deidFile = stubArtifactWithMtime(9750L, reportTime, reportTime.minusSeconds(30));
        // 스큐 감산 창(60초) 안임을 고정 — 이 테스트가 "10분 과거" 케이스의 중복이 아님을 증명한다.
        assertThat(Files.getLastModifiedTime(deidFile).toInstant())
                .isAfter(reportTime.minusSeconds(60).atZone(ZoneId.systemDefault()).toInstant())
                .isBefore(reportTime.atZone(ZoneId.systemDefault()).toInstant());

        // when / then — 409 거부, fail-closed (게이트 유지).
        assertThatThrownBy(() -> service.resolveManually(750L, "skew-9750.mp4", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_OPEN);
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
        verify(streamMetaCacheEvictor, never()).evictAfterCommit(anyLong());
    }

    @Test
    @DisplayName("resolve_검증시_비식별본_mtime이_신고시각_이후면_정상_통과한다")
    void resolveAcceptedWhenArtifactMtimeIsAfterReportTime() throws Exception {
        // given — mtime = 신고시각 + 1초. 신고 직후 외부 솔루션이 제자리 교체한 정상 재비식별 케이스로,
        //         스큐 관용 제거가 정상 경로를 오탐 거부하지 않음을 고정한다(회귀 방어).
        LsDeidentReport rep = report(751L, 9751L, LsDeidentReport.REPORT_OPEN);
        LocalDateTime reportTime = secondAlignedReportTime();
        setField(rep, "reportDt", reportTime);
        when(reportRepository.findById(751L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9751L, LsDataRaw.PRVC_TYPE_PRVC);
        setField(r, "dataSttsCd", LsDataRaw.DATA_STTS_MARKING_READY);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9751L)).thenReturn(Optional.of(r));
        stubArtifactWithMtime(9751L, reportTime, reportTime.plusSeconds(1));

        // when
        service.resolveManually(751L, "skew-9751.mp4", reviewerActor);

        // then — RESOLVED 전이 + 'Y' 복원 + 작업락 해제.
        //   ⚠ V171(원자 클레임) 이후 전이는 엔티티 setter 가 아니라 <조건부 UPDATE> 로 수행되므로
        //     rep.getReportSttsCd() 는 OPEN 그대로다. 전이 사실은 claimResolve 호출로 단정한다.
        verify(reportRepository).claimResolve(eq(751L),
                eq(LsDeidentReport.REPORT_OPEN), eq(LsDeidentReport.REPORT_RESOLVED), any());
        assertThat(r.getDeIdntfYn()).isEqualTo("Y");
        verify(workLockService).releaseRaw(eq(9751L), anyString(), anyString());
    }

    @Test
    @DisplayName("resolve_검증시_비식별본_mtime이_신고시각과_동일하면_경계값_처리를_확인한다")
    void resolveRejectedWhenArtifactMtimeEqualsReportTime() throws Exception {
        // given — mtime == 신고시각(정확히 동일). 기대 동작은 <거부>다: 동일 시각이면 그 파일은
        //         신고 시점에 이미 존재하던 산출물(=신고를 유발한 그 파일)이며, 재비식별로 교체됐다는
        //         증거가 아니다. 게이트는 fail-closed 이므로 '증거 없음'은 거부로 수렴한다.
        LsDeidentReport rep = report(752L, 9752L, LsDeidentReport.REPORT_OPEN);
        LocalDateTime reportTime = secondAlignedReportTime();
        setField(rep, "reportDt", reportTime);
        when(reportRepository.findById(752L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9752L, LsDataRaw.PRVC_TYPE_PRVC);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9752L)).thenReturn(Optional.of(r));
        Path deidFile = stubArtifactWithMtime(9752L, reportTime, reportTime);
        // mtime 이 실제로 신고시각과 동일하게 기록됐음을 고정(FS 절삭으로 경계가 밀리지 않았는지 확인).
        assertThat(Files.getLastModifiedTime(deidFile).toInstant())
                .isEqualTo(reportTime.atZone(ZoneId.systemDefault()).toInstant());

        // when / then — 409 거부, fail-closed.
        assertThatThrownBy(() -> service.resolveManually(752L, "skew-9752.mp4", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_OPEN);
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
    }

    // ------------------------------------------------------------
    // procLog 분기 경계값 — mtime 분기와의 <대칭> 고정 (B-ISSUE-42 보강)
    //   verifyDeidentArtifact 는 독립된 두 시각 비교를 OR 로 묶는다:
    //     (1) procTime.isAfter(reportTime)   — DeidentReportService.java:586
    //     (2) mtime.isAfter(reportTime)      — DeidentReportService.java:607
    //   (2)는 105a/b/c 로 경계가 고정됐으나 (1)은 "신고 이전 / 한참 이후"만 있고
    //   <정확히 동일 시각> 케이스가 없어, 누군가 (1)만 관대 비교(!isBefore 등)로
    //   되돌려도 잡히지 않았다. 두 분기 모두 엄격 비교임을 대칭으로 못 박는다.
    // ------------------------------------------------------------

    /**
     * procLog 완료시각 단독 판정을 격리하기 위해 파일 mtime 을 항상 신고 이전으로 고정한 산출물 스텁.
     * ({@link #stubArtifactWithMtime} 의 정확한 대칭 — 그쪽은 procLog 을 신고 이전으로 고정한다.)
     */
    private Path stubArtifactWithProcTime(long rawSn, LocalDateTime reportTime, LocalDateTime procTime)
            throws Exception {
        Path deidFile = TestVideoFixtures.writeTinyMp4(tempDir.resolve("proc-" + rawSn + ".mp4"));
        Files.setLastModifiedTime(deidFile, FileTime.from(
                reportTime.minusMinutes(10).atZone(ZoneId.systemDefault()).toInstant()));
        LsDeidentProcLog procLog = LsDeidentProcLog.request(
                rawSn, "req-" + rawSn, "/orgnl/" + rawSn + ".mp4", "system");
        procLog.succeed(deidFile.toString());
        setField(procLog, "resDt", procTime);
        when(procLogRepository.findLatestSuccessByDataRawSn(rawSn)).thenReturn(Optional.of(procLog));
        return deidFile;
    }

    @Test
    @DisplayName("resolve_검증시_procLog_시각이_신고시각과_동일하면_거부된다")
    void resolveRejectedWhenProcLogTimeEqualsReportTime() throws Exception {
        // given — procLog 완료시각 == 신고시각(정확히 동일, 나노초까지). mtime 은 신고 이전으로 고정해
        //         procLog 분기만 단독 노출시킨다. 기대 동작은 mtime 경계값(105b)과 <대칭>인 거부다:
        //         동일 시각의 성공 이력은 신고 시점에 이미 존재하던 그 비식별본이며 '신고 이후 재비식별'
        //         증거가 아니다. 증거 없음은 fail-closed 로 거부에 수렴한다.
        //         ※ 신고시각을 초 정렬하지 않는다 — 이 분기는 파일시스템을 거치지 않는 순수 LocalDateTime
        //           비교라 나노초 정밀도의 동일성을 그대로 검증할 수 있다.
        LsDeidentReport rep = report(753L, 9753L, LsDeidentReport.REPORT_OPEN);
        LocalDateTime reportTime = LocalDateTime.now().minusMinutes(5);
        setField(rep, "reportDt", reportTime);
        when(reportRepository.findById(753L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9753L, LsDataRaw.PRVC_TYPE_PRVC);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9753L)).thenReturn(Optional.of(r));
        stubArtifactWithProcTime(9753L, reportTime, reportTime);

        // when / then — 409 거부, fail-closed (게이트 유지).
        assertThatThrownBy(() -> service.resolveManually(753L, "proc-9753.mp4", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_OPEN);
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
        verify(streamMetaCacheEvictor, never()).evictAfterCommit(anyLong());
    }

    @Test
    @DisplayName("resolve_검증시_procLog_시각이_신고시각_직후면_정상_통과한다")
    void resolveAcceptedWhenProcLogTimeIsJustAfterReportTime() throws Exception {
        // given — procLog 완료시각 = 신고시각 + 1초(경계 바로 바깥). 기존 통과 케이스
        //         (resolveWithPostReportProcLogSucceeds)는 완료시각이 신고보다 1시간 뒤라 "경계가 정확히
        //         동일 시각에 있다"를 증명하지 못한다. 거부(동일)/통과(+1초)를 붙여야 경계가 고정된다.
        LsDeidentReport rep = report(754L, 9754L, LsDeidentReport.REPORT_OPEN);
        LocalDateTime reportTime = LocalDateTime.now().minusMinutes(5);
        setField(rep, "reportDt", reportTime);
        when(reportRepository.findById(754L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9754L, LsDataRaw.PRVC_TYPE_PRVC);
        setField(r, "dataSttsCd", LsDataRaw.DATA_STTS_MARKING_READY);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9754L)).thenReturn(Optional.of(r));
        stubArtifactWithProcTime(9754L, reportTime, reportTime.plusSeconds(1));

        // when
        service.resolveManually(754L, "proc-9754.mp4", reviewerActor);

        // then — procLog 조건 단독으로 통과 → RESOLVED + 'Y' 복원 + 작업락 해제.
        //   V171 원자 클레임 — 전이 사실은 claimResolve 호출로 단정한다(엔티티는 OPEN 그대로).
        verify(reportRepository).claimResolve(eq(754L),
                eq(LsDeidentReport.REPORT_OPEN), eq(LsDeidentReport.REPORT_RESOLVED), any());
        assertThat(r.getDeIdntfYn()).isEqualTo("Y");
        verify(workLockService).releaseRaw(eq(9754L), anyString(), anyString());
    }

    // ------------------------------------------------------------
    // mtime 비교의 <파일시스템 정밀도> 경계 (B-ISSUE-42 보강)
    //   기존 mtime 케이스는 전부 초 정렬 신고시각 + 초 단위 오프셋(±30s/±1s)이라,
    //   FS 가 mtime 을 절삭할 때 서브초 경계가 어느 쪽으로 밀리는지 고정하지 못한다.
    //
    //   실측(이 저장소 개발 환경, java.io.tmpdir = APFS): setLastModifiedTime 왕복이
    //   나노초까지 완전 보존된다(+1ms→.001, +1ns→.000000001). 그러나 ext3/HFS+/일부
    //   overlayfs 는 초 단위로 절삭하므로 고정 기대값을 박으면 환경별로 플래키가 된다.
    //   따라서 <저장된 mtime 을 되읽어> 그 값 기준으로 기대를 분기하고, 보안상 중요한
    //   방향(절삭이 '신고 이전 파일'을 '이후'로 뒤집지 않음)은 정밀도와 무관하게 단정한다.
    // ------------------------------------------------------------

    @Test
    @DisplayName("resolve_검증시_mtime이_신고시각_직후_서브초면_저장된_mtime_기준으로_판정된다")
    void resolveJudgesSubSecondPostReportMtimeByStoredValue() throws Exception {
        // given — 의도한 mtime = 신고시각 + 1ms. 저장 결과는 파일시스템 정밀도에 좌우된다.
        LsDeidentReport rep = report(755L, 9755L, LsDeidentReport.REPORT_OPEN);
        LocalDateTime reportTime = secondAlignedReportTime();
        setField(rep, "reportDt", reportTime);
        when(reportRepository.findById(755L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9755L, LsDataRaw.PRVC_TYPE_PRVC);
        setField(r, "dataSttsCd", LsDataRaw.DATA_STTS_MARKING_READY);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9755L)).thenReturn(Optional.of(r));
        Path deidFile = stubArtifactWithMtime(9755L, reportTime, reportTime.plusNanos(1_000_000L));

        Instant reportInstant = reportTime.atZone(ZoneId.systemDefault()).toInstant();
        Instant storedMtime = Files.getLastModifiedTime(deidFile).toInstant();
        // 절삭은 내림이어야 한다 — 올림이면 신고 이전 파일이 '이후'로 승격되어 오탐 통과가 생긴다.
        assertThat(storedMtime).isBeforeOrEqualTo(reportInstant.plusMillis(1));

        // when / then — 프로덕션 판정(mtime.isAfter(reportTime))이 <저장된> mtime 과 일치해야 한다.
        if (storedMtime.isAfter(reportInstant)) {
            // 서브초 정밀도 보존 FS(APFS/ext4 등) — 신고 1ms 후 교체는 정상 재비식별이므로 통과.
            service.resolveManually(755L, "skew-9755.mp4", reviewerActor);
            // V171 원자 클레임 — 전이 사실은 claimResolve 호출로 단정한다(엔티티는 OPEN 그대로).
            verify(reportRepository).claimResolve(eq(755L),
                    eq(LsDeidentReport.REPORT_OPEN), eq(LsDeidentReport.REPORT_RESOLVED), any());
            assertThat(r.getDeIdntfYn()).isEqualTo("Y");
            verify(workLockService).releaseRaw(eq(9755L), anyString(), anyString());
        } else {
            // 초 단위 절삭 FS — 저장값이 신고시각과 같은 초로 내려앉아 '이후' 증거가 사라진다.
            // 이 경우의 정답은 fail-closed 거부다(경계 오판으로 게이트가 열리면 PII 재노출).
            assertThat(storedMtime).isEqualTo(reportInstant);
            assertThatThrownBy(() -> service.resolveManually(755L, "skew-9755.mp4", reviewerActor))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CONFLICT);
            assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_OPEN);
            assertThat(r.getDeIdntfYn()).isEqualTo("F");
        }
    }

    @Test
    @DisplayName("resolve_검증시_mtime이_신고시각_직전_서브초면_정밀도와_무관하게_거부된다")
    void resolveRejectsSubSecondPreReportMtimeRegardlessOfFsPrecision() throws Exception {
        // given — 의도한 mtime = 신고시각 - 1ms(신고 직전, 즉 신고를 유발한 그 산출물).
        //         이 방향은 정밀도와 무관하게 항상 거부여야 한다: 서브초 보존 FS 면 저장값이 그대로
        //         신고 이전이고, 초 단위 절삭 FS 면 한 초 더 과거로 내려앉아 역시 신고 이전이다.
        //         절삭이 <올림>이면 신고 이후로 뒤집혀 오탐 통과가 되므로 그 사실도 함께 단정한다.
        LsDeidentReport rep = report(756L, 9756L, LsDeidentReport.REPORT_OPEN);
        LocalDateTime reportTime = secondAlignedReportTime();
        setField(rep, "reportDt", reportTime);
        when(reportRepository.findById(756L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9756L, LsDataRaw.PRVC_TYPE_PRVC);
        r.markDeidentified("F");
        when(videoRepository.findByRawSnForUpdate(9756L)).thenReturn(Optional.of(r));
        Path deidFile = stubArtifactWithMtime(9756L, reportTime, reportTime.minusNanos(1_000_000L));

        Instant reportInstant = reportTime.atZone(ZoneId.systemDefault()).toInstant();
        assertThat(Files.getLastModifiedTime(deidFile).toInstant()).isBefore(reportInstant);

        // when / then — 409 거부, fail-closed.
        assertThatThrownBy(() -> service.resolveManually(756L, "skew-9756.mp4", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_OPEN);
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
        verify(streamMetaCacheEvictor, never()).evictAfterCommit(anyLong());
    }

    @Test
    @DisplayName("미인증_사용자_resolve_요청시_401")
    void resolveUnauthenticated() {
        assertThatThrownBy(() -> service.resolveManually(700L, "deid-9700.mp4", null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("존재하지_않는_신고_resolve_요청시_NOT_FOUND_404")
    void resolveUnknownReportNotFound() {
        when(reportRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveManually(9999L, "deid-9999.mp4", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ============================================================
    // 기존 유지 — resolveOpenReports (DeidentifyStep 자동 호출 경로)
    // ============================================================

    @Test
    @DisplayName("resolveOpenReports_REPORT_STTS_OPEN_신고_일괄_RESOLVED_전이_+_WorkLock_해제")
    void resolveOpenReportsTransitions() {
        LsDeidentReport r1 = LsDeidentReport.createReport(9100L, 100L, "사유1");
        LsDeidentReport r2 = LsDeidentReport.createReport(9100L, 101L, "사유2");
        when(reportRepository.findAllByDataRawSnAndReportSttsCd(9100L, LsDeidentReport.REPORT_OPEN))
                .thenReturn(List.of(r1, r2));

        int n = service.resolveOpenReports(9100L);

        assertThat(n).isEqualTo(2);
        assertThat(r1.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_RESOLVED);
        assertThat(r2.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_RESOLVED);
        assertThat(r1.getResolvedDt()).isNotNull();
        verify(workLockService).releaseRaw(9100L, "system", "DEIDENT_SUCCEEDED");
        // 배치 자동 재비식별 성공으로 비식별본이 교체되었으므로 커밋 후 스트림 메타 캐시 무효화 훅 호출.
        verify(streamMetaCacheEvictor).evictAfterCommit(9100L);
    }

    @Test
    @DisplayName("resolveOpenReports_rawSn_null_안전_종료_0")
    void resolveNullRawSnReturnsZero() {
        assertThat(service.resolveOpenReports(null)).isZero();
        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
    }

    // ============================================================
    // V171 — 신고 단계 구분 + 접수 가드 + 해소 후 재개 지점 분기
    // ============================================================

    /** 발행된 단계 재개 이벤트만 추린다(다른 이벤트와 섞이지 않게). */
    private List<DeidentStageResumeEvent> capturedStageResumeEvents() {
        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeast(0)).publishEvent(cap.capture());
        return cap.getAllValues().stream()
                .filter(DeidentStageResumeEvent.class::isInstance)
                .map(DeidentStageResumeEvent.class::cast)
                .toList();
    }

    @Test
    @DisplayName("마킹단계_신고는_MARKING_READY_에서만_접수된다")
    void markingStageReportRequiresMarkingReady() {
        // given — 마킹이 끝나 배치가 도는 영상(PROCESSING). 마킹 화면에서 도달할 상태가 아니다.
        LsDataRaw processing = raw(9801L, LsDataRaw.PRVC_TYPE_PRVC);
        setField(processing, "dataSttsCd", LsDataRaw.DATA_STTS_PROCESSING);
        when(videoRepository.findByRawSnForUpdate(9801L)).thenReturn(Optional.of(processing));

        // when / then — 412 (신고 게이트 계열 표준 코드). 부작용 0.
        assertThatThrownBy(() -> service.reportByVideo(9801L, "얼굴 미블러", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(processing.getDeIdntfYn()).isEqualTo("Y");
        verify(reportRepository, never()).save(any());
        verify(workLockService, never()).lockRawForRedeident(anyLong(), anyString());

        // and — MARKING_READY 면 정상 접수되고 DCLR_STP_CD='MARKING' 이 기록된다.
        LsDataRaw ready = raw(9802L, LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findByRawSnForUpdate(9802L)).thenReturn(Optional.of(ready));
        when(workLockService.isRawLocked(9802L)).thenReturn(false);
        stubReportSave();
        stubApproved(9802L, false);

        service.reportByVideo(9802L, "얼굴 미블러", reviewerActor);

        ArgumentCaptor<LsDeidentReport> saved = ArgumentCaptor.forClass(LsDeidentReport.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getDclrStpCd()).isEqualTo(LsDeidentReport.STAGE_MARKING);
    }

    @Test
    @DisplayName("마킹단계_신고_거부_문구는_영상의_배치단계를_알려주지_않는다")
    void markingStageRejectionIsNotAStateOracle() {
        // given — 서로 다른 비-MARKING_READY 상태 3종. 응답 코드·문구가 갈리면 상태 오라클이 된다(CWE-209).
        List<String> stages = List.of(LsDataRaw.DATA_STTS_PROCESSING,
                LsDataRaw.DATA_STTS_COMPLETED, LsDataRaw.DATA_STTS_FAILED);
        long rawSn = 9810L;
        String firstMessage = null;
        for (String stage : stages) {
            LsDataRaw r = raw(rawSn, LsDataRaw.PRVC_TYPE_PRVC);
            setField(r, "dataSttsCd", stage);
            when(videoRepository.findByRawSnForUpdate(rawSn)).thenReturn(Optional.of(r));

            CustomException ex = (CustomException) org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.reportByVideo(9810L, "사유", reviewerActor));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            // 상태 코드 원문이 사용자 문구에 새지 않는다.
            assertThat(ex.getMessage()).doesNotContain(stage);
            if (firstMessage == null) {
                firstMessage = ex.getMessage();
            } else {
                assertThat(ex.getMessage()).isEqualTo(firstMessage);
            }
        }
    }

    @Test
    @DisplayName("라벨링단계_신고는_기존대로_접수된다")
    void labelingStageReportUnaffectedByBatchStage() {
        // given — 검수 완료 영상(배치 단계 COMPLETED). 라벨링 화면 신고는 이 상태가 정상 동선이다.
        LsDataSrc s = src(1L, 9820L);
        LsDataRaw r = raw(9820L, LsDataRaw.PRVC_TYPE_PRVC);
        setField(r, "dataSttsCd", LsDataRaw.DATA_STTS_COMPLETED);
        when(accessGuard.verifyAndGet(eq(1L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9820L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9820L)).thenReturn(false);
        stubReportSave();
        stubApproved(9820L, false);

        // when — 배치 단계 제한을 라벨링에 걸면 이 정상 동선이 막힌다(회귀 방어).
        Long rprtSn = service.report(1L, "얼굴 미블러", workerActor);

        // then — 접수 + DCLR_STP_CD='LABELING'
        assertThat(rprtSn).isEqualTo(555L);
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
        ArgumentCaptor<LsDeidentReport> saved = ArgumentCaptor.forClass(LsDeidentReport.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getDclrStpCd()).isEqualTo(LsDeidentReport.STAGE_LABELING);
    }

    @Test
    @DisplayName("해소시_마킹단계는_MARKING_단계_재개_이벤트를_발행한다")
    void resolvePublishesMarkingStageResume() {
        LsDeidentReport rep = report(760L, 9830L, LsDeidentReport.REPORT_OPEN);
        setField(rep, "dclrStpCd", LsDeidentReport.STAGE_MARKING);
        when(reportRepository.findById(760L)).thenReturn(Optional.of(rep));
        stubDeidentArtifact(9830L);

        service.resolveManually(760L, "deid-9830.mp4", reviewerActor);

        assertThat(capturedStageResumeEvents())
                .containsExactly(new DeidentStageResumeEvent(9830L, LsDeidentReport.STAGE_MARKING));
    }

    @Test
    @DisplayName("해소시_라벨링단계는_LABELING_단계_재개_이벤트를_발행한다")
    void resolvePublishesLabelingStageResume() {
        LsDeidentReport rep = report(761L, 9831L, LsDeidentReport.REPORT_OPEN);
        setField(rep, "dclrStpCd", LsDeidentReport.STAGE_LABELING);
        when(reportRepository.findById(761L)).thenReturn(Optional.of(rep));
        stubDeidentArtifact(9831L);

        service.resolveManually(761L, "deid-9831.mp4", reviewerActor);

        assertThat(capturedStageResumeEvents())
                .containsExactly(new DeidentStageResumeEvent(9831L, LsDeidentReport.STAGE_LABELING));
    }

    @Test
    @DisplayName("단계가_NULL_인_레거시_신고는_재개_이벤트를_발행하지_않는다")
    void legacyNullStageReportPublishesNoResumeEvent() {
        // given — DCLR_STP_CD 신설 이전 신고(백필하지 않는다 = 단계 미상).
        LsDeidentReport rep = report(762L, 9832L, LsDeidentReport.REPORT_OPEN);
        assertThat(rep.getDclrStpCd()).isNull();
        when(reportRepository.findById(762L)).thenReturn(Optional.of(rep));
        stubDeidentArtifact(9832L);

        // when
        service.resolveManually(762L, "deid-9832.mp4", reviewerActor);

        // then — 단계 재개는 없고, 기존 2종 계약(게이트 재개방)은 그대로 유지된다.
        assertThat(capturedStageResumeEvents()).isEmpty();
        verify(eventPublisher).publishEvent(new DeidentGateReopenedEvent(9832L));
    }

    @Test
    @DisplayName("동시_resolve_는_한_번만_재개한다")
    void concurrentResolveResumesOnlyOnce() {
        // given — 두 노드가 동시에 같은 신고를 해소. 둘 다 findById 로 OPEN 을 관측한다(read-then-write).
        LsDeidentReport rep = report(763L, 9833L, LsDeidentReport.REPORT_OPEN);
        setField(rep, "dclrStpCd", LsDeidentReport.STAGE_LABELING);
        when(reportRepository.findById(763L)).thenReturn(Optional.of(rep));
        stubDeidentArtifact(9833L);
        // 조건부 UPDATE 는 DB 가 직렬화 — 첫 호출만 1행, 두 번째는 0행.
        when(reportRepository.claimResolve(eq(763L), anyString(), anyString(), any()))
                .thenReturn(1).thenReturn(0);

        // when — 1번째 성공
        service.resolveManually(763L, "deid-9833.mp4", reviewerActor);
        // and — 2번째는 클레임 패배 → 409
        assertThatThrownBy(() -> service.resolveManually(763L, "deid-9833.mp4", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // then — 재개 이벤트는 <b>정확히 1회</b>. 락 해제·'Y' 복원도 클레임 성공자만 수행한다.
        assertThat(capturedStageResumeEvents())
                .containsExactly(new DeidentStageResumeEvent(9833L, LsDeidentReport.STAGE_LABELING));
        verify(workLockService, org.mockito.Mockito.times(1))
                .releaseRaw(eq(9833L), anyString(), anyString());
    }

    @Test
    @DisplayName("자동_배치_해소도_단계별_재개를_트리거하고_단계미상은_제외한다")
    void resolveOpenReportsPublishesStageResume() {
        LsDeidentReport withStage = LsDeidentReport.createReport(9840L, 100L, "사유1",
                LsDeidentReport.STAGE_LABELING);
        LsDeidentReport legacy = LsDeidentReport.createReport(9840L, 101L, "사유2");
        when(reportRepository.findAllByDataRawSnAndReportSttsCd(9840L, LsDeidentReport.REPORT_OPEN))
                .thenReturn(List.of(withStage, legacy));

        service.resolveOpenReports(9840L);

        assertThat(capturedStageResumeEvents())
                .containsExactly(new DeidentStageResumeEvent(9840L, LsDeidentReport.STAGE_LABELING));
    }

    // ============================================================
    // R3 — 산출물 선택(후보 목록 대조) · 원장 재지정
    //   구 해소는 "원장에 기록된 경로 1개"의 mtime 만 봤다 = 외부 솔루션이 <같은 이름으로 제자리
    //   덮어쓰기> 하는 것을 전제. 실제(KPST)는 {원본stem}-mask{ext} 로 산출하므로 그런 신고는
    //   영원히 해소되지 않았다(작업락 + 'F' 고착). 이제 사람이 후보 목록에서 고른다.
    // ============================================================

    @Test
    @DisplayName("R3_산출물_선택이_없으면_400이다_서버가_기본값을_고르지_않는다")
    void resolveWithoutFileNameRejected() {
        LsDeidentReport rep = report(770L, 9770L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(770L)).thenReturn(Optional.of(rep));
        stubDeidentArtifact(9770L);

        for (String blank : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> service.resolveManually(770L, blank, reviewerActor))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
        // fail-closed — 전이·락 해제 어느 것도 일어나지 않는다.
        verify(reportRepository, never()).claimResolve(anyLong(), anyString(), anyString(), any());
        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("R3_후보_목록에_없는_파일명은_400이다")
    void resolveWithUnlistedFileNameRejected() {
        LsDeidentReport rep = report(771L, 9771L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(771L)).thenReturn(Optional.of(rep));
        stubDeidentArtifact(9771L); // 유일한 후보는 "deid-9771.mp4"

        assertThatThrownBy(() -> service.resolveManually(771L, "somebody-elses.mp4", reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(reportRepository, never()).claimResolve(anyLong(), anyString(), anyString(), any());
        verify(procLogRepository, never()).save(any(LsDeidentProcLog.class));
    }

    @Test
    @DisplayName("R3_경로_순회_시도는_예외가_아니라_400으로_수렴한다")
    void resolveWithPathTraversalAttemptRejected() {
        // 목록 키는 언제나 basename 이라 상위참조·절대경로·구분자가 섞인 입력은 어떤 항목과도
        // 같아질 수 없다 — 서버가 이 값으로 경로를 조립하지 않기 때문이다(CWE-22).
        LsDeidentReport rep = report(772L, 9772L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(772L)).thenReturn(Optional.of(rep));
        Path artifact = TestVideoFixtures.writeTinyMp4(tempDir.resolve("deid-9772.mp4"));
        stubDeidentArtifact(9772L, artifact);

        String[] attempts = {
                "../deid-9772.mp4",
                "../../etc/passwd",
                artifact.toString(),                 // 절대경로 전체
                "./deid-9772.mp4",
                "sub/deid-9772.mp4",
        };
        for (String attempt : attempts) {
            assertThatThrownBy(() -> service.resolveManually(772L, attempt, reviewerActor))
                    .as("traversal attempt=%s", attempt)
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
        verify(reportRepository, never()).claimResolve(anyLong(), anyString(), anyString(), any());
    }

    /**
     * ★ R3 핵심 회귀 가드 — <b>다른 이름</b>의 재비식별 산출물을 골라 해소하면, 이후 조회되는
     * 최신 성공 경로가 <b>그 파일</b>이 된다.
     *
     * <p>이 단언이 없으면 "목록에서 고르기"가 반쪽이 된다: 해소 이후의 프레임 재추출·영상 스트리밍은
     * 전부 {@code DE_IDNTF_FILE_PATH_NM} 을 읽으므로, 원장을 재지정하지 않으면 하류가 <b>옛 파일</b>을
     * 계속 쓴다. 새 행은 {@code REQ_DT DESC, PROC_LOG_SN DESC} 정렬에서 최신으로 잡힌다.
     */
    @Test
    @DisplayName("R3_다른_이름의_산출물을_고르면_원장의_최신_성공_경로가_그_파일로_바뀐다")
    void resolveWithDifferentlyNamedArtifactRepointsProcLog() throws Exception {
        // given — 신고 이후 외부 솔루션이 <다른 이름>({원본stem}-mask{ext})으로 산출물을 만들었다.
        //         원장은 여전히 신고를 유발한 옛 산출물(deid-9773.mp4)을 가리킨다.
        LsDeidentReport rep = report(773L, 9773L, LsDeidentReport.REPORT_OPEN);
        LocalDateTime reportTime = LocalDateTime.now().minusHours(1);
        setField(rep, "reportDt", reportTime);
        when(reportRepository.findById(773L)).thenReturn(Optional.of(rep));

        LsDataRaw r = raw(9773L, LsDataRaw.PRVC_TYPE_PRVC);
        r.markDeidentified("F");
        when(videoRepository.findById(9773L)).thenReturn(Optional.of(r));
        when(videoRepository.findByRawSnForUpdate(9773L)).thenReturn(Optional.of(r));

        Path old = TestVideoFixtures.writeTinyMp4(tempDir.resolve("deid-9773.mp4"));
        Files.setLastModifiedTime(old, FileTime.from(
                reportTime.minusMinutes(10).atZone(ZoneId.systemDefault()).toInstant()));
        LsDeidentProcLog oldLog = LsDeidentProcLog.request(9773L, "req", "/var/raw/clip.mp4", "system");
        oldLog.succeed(old.toString());
        // 옛 성공 이력이므로 완료시각도 신고 이전이어야 한다(그래야 '신고 이후 재비식별' 증거가 없다).
        setField(oldLog, "resDt", reportTime.minusMinutes(10));
        when(procLogRepository.findLatestSuccessByDataRawSn(9773L)).thenReturn(Optional.of(oldLog));

        // 새 산출물은 <비식별 영상 디렉터리>({base}/videos/{rawSn}/) 안에 있어야 열거된다.
        Path deidDir = Files.createDirectories(tempDir.resolve("videos").resolve("9773"));
        Path fresh = TestVideoFixtures.writeTinyMp4(deidDir.resolve("clip-mask.mp4"));
        Files.setLastModifiedTime(fresh, FileTime.from(
                reportTime.plusMinutes(5).atZone(ZoneId.systemDefault()).toInstant()));

        // and — 후보 목록에 둘 다 뜨고, 새 산출물만 자격을 갖췄다(옛것은 신고 이전이라 부적격).
        List<kr.co.cudo.authoring.label.dto.DeidentCandidateResponse> candidates =
                service.listDeidentCandidates(773L, reviewerActor);
        assertThat(candidates).extracting(
                        kr.co.cudo.authoring.label.dto.DeidentCandidateResponse::fileName)
                .containsExactlyInAnyOrder("clip-mask.mp4", "deid-9773.mp4");
        assertThat(candidates).filteredOn(c -> c.fileName().equals("clip-mask.mp4"))
                .allMatch(kr.co.cudo.authoring.label.dto.DeidentCandidateResponse::eligible);
        assertThat(candidates).filteredOn(c -> c.fileName().equals("deid-9773.mp4"))
                .allMatch(c -> !c.eligible() && c.current());

        // when — 사람이 새 산출물을 고른다.
        service.resolveManually(773L, "clip-mask.mp4", reviewerActor);

        // then — 원장에 <새 SUCCESS 행>이 선택 경로로 적재된다(UPDATE 아님 — 이력 보존).
        ArgumentCaptor<LsDeidentProcLog> saved = ArgumentCaptor.forClass(LsDeidentProcLog.class);
        verify(procLogRepository).save(saved.capture());
        assertThat(saved.getValue().getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        assertThat(saved.getValue().getDataRawSn()).isEqualTo(9773L);
        assertThat(java.nio.file.Paths.get(saved.getValue().getDeIdntfFilePathNm()).getFileName())
                .hasToString("clip-mask.mp4");
        // and — 기존 계약(전이·락 해제·'Y' 복원)은 그대로다.
        verify(reportRepository).claimResolve(eq(773L),
                eq(LsDeidentReport.REPORT_OPEN), eq(LsDeidentReport.REPORT_RESOLVED), any());
        verify(workLockService).releaseRaw(eq(9773L), anyString(), anyString());
        assertThat(r.getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("R3_후보_조회는_해소와_같은_인가축이고_타인_배정_WORKER는_403이다")
    void listCandidatesUsesSameAuthorizationAsResolve() {
        LsDeidentReport rep = report(774L, 9774L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(774L)).thenReturn(Optional.of(rep));
        doThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."))
                .when(accessGuard).verifyRawAccess(eq(9774L), eq(workerActor));

        assertThatThrownBy(() -> service.listDeidentCandidates(774L, workerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("R3_후보_조회는_미인증_401_없는_신고_404다")
    void listCandidatesAuthAndNotFound() {
        assertThatThrownBy(() -> service.listDeidentCandidates(775L, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        when(reportRepository.findById(9999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.listDeidentCandidates(9999L, reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("R3_산출물이_하나도_없으면_후보_조회는_빈_목록_이고_에러가_아니다")
    void listCandidatesReturnsEmptyListWhenNothingProduced() {
        // 아직 외부 비식별을 하지 않은 정상 상태다 — 화면이 그 사실을 안내한다(에러 아님).
        LsDeidentReport rep = report(776L, 9776L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(776L)).thenReturn(Optional.of(rep));
        when(procLogRepository.findLatestSuccessByDataRawSn(9776L)).thenReturn(Optional.empty());

        assertThat(service.listDeidentCandidates(776L, reviewerActor)).isEmpty();
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
