package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
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
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.version.service.VersionService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2 (R1 v1.14) — DeidentReportService 단위 테스트 (Mockito 기반).
 *
 * <p>R1 v1.14 정합 변경:
 * <ul>
 *   <li>신고 시 영상 전체 라벨(자동+수동)을 복원 가능 스냅샷 기록 후 일괄 삭제.</li>
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
    private VersionService versionService;
    private LsDataLblRepository labelRepository;
    private LsDataLblAttrValRepository attrValRepository;
    private LsDataLblAiInfoRepository aiInfoRepository;
    private LsRawDataStatusRepository rawDataStatusRepository;
    private LsDataLblHstryRepository lblHstryRepository;
    private ApplicationEventPublisher eventPublisher;
    private StreamMetaCacheEvictor streamMetaCacheEvictor;
    private DeidentReportService service;

    private TokenClaims workerActor;
    private TokenClaims reviewerActor;

    @BeforeEach
    void setUp() {
        accessGuard = mock(LabelAccessGuard.class);
        videoRepository = mock(VideoRepository.class);
        reportRepository = mock(LsDeidentReportRepository.class);
        retryQueue = mock(BatchRetryQueue.class);
        notificationService = mock(NotificationService.class);
        workLockService = mock(WorkLockService.class);
        versionService = mock(VersionService.class);
        labelRepository = mock(LsDataLblRepository.class);
        attrValRepository = mock(LsDataLblAttrValRepository.class);
        aiInfoRepository = mock(LsDataLblAiInfoRepository.class);
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        lblHstryRepository = mock(LsDataLblHstryRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        streamMetaCacheEvictor = mock(StreamMetaCacheEvictor.class);
        service = new DeidentReportService(accessGuard, videoRepository, reportRepository,
                notificationService, workLockService, versionService,
                labelRepository, attrValRepository, aiInfoRepository,
                rawDataStatusRepository, lblHstryRepository, eventPublisher,
                streamMetaCacheEvictor);

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

    private LsDataLbl lbl(long lblSn, long srcSn) {
        LsDataLbl l = LsDataLbl.createManual(srcSn, "BBOX", null, "person", "[[0,0],[1,1]]", 100L);
        setField(l, "lblSn", lblSn);
        return l;
    }

    private void stubReportSave() {
        when(reportRepository.save(any(LsDeidentReport.class))).thenAnswer(inv -> {
            LsDeidentReport arg = inv.getArgument(0);
            setField(arg, "deidentReportSn", 555L);
            return arg;
        });
    }

    /** 라벨이 있는(스냅샷 발생) 케이스 — versionService 가 true 를 반환하도록 스텁. */
    private void stubSnapshotted(long rawSn) {
        when(versionService.snapshotDeidentReport(eq(rawSn), any())).thenReturn(true);
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
    @DisplayName("신고시_영상_전체_라벨이_스냅샷_기록_후_삭제됨")
    void reportSnapshotsAndDeletesAllVideoLabels() {
        LsDataSrc s = src(1L, 9001L);
        LsDataRaw r = raw(9001L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(1L), any())).thenReturn(s);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9001L)).thenReturn(false);
        stubReportSave();
        stubApproved(9001L, false);
        List<LsDataLbl> labels = List.of(lbl(10L, 1L), lbl(11L, 1L));
        when(labelRepository.findAllByRawSn(9001L)).thenReturn(labels);
        stubSnapshotted(9001L);

        Long rprtSn = service.report(1L, "얼굴 미블러", workerActor);

        assertThat(rprtSn).isEqualTo(555L);
        // 삭제 전 복원 가능 스냅샷 기록 (VersionService 위임).
        verify(versionService).snapshotDeidentReport(9001L, workerActor);
        // 라벨 본문 일괄 삭제.
        verify(labelRepository).deleteAllByRawSn(9001L);
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService).lockRawForRedeident(9001L, "100");
        // 신고('F') 후 스트림 메타 캐시 무효화 훅 호출(커밋 후 옛 노출본 서빙 차단).
        verify(streamMetaCacheEvictor).evictAfterCommit(9001L);
    }

    @Test
    @DisplayName("신고시_라벨_속성값과_AI정보_고아_잔존_없음")
    void reportDeletesAttrAndAiInfoBeforeLabels() {
        LsDataSrc s = src(2L, 9002L);
        LsDataRaw r = raw(9002L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(2L), any())).thenReturn(s);
        when(videoRepository.findById(9002L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9002L)).thenReturn(false);
        stubReportSave();
        stubApproved(9002L, false);
        List<LsDataLbl> labels = List.of(lbl(20L, 2L), lbl(21L, 2L));
        when(labelRepository.findAllByRawSn(9002L)).thenReturn(labels);
        stubSnapshotted(9002L);

        service.report(2L, "사유", workerActor);

        // 고아 방지 삭제 순서: ATTR_VAL → AI_INFO → LBL.
        var order = inOrder(attrValRepository, aiInfoRepository, labelRepository);
        order.verify(attrValRepository).deleteByLblSnIn(List.of(20L, 21L));
        order.verify(aiInfoRepository).deleteByDataLblSnIn(List.of(20L, 21L));
        order.verify(labelRepository).deleteAllByRawSn(9002L);
    }

    @Test
    @DisplayName("신고시_재비식별_큐에_적재되지_않음")
    void reportDoesNotEnqueueRetry() {
        LsDataSrc s = src(3L, 9003L);
        LsDataRaw r = raw(9003L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(3L), any())).thenReturn(s);
        when(videoRepository.findById(9003L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9003L)).thenReturn(false);
        stubReportSave();
        stubApproved(9003L, false);
        when(labelRepository.findAllByRawSn(9003L)).thenReturn(List.of(lbl(30L, 3L)));
        stubSnapshotted(9003L);

        service.report(3L, "사유", workerActor);

        verify(retryQueue, never()).enqueueIfRetryable(anyLong());
    }

    @Test
    @DisplayName("라벨_0건_영상_신고시_스냅샷_없이_정상_처리")
    void reportWithNoLabelsSkipsSnapshotAndDelete() {
        LsDataSrc s = src(4L, 9004L);
        LsDataRaw r = raw(9004L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(4L), any())).thenReturn(s);
        when(videoRepository.findById(9004L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9004L)).thenReturn(false);
        stubReportSave();
        stubApproved(9004L, false);
        when(labelRepository.findAllByRawSn(9004L)).thenReturn(List.of());
        // 라벨 0건 → VersionService 가 스냅샷 미생성(false) 반환 → 호출 측은 삭제 스킵.
        when(versionService.snapshotDeidentReport(eq(9004L), any())).thenReturn(false);

        Long rprtSn = service.report(4L, "사유", workerActor);

        assertThat(rprtSn).isEqualTo(555L);
        // 라벨 0건이면 삭제는 스킵 (스냅샷은 위임 호출되나 내부에서 미생성).
        verify(labelRepository, never()).deleteAllByRawSn(anyLong());
        verify(attrValRepository, never()).deleteByLblSnIn(any());
        // 신고 저장·잠금·DE_IDNTF_F 는 정상.
        verify(reportRepository).save(any(LsDeidentReport.class));
        verify(workLockService).lockRawForRedeident(9004L, "100");
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
    }

    @Test
    @DisplayName("비식별_신고_시_삭제되는_라벨_이력이_LBL_HSTRY에_기록된다")
    void reportRecordsDeletionHistoryForEachLabel() {
        // given — 라벨 2건 영상 신고
        LsDataSrc s = src(40L, 9040L);
        LsDataRaw r = raw(9040L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(40L), any())).thenReturn(s);
        when(videoRepository.findById(9040L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9040L)).thenReturn(false);
        stubReportSave();
        stubApproved(9040L, false);
        List<LsDataLbl> labels = List.of(lbl(401L, 40L), lbl(402L, 40L));
        when(labelRepository.findAllByRawSn(9040L)).thenReturn(labels);
        stubSnapshotted(9040L);

        // when
        service.report(40L, "사유", workerActor);

        // then — 삭제 라벨 1건당 이력 1건 saveAll (1건씩 save 금지)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLblHstry>> cap = ArgumentCaptor.forClass(List.class);
        verify(lblHstryRepository).saveAll(cap.capture());
        List<LsDataLblHstry> saved = cap.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(LsDataLblHstry::getLblSn).containsExactlyInAnyOrder(401L, 402L);
        assertThat(saved).allSatisfy(h -> {
            assertThat(h.getSrcSn()).isEqualTo(40L);
            assertThat(h.getRegisteredAt()).isNotNull();
        });

        // 이력 기록은 라벨 본문 삭제보다 먼저 (부분 실패 시 이력만 남는 정합성 깨짐 방지, 동일 트랜잭션).
        var order = inOrder(lblHstryRepository, labelRepository);
        order.verify(lblHstryRepository).saveAll(any());
        order.verify(labelRepository).deleteAllByRawSn(9040L);
    }

    @Test
    @DisplayName("라벨이_없는_영상_신고_시_이력이_기록되지_않는다")
    void reportWithNoLabelsDoesNotRecordHistory() {
        // given — 라벨 0건
        LsDataSrc s = src(41L, 9041L);
        LsDataRaw r = raw(9041L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(41L), any())).thenReturn(s);
        when(videoRepository.findById(9041L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9041L)).thenReturn(false);
        stubReportSave();
        stubApproved(9041L, false);
        when(labelRepository.findAllByRawSn(9041L)).thenReturn(List.of());
        when(versionService.snapshotDeidentReport(eq(9041L), any())).thenReturn(false);

        // when
        service.report(41L, "사유", workerActor);

        // then — 라벨 0건이면 이력 0건 (saveAll 미호출)
        verify(lblHstryRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("이미_잠금_영상_신고_거부_시_이력이_기록되지_않는다")
    void rejectedReportDoesNotRecordHistory() {
        // given — 이미 잠금 → CONFLICT 거부
        LsDataSrc s = src(42L, 9042L);
        LsDataRaw r = raw(9042L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(42L), any())).thenReturn(s);
        when(videoRepository.findById(9042L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9042L)).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> service.report(42L, "사유", workerActor))
                .isInstanceOf(CustomException.class);
        verify(lblHstryRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("APPROVED_영상_신고시_TASK_MODIFIED_통지_발행")
    void approvedVideoReportPublishesTaskModified() {
        LsDataSrc s = src(5L, 9005L);
        LsDataRaw r = raw(9005L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(5L), any())).thenReturn(s);
        when(videoRepository.findById(9005L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9005L)).thenReturn(false);
        stubReportSave();
        stubApproved(9005L, true);
        when(labelRepository.findAllByRawSn(9005L)).thenReturn(List.of(lbl(50L, 5L)));
        stubSnapshotted(9005L);

        service.report(5L, "사유", workerActor);

        ArgumentCaptor<TaskModifiedEvent> cap = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(cap.capture());
        TaskModifiedEvent evt = cap.getValue();
        assertThat(evt.rawSn()).isEqualTo(9005L);
        assertThat(evt.changeType()).isEqualTo(ChangeType.LABEL_DELETED);
    }

    @Test
    @DisplayName("미승인_영상_신고시_통지_미발행")
    void notApprovedVideoReportDoesNotPublish() {
        LsDataSrc s = src(6L, 9006L);
        LsDataRaw r = raw(9006L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(6L), any())).thenReturn(s);
        when(videoRepository.findById(9006L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9006L)).thenReturn(false);
        stubReportSave();
        stubApproved(9006L, false);
        when(labelRepository.findAllByRawSn(9006L)).thenReturn(List.of(lbl(60L, 6L)));
        stubSnapshotted(9006L);

        service.report(6L, "사유", workerActor);

        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("동시_신고_unique_위반시_409")
    void concurrentReportUniqueViolationConflict() {
        LsDataSrc s = src(7L, 9007L);
        LsDataRaw r = raw(9007L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(7L), any())).thenReturn(s);
        when(videoRepository.findById(9007L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9007L)).thenReturn(false);
        stubReportSave();
        stubApproved(9007L, false);
        when(labelRepository.findAllByRawSn(9007L)).thenReturn(List.of());
        // 잠금 INSERT 시 동시 신고로 unique 제약 위반.
        doThrow(new DataIntegrityViolationException("unique"))
                .when(workLockService).lockRawForRedeident(9007L, "100");

        assertThatThrownBy(() -> service.report(7L, "사유", workerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("이미_잠금_영상_신고시_CONFLICT_409_+_저장_없음_+_라벨_삭제_없음")
    void alreadyLockedConflict() {
        LsDataSrc s = src(8L, 9008L);
        LsDataRaw r = raw(9008L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(8L), any())).thenReturn(s);
        when(videoRepository.findById(9008L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9008L)).thenReturn(true);

        assertThatThrownBy(() -> service.report(8L, "재신고", workerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(reportRepository, never()).save(any());
        verify(labelRepository, never()).deleteAllByRawSn(anyLong());
        verify(workLockService, never()).lockRawForRedeident(anyLong(), anyString());
    }

    @Test
    @DisplayName("존재하지_않는_영상_신고시_NOT_FOUND_404")
    void unknownVideoNotFound() {
        LsDataSrc s = src(9L, 9999L);
        when(accessGuard.verifyAndGet(eq(9L), any())).thenReturn(s);
        when(videoRepository.findById(9999L)).thenReturn(Optional.empty());

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
        when(videoRepository.findById(9010L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9010L)).thenReturn(false);
        stubReportSave();
        stubApproved(9010L, false);
        when(labelRepository.findAllByRawSn(9010L)).thenReturn(List.of(lbl(100L, 10L)));
        stubSnapshotted(9010L);

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

    @Test
    @DisplayName("수동_비식별화_완료시_신고가_RESOLVED로_전이되고_작업락_해제")
    void resolveManuallyTransitionsAndReleasesLock() {
        LsDeidentReport rep = report(700L, 9700L, LsDeidentReport.REPORT_OPEN);
        when(reportRepository.findById(700L)).thenReturn(Optional.of(rep));

        service.resolveManually(700L, reviewerActor);

        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_RESOLVED);
        assertThat(rep.getResolvedDt()).isNotNull();
        verify(workLockService).releaseRaw(eq(9700L), anyString(), anyString());
        // 수동 재비식별 완료로 비식별본이 교체될 수 있으므로 커밋 후 스트림 메타 캐시 무효화 훅 호출.
        verify(streamMetaCacheEvictor).evictAfterCommit(9700L);
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

        service.resolveManually(703L, reviewerActor);

        assertThat(rep.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_RESOLVED);
        verify(workLockService).releaseRaw(eq(9703L), anyString(), anyString());
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
