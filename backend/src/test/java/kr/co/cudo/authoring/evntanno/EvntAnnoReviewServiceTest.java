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
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDateTime;
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
    private LsEvntAnno anno;
    private LsEvntAnnoReview review;

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
        anno = mock(LsEvntAnno.class);
        when(anno.getEvntAnnoSn()).thenReturn(EVNT_ANNO_SN);
        when(annoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.of(anno));
        review = mock(LsEvntAnnoReview.class);
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

    /**
     * 최초 검수 완료 시각 — 재동결이 이 값을 승계해야 한다(지연 승인 시각인 now() 로 덮으면 A 결함 회귀).
     * {@code EnvironmentMetaServiceTest.ORIGINAL_APPROVED_AT} 와 동일한 검증 패턴.
     */
    private static final LocalDateTime ORIGINAL_APPROVED_AT = LocalDateTime.of(2026, 3, 1, 10, 0);

    private void stubActiveSnapshotPresent(boolean present) {
        if (present) {
            // 활성 스냅샷의 RVW_CMPL_DT 를 고정값으로 스텁 → 재동결이 이 값을 승계하는지 구체 검증 가능.
            LsDatasetVideoMeta snapshot = mock(LsDatasetVideoMeta.class);
            when(snapshot.getRvwCmplDt()).thenReturn(ORIGINAL_APPROVED_AT);
            when(videoMetaRepository.findByRawSnAndActiveYn(RAW_SN, LsDatasetVideoMeta.ACTIVE_YES))
                    .thenReturn(List.of(snapshot));
        } else {
            when(videoMetaRepository.findByRawSnAndActiveYn(RAW_SN, LsDatasetVideoMeta.ACTIVE_YES))
                    .thenReturn(List.of());
        }
    }

    @Test
    @DisplayName("영상승인후_event_annotation_지연승인시_재동결_export재생성_TASK_MODIFIED_트리거")
    void lateApprove_whenVideoAlreadyApproved_triggersReFreezeExportAndNotify() {
        stubVideoStatus(LsRawDataStatus.STTS_APPROVED);
        stubActiveSnapshotPresent(true);

        service.approve(RAW_SN, reviewer());

        // 재동결은 지연 승인 시각(now())이 아니라 기존 활성 스냅샷의 승인 시각을 그대로 승계해야 한다(A 결함 방어).
        verify(snapshotService).materialize(eq(RAW_SN), eq(ORIGINAL_APPROVED_AT));
        verify(snapshotService, never()).materialize(any());   // 1-arg(now()) 경로는 절대 호출 금지
        // MED-F(Phase 5C) — 재산출 트리거는 TaskModifiedEvent(regen=true) 한 축뿐. DatasetReExportEvent 병행
        //   발행(이중 export → 유령 버전 폴더)은 제거됐다.
        verify(eventPublisher, never()).publishEvent(any(DatasetReExportEvent.class));
        verify(eventPublisher).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("TASK_MODIFIED_통지는_META_UPDATED_변경종류로_발행된다")
    void lateApprove_notifyUsesMetaUpdatedChangeType() {
        stubVideoStatus(LsRawDataStatus.STTS_APPROVED);
        stubActiveSnapshotPresent(true);

        service.approve(RAW_SN, reviewer());

        // exportRegenerated=true — 이 경로만 DatasetReExportEvent 로 export 폴더를 재생성하므로
        // 통지가 전 프레임을 changed_items 에 실어야 한다(A-2).
        verify(eventPublisher).publishEvent(new TaskModifiedEvent(
                RAW_SN, null, ChangeType.META_UPDATED, 11L, true));
    }

    @Test
    @DisplayName("지연승인_통지는_export_재생성_동반_표식을_싣는다")
    void lateApprove_notifyCarriesExportRegeneratedFlag() {
        stubVideoStatus(LsRawDataStatus.STTS_APPROVED);
        stubActiveSnapshotPresent(true);

        service.approve(RAW_SN, reviewer());

        // 재생성 표식은 발행처 클래스명이 아니라 이벤트가 실어 나른다 — 통지 조립부가 이 값으로만
        // changed_items 범위를 정한다. MED-F(Phase 5C): 이 한 축(TaskModifiedEvent regen=true)이 export
        // 전량 재생성 → 통지를 직렬화하므로 DatasetReExportEvent 는 더 이상 발행하지 않는다.
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(e -> {
            assertThat(e).isInstanceOf(TaskModifiedEvent.class);
            assertThat(((TaskModifiedEvent) e).exportRegenerated()).isTrue();
        });
        assertThat(captor.getAllValues()).noneSatisfy(e ->
                assertThat(e).isInstanceOf(DatasetReExportEvent.class));
    }

    @Test
    @DisplayName("영상_미승인이면_재동결_트리거안함 — 역순 무회귀·중복 방지")
    void lateApprove_whenVideoNotApproved_doesNotTrigger() {
        stubVideoStatus(LsRawDataStatus.STTS_IN_REVIEW);

        service.approve(RAW_SN, reviewer());

        verify(snapshotService, never()).materialize(any());
        verify(snapshotService, never()).materialize(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("영상_상태row_부재시_재동결_트리거안함")
    void lateApprove_whenNoStatusRow_doesNotTrigger() {
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of());

        service.approve(RAW_SN, reviewer());

        verify(snapshotService, never()).materialize(any());
        verify(snapshotService, never()).materialize(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("APPROVED지만_활성스냅샷_없으면_재동결_트리거안함 — fail-safe skip")
    void lateApprove_whenApprovedButNoActiveSnapshot_doesNotTrigger() {
        stubVideoStatus(LsRawDataStatus.STTS_APPROVED);
        stubActiveSnapshotPresent(false);

        service.approve(RAW_SN, reviewer());

        verify(snapshotService, never()).materialize(any());
        verify(snapshotService, never()).materialize(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("반려는_재동결_export_통지를_전혀_트리거하지_않는다")
    void reject_doesNotTriggerReFreeze() {
        service.reject(RAW_SN, "사유", reviewer());

        verify(snapshotService, never()).materialize(any());
        verify(snapshotService, never()).materialize(any(), any());
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(rawDataStatusRepository);
        verifyNoInteractions(videoMetaRepository);
    }

    // ---- 지연 동결 치유(healLateFrozenEventAnnotation) 경로 — 재동결 승인시각 승계 검증 ----

    @Test
    @DisplayName("지연동결_치유시_재동결은_기존_승인시각을_승계한다 — now로_덮지_않음")
    void heal_reFreeze_inheritsOriginalApprovedAt() {
        // given — event_annotation 은 이미 APPROVED 이고, 최초 승인 시각을 가진 활성 스냅샷이 존재
        when(review.getRvwSttsCd()).thenReturn(LsEvntAnnoReview.STTS_APPROVED);
        stubActiveSnapshotPresent(true);

        // when
        boolean healed = service.healLateFrozenEventAnnotation(RAW_SN);

        // then — 재동결 수행 + 지연 치유 시각(now())이 아닌 기존 활성 스냅샷 승인 시각을 그대로 승계
        assertThat(healed).isTrue();
        verify(snapshotService).materialize(eq(RAW_SN), eq(ORIGINAL_APPROVED_AT));
        verify(snapshotService, never()).materialize(any());   // 1-arg(now()) 경로는 절대 호출 금지
    }

    // ---- F: 영상 검수 승인 시 event_annotation 자동 확정(autoApproveOnVideoApproval) ----

    @Test
    @DisplayName("영상승인시_AUTO_GENERATED_event_annotation이_APPROVED로_자동전이되고_flush된다")
    void autoApprove_whenAutoGenerated_transitionsToApprovedAndFlushes() {
        when(review.getRvwSttsCd()).thenReturn(LsEvntAnnoReview.STTS_AUTO_GENERATED);

        service.autoApproveOnVideoApproval(RAW_SN, reviewer());

        verify(review).approve("11");
        verify(reviewRepository).flush();
        // 자동 승인은 역순 지연승인용 재동결/이벤트를 트리거하지 않는다(같은 tx의 후속 materialize와 중복 방지).
        verify(snapshotService, never()).materialize(any());
        verify(snapshotService, never()).materialize(any(), any());
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(rawDataStatusRepository);
        verifyNoInteractions(videoMetaRepository);
    }

    @Test
    @DisplayName("영상승인시_PENDING_event_annotation도_APPROVED로_자동전이된다")
    void autoApprove_whenPending_transitionsToApproved() {
        when(review.getRvwSttsCd()).thenReturn(LsEvntAnnoReview.STTS_PENDING);

        service.autoApproveOnVideoApproval(RAW_SN, reviewer());

        verify(review).approve("11");
        verify(reviewRepository).flush();
    }

    @Test
    @DisplayName("영상승인시_REJECTED_event_annotation은_자동승인_제외되어_전이되지않는다")
    void autoApprove_whenRejected_isSkipped() {
        when(review.getRvwSttsCd()).thenReturn(LsEvntAnnoReview.STTS_REJECTED);

        service.autoApproveOnVideoApproval(RAW_SN, reviewer());

        verify(review, never()).approve(any());
        verify(reviewRepository, never()).flush();
    }

    @Test
    @DisplayName("영상승인시_이미_APPROVED면_멱등_재전이없이_통과한다")
    void autoApprove_whenAlreadyApproved_isIdempotentNoOp() {
        when(review.getRvwSttsCd()).thenReturn(LsEvntAnnoReview.STTS_APPROVED);

        service.autoApproveOnVideoApproval(RAW_SN, reviewer());

        verify(review, never()).approve(any());
        verify(reviewRepository, never()).flush();
    }

    @Test
    @DisplayName("event_annotation_없는_영상_승인은_정상_no_op")
    void autoApprove_whenNoEventAnnotation_isNoOp() {
        when(annoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.empty());

        service.autoApproveOnVideoApproval(RAW_SN, reviewer());

        verify(review, never()).approve(any());
        verify(reviewRepository, never()).flush();
    }

    @Test
    @DisplayName("event_annotation은_있으나_검토row_없으면_정상_no_op")
    void autoApprove_whenNoReviewRow_isNoOp() {
        when(reviewRepository.findByEvntAnnoSn(EVNT_ANNO_SN)).thenReturn(List.of());

        service.autoApproveOnVideoApproval(RAW_SN, reviewer());

        verify(review, never()).approve(any());
        verify(reviewRepository, never()).flush();
    }
}
