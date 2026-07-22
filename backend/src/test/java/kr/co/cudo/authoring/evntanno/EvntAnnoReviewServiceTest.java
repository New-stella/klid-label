package kr.co.cudo.authoring.evntanno;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.event.DatasetReExportEvent;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.dataset.service.DatasetVideoMetaSnapshotService;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoReviewRepository;
import kr.co.cudo.authoring.evntanno.service.EvntAnnoReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link EvntAnnoReviewService} 단위 테스트(Mockito) — <b>event_annotation 지연 승인 재동결 트리거</b>
 * 결정 로직 검증(HIGH 결함 수정: export 영구 누락 방지).
 *
 * <ul>
 *   <li>영상이 이미 APPROVED + 활성 스냅샷 존재 → 재동결({@code materialize}) + export 재생성 이벤트 +
 *       TASK_MODIFIED(META_UPDATED) 트리거.</li>
 *   <li>영상 미승인 → 트리거하지 않음(역순 무회귀·중복 방지).</li>
 *   <li>APPROVED 이지만 활성 스냅샷 부재 → 트리거하지 않음(fail-safe skip).</li>
 *   <li>반려(reject)는 재동결/export/통지를 전혀 트리거하지 않음.</li>
 * </ul>
 */
class EvntAnnoReviewServiceTest {

    private static final long RAW_SN = 500L;
    private static final long EVNT_ANNO_SN = 900L;

    private LsEvntAnnoRepository annoRepository;
    private LsEvntAnnoReviewRepository reviewRepository;
    private LsRawDataStatusRepository rawDataStatusRepository;
    private LsDatasetVideoMetaRepository videoMetaRepository;
    private DatasetVideoMetaSnapshotService snapshotService;
    private ApplicationEventPublisher eventPublisher;

    private EvntAnnoReviewService service;

    @BeforeEach
    void setUp() {
        annoRepository = mock(LsEvntAnnoRepository.class);
        reviewRepository = mock(LsEvntAnnoReviewRepository.class);
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        videoMetaRepository = mock(LsDatasetVideoMetaRepository.class);
        snapshotService = mock(DatasetVideoMetaSnapshotService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new EvntAnnoReviewService(annoRepository, reviewRepository, rawDataStatusRepository,
                videoMetaRepository, snapshotService, eventPublisher);

        // resolveReview 공통 스텁 — anno + 검토 row 해석.
        LsEvntAnno anno = mock(LsEvntAnno.class);
        when(anno.getEvntAnnoSn()).thenReturn(EVNT_ANNO_SN);
        when(annoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.of(anno));
        LsEvntAnnoReview review = mock(LsEvntAnnoReview.class);
        when(review.getRvwSn()).thenReturn(1L);
        when(reviewRepository.findByEvntAnnoSn(EVNT_ANNO_SN)).thenReturn(List.of(review));
    }

    private TokenClaims reviewer() {
        return new TokenClaims("11", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private void stubVideoStatus(String status) {
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        when(stts.getDataSttsCd()).thenReturn(status);
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of(stts));
    }

    private void stubActiveSnapshotPresent(boolean present) {
        when(videoMetaRepository.findByRawSnAndActiveYn(RAW_SN, LsDatasetVideoMeta.ACTIVE_YES))
                .thenReturn(present ? List.of(mock(LsDatasetVideoMeta.class)) : List.of());
    }

    @Test
    @DisplayName("영상승인후_event_annotation_지연승인시_재동결_export재생성_TASK_MODIFIED_트리거")
    void lateApprove_whenVideoAlreadyApproved_triggersReFreezeExportAndNotify() {
        stubVideoStatus(LsRawDataStatus.STTS_APPROVED);
        stubActiveSnapshotPresent(true);

        service.approve(RAW_SN, reviewer());

        verify(snapshotService).materialize(eq(RAW_SN));
        verify(eventPublisher).publishEvent(any(DatasetReExportEvent.class));
        verify(eventPublisher).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("TASK_MODIFIED_통지는_META_UPDATED_변경종류로_발행된다")
    void lateApprove_notifyUsesMetaUpdatedChangeType() {
        stubVideoStatus(LsRawDataStatus.STTS_APPROVED);
        stubActiveSnapshotPresent(true);

        service.approve(RAW_SN, reviewer());

        verify(eventPublisher).publishEvent(new TaskModifiedEvent(
                RAW_SN, null, ChangeType.META_UPDATED, 11L));
    }

    @Test
    @DisplayName("영상_미승인이면_재동결_트리거안함 — 역순 무회귀·중복 방지")
    void lateApprove_whenVideoNotApproved_doesNotTrigger() {
        stubVideoStatus(LsRawDataStatus.STTS_IN_REVIEW);

        service.approve(RAW_SN, reviewer());

        verify(snapshotService, never()).materialize(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("영상_상태row_부재시_재동결_트리거안함")
    void lateApprove_whenNoStatusRow_doesNotTrigger() {
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of());

        service.approve(RAW_SN, reviewer());

        verify(snapshotService, never()).materialize(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("APPROVED지만_활성스냅샷_없으면_재동결_트리거안함 — fail-safe skip")
    void lateApprove_whenApprovedButNoActiveSnapshot_doesNotTrigger() {
        stubVideoStatus(LsRawDataStatus.STTS_APPROVED);
        stubActiveSnapshotPresent(false);

        service.approve(RAW_SN, reviewer());

        verify(snapshotService, never()).materialize(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("반려는_재동결_export_통지를_전혀_트리거하지_않는다")
    void reject_doesNotTriggerReFreeze() {
        service.reject(RAW_SN, "사유", reviewer());

        verify(snapshotService, never()).materialize(any());
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(rawDataStatusRepository);
        verifyNoInteractions(videoMetaRepository);
    }
}
