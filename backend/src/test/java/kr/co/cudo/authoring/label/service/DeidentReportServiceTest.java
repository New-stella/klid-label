package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
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
import kr.co.cudo.authoring.label.event.DeidentReportResolvedEvent;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
    private LsRawDataStatusRepository rawDataStatusRepository;
    private ApplicationEventPublisher eventPublisher;
    private StreamMetaCacheEvictor streamMetaCacheEvictor;
    private LsDeidentProcLogRepository procLogRepository;
    private LsDataLblHstryRepository lblHstryRepository;
    private DeidentReportService service;

    private TokenClaims workerActor;
    private TokenClaims reviewerActor;

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
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        streamMetaCacheEvictor = mock(StreamMetaCacheEvictor.class);
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        // D-25 (2026-07-27 정책 반전) — 신고는 라벨을 삭제하지 않으므로 라벨/스냅샷/이력 협력자
        //   (VersionService·LsDataLblRepository·ATTR_VAL·AI_INFO·LBL_HSTRY)가 의존성에서 제거됐다.
        // DEV_FIX-B(M5) — 개인정보 3필드 리셋의 행 단위 감사(LS_DATA_LBL_HSTRY) 협력자만 재도입.
        lblHstryRepository = mock(LsDataLblHstryRepository.class);
        // S7 — 신고 게이트 판정은 단일 원천(DeidentReportGate)에 위임한다. 파생 export 재트리거 시
        //   "다른 조상이 아직 신고 중인가" 판정에 쓰이며, videoRepository 스텁이 그대로 판정에 반영된다.
        service = new DeidentReportService(accessGuard, videoRepository, reportRepository,
                notificationService, workLockService, srcRepository,
                rawDataStatusRepository, eventPublisher,
                streamMetaCacheEvictor, procLogRepository, lblHstryRepository,
                new kr.co.cudo.authoring.video.service.DeidentReportGate(videoRepository));

        workerActor = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(60));
        reviewerActor = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
        when(accessGuard.parseUserNo("100")).thenReturn(100L);
        when(accessGuard.parseUserNo("1")).thenReturn(1L);
    }

    private LsDataSrc src(long srcSn, long rawSn) {
        LsDataSrc s = LsDataSrc.create(rawSn, 0, "/raw/0.jpg", LocalDateTime.now());
        setField(s, "srcSn", srcSn);
        return s;
    }

    private LsDataRaw raw(long rawSn, String prvc) {
        LsDataRaw r = LsDataRaw.createFromIngest(
                "C-" + rawSn, "CCTV", "EVT", "GOV",
                prvc, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
        setField(r, "rawSn", rawSn);
        return r;
    }

    private void stubReportSave() {
        when(reportRepository.save(any(LsDeidentReport.class))).thenAnswer(inv -> {
            LsDeidentReport arg = inv.getArgument(0);
            setField(arg, "deidentReportSn", 555L);
            return arg;
        });
    }

    private void stubApproved(long rawSn, boolean approved) {
        if (approved) {
            LsRawDataStatus st = LsRawDataStatus.initial(rawSn);
            setField(st, "dataSttsCd", LsRawDataStatus.STTS_APPROVED);
            when(rawDataStatusRepository.findByRawDataIdIn(List.of(rawSn))).thenReturn(List.of(st));
        } else {
            when(rawDataStatusRepository.findByRawDataIdIn(List.of(rawSn))).thenReturn(List.of());
        }
    }

    @Test
    @DisplayName("비식별_신고시_작업락과_DE_IDNTF_YN_F_전이와_개인정보_리셋은_유지된다")
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

        // then — 신고 저장 + 작업락 + 'F' 전이 + 개인정보 3필드 리셋 + 스트림 캐시 무효화 유지.
        assertThat(rprtSn).isEqualTo(555L);
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService).lockRawForRedeident(9001L, "100");
        verify(srcRepository).resetPrivacyMetaByRawSn(9001L);
        verify(streamMetaCacheEvictor).evictAfterCommit(9001L);
        // 라벨을 지우지 않으므로 라벨셋 버전 bump(낙관적 락)도 하지 않는다.
        verify(srcRepository, never()).bumpLabelVersionByRawSn(anyLong());
    }

    @Test
    @DisplayName("비식별누락신고_처리후_프레임_개인정보값_초기화")
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

        // then — #5: 해당 영상 전체 프레임의 개인정보 3필드를 NULL 로 리셋(재비식별 후 stale 오표기 방지).
        //         라벨 보존 정책(D-25)과 무관하게 프레임 존재 여부와 상관없이 항상 수행한다.
        verify(srcRepository).resetPrivacyMetaByRawSn(9101L);
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

        // when / then — 거부 시 개인정보 리셋·'F' 전이 모두 없음(fail-closed).
        assertThatThrownBy(() -> service.report(42L, "사유", workerActor))
                .isInstanceOf(CustomException.class);
        verify(srcRepository, never()).resetPrivacyMetaByRawSn(anyLong());
        assertThat(r.getDeIdntfYn()).isNotEqualTo("F");
    }

    @Test
    @DisplayName("APPROVED_영상_신고시_TASK_MODIFIED_통지_발행")
    void approvedVideoReportPublishesTaskModified() {
        LsDataSrc s = src(5L, 9005L);
        LsDataRaw r = raw(9005L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(5L), any())).thenReturn(s);
        when(videoRepository.findByRawSnForUpdate(9005L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9005L)).thenReturn(false);
        stubReportSave();
        stubApproved(9005L, true);

        service.report(5L, "사유", workerActor);

        ArgumentCaptor<TaskModifiedEvent> cap = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(cap.capture());
        TaskModifiedEvent evt = cap.getValue();
        assertThat(evt.rawSn()).isEqualTo(9005L);
        // D-25 — 라벨은 보존되므로 구 LABEL_DELETED 가 아니라 개인정보 메타 리셋(META_UPDATED)이 통지된다.
        assertThat(evt.changeType()).isEqualTo(ChangeType.META_UPDATED);
    }

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
     * 비식별 산출물 검증 게이트 통과용 픽스처 — 실존하는 임시 비식별 파일(>0바이트)을 만들고,
     * 해당 rawSn 의 최신 성공 procLog 가 그 경로를 가리키도록 스텁한다.
     */
    private void stubDeidentArtifact(long rawSn) {
        try {
            Path deidFile = tempDir.resolve("deid-" + rawSn + ".mp4");
            Files.write(deidFile, new byte[]{1, 2, 3});
            LsDeidentProcLog procLog = LsDeidentProcLog.request(
                    rawSn, "req-" + rawSn, "/orgnl/" + rawSn + ".mp4", "system");
            procLog.succeed(deidFile.toString());
            when(procLogRepository.findLatestSuccessByDataRawSn(rawSn)).thenReturn(Optional.of(procLog));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("수동_비식별화_완료시_신고가_RESOLVED로_전이되고_작업락_해제")
    void resolveManuallyTransitionsAndReleasesLock() {
        LsDeidentReport rep = report(700L, 9700L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(700L)).thenReturn(Optional.of(rep));
        stubDeidentArtifact(9700L);

        service.resolveManually(700L, reviewerActor);

        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_RESOLVED);
        assertThat(rep.getResolvedDt()).isNotNull();
        verify(workLockService).releaseRaw(eq(9700L), anyString(), anyString());
        // 수동 재비식별 완료로 비식별본이 교체될 수 있으므로 커밋 후 스트림 메타 캐시 무효화 훅 호출.
        verify(streamMetaCacheEvictor).evictAfterCommit(9700L);
    }

    // ---------- 신고 해소 → 파생영상(자손) export 재트리거 ----------

    /** 신고 게이트 체인 1노드 스텁 — {@code (DE_IDNTF_YN, ORGNL_RAW_SN)}. */
    private void stubChainNode(Long rawSn, String deIdntfYn, Long orgnlRawSn) {
        when(videoRepository.findDeidentChainNodeByRawSn(rawSn)).thenReturn(Optional.of(
                new kr.co.cudo.authoring.video.repository.DeidentChainProjection() {
                    @Override
                    public String getDeIdntfYn() {
                        return deIdntfYn;
                    }

                    @Override
                    public Long getOrgnlRawSn() {
                        return orgnlRawSn;
                    }
                }));
    }

    /** 검수 승인(APPROVED) 상태 행 — 자손 배치 조회({@code findByRawDataIdIn}) 스텁용. */
    private LsRawDataStatus approvedStatus(long rawSn) {
        LsRawDataStatus st = LsRawDataStatus.initial(rawSn);
        setField(st, "dataSttsCd", LsRawDataStatus.STTS_APPROVED);
        return st;
    }

    @Test
    @DisplayName("resolve시_승인된_파생영상_export도_함께_재트리거된다")
    void resolveRetriggersApprovedDescendantExport() {
        // given — 부모(9740) 신고 해소. 부모는 미승인, 파생(9741)은 검수 완료(APPROVED) 상태다.
        //         부모 신고 구간에는 파생 export 도 게이트에 막혀 skip 되므로 함께 복구돼야 한다.
        LsDeidentReport rep = report(740L, 9740L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(740L)).thenReturn(Optional.of(rep));
        stubDeidentArtifact(9740L);
        stubApproved(9740L, false);
        when(videoRepository.findRawSnsByOrgnlRawSnIn(List.of(9740L))).thenReturn(List.of(9741L));
        when(videoRepository.findRawSnsByOrgnlRawSnIn(List.of(9741L))).thenReturn(List.of());
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(9741L)))
                .thenReturn(List.of(approvedStatus(9741L)));
        stubChainNode(9741L, "Y", 9740L);
        stubChainNode(9740L, "Y", null);

        // when
        service.resolveManually(740L, reviewerActor);

        // then — 파생영상 rawSn 으로 재산출 이벤트가 발행된다(부모는 미승인이라 미발행).
        verify(eventPublisher).publishEvent(new DeidentReportResolvedEvent(9741L));
        verify(eventPublisher, never()).publishEvent(new DeidentReportResolvedEvent(9740L));
        // 파생 스트림 메타 캐시도 함께 무효화된다(캐시 경유 게이트 우회 차단).
        verify(streamMetaCacheEvictor).evictAfterCommit(9740L);
        verify(streamMetaCacheEvictor).evictAfterCommit(9741L);
    }

    @Test
    @DisplayName("다른_조상이_아직_신고중인_파생영상은_재트리거하지_않는다")
    void resolveSkipsDescendantStillUnderAnotherAncestorReport() {
        // given — 3단 체인(조부 9750 → 부모 9751 → 파생 9752). 부모 신고만 해소했고 조부는 아직 'F' 다.
        //         이때 파생 export 는 여전히 게이트에 막히므로 재트리거하면 무의미한 실패만 쌓인다.
        LsDeidentReport rep = report(741L, 9751L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(741L)).thenReturn(Optional.of(rep));
        stubDeidentArtifact(9751L);
        stubApproved(9751L, false);
        when(videoRepository.findRawSnsByOrgnlRawSnIn(List.of(9751L))).thenReturn(List.of(9752L));
        when(videoRepository.findRawSnsByOrgnlRawSnIn(List.of(9752L))).thenReturn(List.of());
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(9752L)))
                .thenReturn(List.of(approvedStatus(9752L)));
        stubChainNode(9752L, "Y", 9751L);
        stubChainNode(9751L, "Y", 9750L);
        stubChainNode(9750L, "F", null); // 조부는 아직 신고 구간

        // when
        service.resolveManually(741L, reviewerActor);

        // then — 재산출 이벤트가 발행되지 않는다(게이트에 어차피 막힐 재산출을 만들지 않는다).
        verify(eventPublisher, never()).publishEvent(any(DeidentReportResolvedEvent.class));
    }

    @Test
    @DisplayName("OPEN이_아닌_신고_resolve_요청시_409")
    void resolveNonOpenConflict() {
        LsDeidentReport rep = report(701L, 9701L, LsDeidentReport.REPORT_RESOLVED);
        when(reportRepository.findById(701L)).thenReturn(Optional.of(rep));

        assertThatThrownBy(() -> service.resolveManually(701L, reviewerActor))
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

        assertThatThrownBy(() -> service.resolveManually(702L, workerActor))
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

        service.resolveManually(703L, reviewerActor);

        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_RESOLVED);
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
        service.resolveManually(710L, reviewerActor);

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
        service.resolveManually(711L, reviewerActor);

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
        service.resolveManually(712L, reviewerActor);

        // then — 'Y' 복원은 하되 배치 단계는 COMPLETED 유지(MARKING_READY 역행 금지).
        assertThat(r.getDeIdntfYn()).isEqualTo("Y");
        assertThat(r.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_COMPLETED);
    }

    // ============================================================
    // 비식별 산출물 검증 게이트 (CWE-359, fail-closed)
    // ============================================================

    @Test
    @DisplayName("비식별파일_없이_resolve시_409_거부되고_deIdntfYn은_F유지_report는_OPEN유지")
    void resolveWithoutDeidentFileRejectedAndFailClosed() {
        // given — procLog 에 비식별 경로는 기록되어 있으나 실제 파일이 스토리지에 없음.
        LsDeidentReport rep = report(720L, 9720L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(720L)).thenReturn(Optional.of(rep));
        LsDataRaw r = raw(9720L, LsDataRaw.PRVC_TYPE_PRVC);
        r.markDeidentified("F");
        // 부재 파일 경로를 가리키는 procLog.
        LsDeidentProcLog procLog = LsDeidentProcLog.request(9720L, "req", "/orgnl/9720.mp4", "system");
        procLog.succeed(tempDir.resolve("does-not-exist.mp4").toString());
        when(procLogRepository.findLatestSuccessByDataRawSn(9720L)).thenReturn(Optional.of(procLog));

        // when / then — 409 거부 (비식별 산출물 미검증).
        assertThatThrownBy(() -> service.resolveManually(720L, reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // fail-closed — report OPEN 유지, deIdntfYn 'F' 유지, 작업락 미해제, 'Y' 미복원.
        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_OPEN);
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("procLog_기록없이_resolve시_거부된다")
    void resolveWithoutProcLogRejected() {
        // given — 해당 rawSn 의 성공 처리 이력(procLog)이 아예 없음.
        LsDeidentReport rep = report(721L, 9721L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(721L)).thenReturn(Optional.of(rep));
        when(procLogRepository.findLatestSuccessByDataRawSn(9721L)).thenReturn(Optional.empty());

        // when / then — 409 거부.
        assertThatThrownBy(() -> service.resolveManually(721L, reviewerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

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
        service.resolveManually(722L, reviewerActor);

        // then — 게이트 통과 → RESOLVED 전이 + 'Y' 복원 + 마킹 게이트 두 조건 충족.
        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_RESOLVED);
        assertThat(r.getDeIdntfYn()).isEqualTo("Y");
        assertThat(r.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        verify(workLockService).releaseRaw(eq(9722L), anyString(), anyString());
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

        Path deidFile = tempDir.resolve("pre-report-9730.mp4");
        Files.write(deidFile, new byte[]{1, 2, 3});
        // 파일 mtime 을 신고보다 10분 과거로 강제 (스큐 60초를 훨씬 넘는 과거).
        Files.setLastModifiedTime(deidFile, FileTime.from(
                reportTime.minusMinutes(10).atZone(ZoneId.systemDefault()).toInstant()));
        LsDeidentProcLog procLog = LsDeidentProcLog.request(9730L, "req", "/orgnl/9730.mp4", "system");
        procLog.succeed(deidFile.toString());
        // procLog 완료시각도 신고 이전으로 강제 (옛 성공 이력).
        setField(procLog, "resDt", reportTime.minusMinutes(10));
        when(procLogRepository.findLatestSuccessByDataRawSn(9730L)).thenReturn(Optional.of(procLog));

        // when / then — 신고 이후 재비식별 산출물 미확인 → 409 거부, fail-closed.
        assertThatThrownBy(() -> service.resolveManually(730L, reviewerActor))
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

        Path deidFile = tempDir.resolve("replaced-9731.mp4");
        Files.write(deidFile, new byte[]{1, 2, 3}); // mtime = now (신고보다 1시간 후)
        LsDeidentProcLog procLog = LsDeidentProcLog.request(9731L, "req", "/orgnl/9731.mp4", "system");
        procLog.succeed(deidFile.toString());
        // 파일 교체는 새 procLog 를 만들지 않음 — 완료시각은 신고 이전(옛 성공 이력).
        setField(procLog, "resDt", reportTime.minusMinutes(5));
        when(procLogRepository.findLatestSuccessByDataRawSn(9731L)).thenReturn(Optional.of(procLog));

        // when
        service.resolveManually(731L, reviewerActor);

        // then — mtime 조건으로 통과 → RESOLVED + 'Y' 복원.
        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_RESOLVED);
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

        Path deidFile = tempDir.resolve("auto-9732.mp4");
        Files.write(deidFile, new byte[]{1, 2, 3});
        // 파일 mtime 은 신고 이전으로 강제 (procLog 완료시각 단독으로 통과함을 격리 검증).
        Files.setLastModifiedTime(deidFile, FileTime.from(
                reportTime.minusMinutes(10).atZone(ZoneId.systemDefault()).toInstant()));
        LsDeidentProcLog procLog = LsDeidentProcLog.request(9732L, "req", "/orgnl/9732.mp4", "system");
        procLog.succeed(deidFile.toString());
        // 신고 이후 자동 재비식별 성공 → 완료시각 최신.
        setField(procLog, "resDt", LocalDateTime.now());
        when(procLogRepository.findLatestSuccessByDataRawSn(9732L)).thenReturn(Optional.of(procLog));

        // when
        service.resolveManually(732L, reviewerActor);

        // then — procLog 완료시각 조건으로 통과 → RESOLVED + 'Y' 복원.
        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_RESOLVED);
        assertThat(r.getDeIdntfYn()).isEqualTo("Y");
        verify(workLockService).releaseRaw(eq(9732L), anyString(), anyString());
    }

    @Test
    @DisplayName("미인증_사용자_resolve_요청시_401")
    void resolveUnauthenticated() {
        assertThatThrownBy(() -> service.resolveManually(700L, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("존재하지_않는_신고_resolve_요청시_NOT_FOUND_404")
    void resolveUnknownReportNotFound() {
        when(reportRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveManually(9999L, reviewerActor))
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
