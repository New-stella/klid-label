package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.dataset.dto.EnvironmentMetaResponse;
import kr.co.cudo.authoring.dataset.dto.EnvironmentMetaUpdateRequest;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.event.DatasetReExportEvent;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2 — 촬영환경(날씨·시간대·계절) 메타 서비스 단위 테스트.
 *
 * <p>검증 축: 파생 프리필(수동값 미저장) · 수동값 우선(저장 후) · 화이트리스트 검증(400) ·
 * IDOR(403) · PUT 전체교체 계약 · APPROVED 이후 수정 시 TASK_MODIFIED 발행 ·
 * dirty checking(전체 save 금지).
 */
class EnvironmentMetaServiceTest {

    private static final Long RAW_SN = 77L;

    private VideoRepository videoRepository;
    private LabelAccessGuard accessGuard;
    private ReviewApprovalGate approvalGate;
    private DatasetVideoMetaSnapshotService snapshotService;
    private LsDatasetVideoMetaRepository videoMetaRepository;
    private ApplicationEventPublisher eventPublisher;
    private EnvironmentMetaService service;

    private final TokenClaims worker =
            new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        accessGuard = mock(LabelAccessGuard.class);
        approvalGate = mock(ReviewApprovalGate.class);
        snapshotService = mock(DatasetVideoMetaSnapshotService.class);
        videoMetaRepository = mock(LsDatasetVideoMetaRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new EnvironmentMetaService(videoRepository, accessGuard, approvalGate,
                snapshotService, videoMetaRepository, eventPublisher);
        when(accessGuard.parseUserNo("100")).thenReturn(100L);
        when(approvalGate.isApproved(any())).thenReturn(false);
        when(videoMetaRepository.findByRawSnAndActiveYn(any(), any())).thenReturn(List.of());
    }

    /** 검수 완료(APPROVED) 상태로 세팅. */
    private void approved() {
        when(approvalGate.isApproved(RAW_SN)).thenReturn(true);
    }

    /** 최초 검수 완료 시각 — 재동결이 이 값을 보존해야 한다(now() 로 덮으면 안 됨). */
    private static final LocalDateTime ORIGINAL_APPROVED_AT = LocalDateTime.of(2026, 3, 1, 10, 0);

    /** 활성 동결 스냅샷 1건 존재로 세팅(승인 시각 RVW_CMPL_DT 포함). */
    private void hasActiveSnapshot() {
        LsDatasetVideoMeta snapshot = mock(LsDatasetVideoMeta.class);
        when(snapshot.getRvwCmplDt()).thenReturn(ORIGINAL_APPROVED_AT);
        when(videoMetaRepository.findByRawSnAndActiveYn(RAW_SN, LsDatasetVideoMeta.ACTIVE_YES))
                .thenReturn(List.of(snapshot));
    }

    /** 촬영일시만 가진 영상(촬영환경 수동값 미입력). */
    private LsDataRaw raw(LocalDateTime shtDt) {
        LsDataRaw raw = LsDataRaw.createFromIngest("CLIP-ENV", "CCTV-1", "EVT01", "LG01",
                LsDataRaw.PRVC_TYPE_PRVC, "/nas/raw/env.mp4", shtDt, 30);
        ReflectionTestUtils.setField(raw, "rawSn", RAW_SN);
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
        return raw;
    }

    @Test
    @DisplayName("저장값_없으면_shtDt_파생값_프리필_반환")
    void 저장값_없으면_shtDt_파생값_프리필_반환() {
        // given — 2026-01-15 22:00 (야간·겨울), 수동값 미입력
        raw(LocalDateTime.of(2026, 1, 15, 22, 0));

        // when
        EnvironmentMetaResponse res = service.get(RAW_SN, worker);

        // then — 파생값이 프리필되고 출처는 DERIVED
        assertThat(res.timeOfDay()).isEqualTo("NGT");
        assertThat(res.season()).isEqualTo("WINTER");
        assertThat(res.timeOfDaySource()).isEqualTo(EnvironmentMetaResponse.SOURCE_DERIVED);
        assertThat(res.seasonSource()).isEqualTo(EnvironmentMetaResponse.SOURCE_DERIVED);
    }

    @Test
    @DisplayName("weather는_파생원_없어_null_프리필")
    void weather는_파생원_없어_null_프리필() {
        // given
        raw(LocalDateTime.of(2026, 7, 1, 9, 0));

        // when
        EnvironmentMetaResponse res = service.get(RAW_SN, worker);

        // then — 날씨는 자동 출처가 없어 미입력(null), 출처도 null
        assertThat(res.weather()).isNull();
        assertThat(res.weatherSource()).isNull();
    }

    @Test
    @DisplayName("shtDt가_null이면_시간대_계절도_null")
    void shtDt가_null이면_시간대_계절도_null() {
        // given — 촬영일시 미상
        raw(null);

        // when
        EnvironmentMetaResponse res = service.get(RAW_SN, worker);

        // then — 파생 불가
        assertThat(res.timeOfDay()).isNull();
        assertThat(res.season()).isNull();
        assertThat(res.timeOfDaySource()).isNull();
        assertThat(res.seasonSource()).isNull();
    }

    @Test
    @DisplayName("수동저장후_재조회시_저장값_유지")
    void 수동저장후_재조회시_저장값_유지() {
        // given — 파생상 야간·겨울인 영상
        LsDataRaw raw = raw(LocalDateTime.of(2026, 1, 15, 22, 0));

        // when — 수동으로 주간·여름·비 저장 후 재조회
        service.update(RAW_SN, new EnvironmentMetaUpdateRequest("비", "DAY", "SUMMER"), worker);
        EnvironmentMetaResponse res = service.get(RAW_SN, worker);

        // then — 파생값이 아니라 수동 저장값이 유지되고 출처는 MANUAL
        assertThat(raw.getWthrNm()).isEqualTo("비");
        assertThat(res.weather()).isEqualTo("비");
        assertThat(res.timeOfDay()).isEqualTo("DAY");
        assertThat(res.season()).isEqualTo("SUMMER");
        assertThat(res.weatherSource()).isEqualTo(EnvironmentMetaResponse.SOURCE_MANUAL);
        assertThat(res.timeOfDaySource()).isEqualTo(EnvironmentMetaResponse.SOURCE_MANUAL);
        assertThat(res.seasonSource()).isEqualTo(EnvironmentMetaResponse.SOURCE_MANUAL);
    }

    @Test
    @DisplayName("허용값_외_문자열_PUT시_400")
    void 허용값_외_문자열_PUT시_400() {
        // given
        raw(LocalDateTime.of(2026, 7, 1, 9, 0));

        // when / then — 자유 텍스트(XSS 벡터 포함)·허용값 외 코드는 모두 거부
        assertThatThrownBy(() -> service.update(RAW_SN,
                new EnvironmentMetaUpdateRequest("<script>alert(1)</script>", "DAY", "SUMMER"), worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> service.update(RAW_SN,
                new EnvironmentMetaUpdateRequest("맑음", "AFTERNOON", "SUMMER"), worker))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> service.update(RAW_SN,
                new EnvironmentMetaUpdateRequest("맑음", "DAY", "MONSOON"), worker))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("길이초과_문자열_PUT시_400")
    void 길이초과_문자열_PUT시_400() {
        // given — DTO @Size 를 우회한 서비스 직접 호출(방어 이중화)
        raw(LocalDateTime.of(2026, 7, 1, 9, 0));
        String tooLong = "맑".repeat(50);

        // when / then
        assertThatThrownBy(() -> service.update(RAW_SN,
                new EnvironmentMetaUpdateRequest(tooLong, "DAY", "SUMMER"), worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("본인배정_아닌_WORKER_PUT시_403")
    void 본인배정_아닌_WORKER_PUT시_403() {
        // given — 접근 가드가 IDOR 차단
        doThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."))
                .when(accessGuard).verifyRawAccess(anyLong(), any());

        // when / then
        assertThatThrownBy(() -> service.update(RAW_SN,
                new EnvironmentMetaUpdateRequest("맑음", "DAY", "SUMMER"), worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(videoRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("PUT_전체교체_계약_일부필드만_보내면_나머지_초기화")
    void PUT_전체교체_계약_일부필드만_보내면_나머지_초기화() {
        // given — 3필드 모두 수동 저장된 영상
        LsDataRaw raw = raw(LocalDateTime.of(2026, 1, 15, 22, 0));
        service.update(RAW_SN, new EnvironmentMetaUpdateRequest("비", "DAY", "SUMMER"), worker);

        // when — 날씨만 담아 재전송(전체 교체 계약)
        EnvironmentMetaResponse res =
                service.update(RAW_SN, new EnvironmentMetaUpdateRequest("눈", null, null), worker);

        // then — 미전송 필드는 초기화(null)되고 조회는 파생값으로 폴백
        assertThat(raw.getWthrNm()).isEqualTo("눈");
        assertThat(raw.getDayNgtCd()).isNull();
        assertThat(raw.getSesnCd()).isNull();
        assertThat(res.timeOfDay()).isEqualTo("NGT");   // 파생 폴백
        assertThat(res.timeOfDaySource()).isEqualTo(EnvironmentMetaResponse.SOURCE_DERIVED);
    }

    @Test
    @DisplayName("APPROVED_영상_촬영환경_수정시_TASK_MODIFIED_발행")
    void APPROVED_영상_촬영환경_수정시_TASK_MODIFIED_발행() {
        // given — 검수 완료(APPROVED) 영상
        raw(LocalDateTime.of(2026, 1, 15, 22, 0));
        approved();

        // when
        service.update(RAW_SN, new EnvironmentMetaUpdateRequest("맑음", "DAY", "SUMMER"), worker);

        // then — META_UPDATED 통지 발행(영상 단위라 srcSn 은 null)
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(TaskModifiedEvent.class);
        TaskModifiedEvent event = (TaskModifiedEvent) captor.getValue();
        assertThat(event.rawSn()).isEqualTo(RAW_SN);
        assertThat(event.srcSn()).isNull();
        assertThat(event.changeType()).isEqualTo(ChangeType.META_UPDATED);
        assertThat(event.modifierNo()).isEqualTo(100L);
        // C-1b(Phase 5C, 구 A-2 정책 반전) — 촬영환경 수정도 export 폴더를 새 버전으로 전량 재생성한다.
        // exportRegenerated=true 로 디바운스 flush 가 export 를 먼저 마친 뒤 전 프레임 통지를 내보내,
        // 관제가 픽업한 산출물의 JSON video 블록이 새 촬영환경으로 동기화된다.
        assertThat(event.exportRegenerated()).isTrue();
        // Phase 7a-1 — 사람이 콘텐츠를 고치는 경로라 재검토 표시 축도 true 로 실린다.
        assertThat(event.needsRecheck()).isTrue();
    }

    @Test
    @DisplayName("미검수_영상_촬영환경_수정시_TASK_MODIFIED_미발행")
    void 미검수_영상_촬영환경_수정시_TASK_MODIFIED_미발행() {
        // given — 상태행 없음(미검수)
        raw(LocalDateTime.of(2026, 1, 15, 22, 0));

        // when
        service.update(RAW_SN, new EnvironmentMetaUpdateRequest("맑음", "DAY", "SUMMER"), worker);

        // then
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("APPROVED_영상_촬영환경_수정시_수동값이_반영된_뒤_재동결되고_TASK_MODIFIED가_발행된다")
    void APPROVED_영상_촬영환경_수정시_재동결되고_통지발행() {
        // given — 이미 검수 승인되어 활성 동결 스냅샷(승인 시점 날씨 null)이 존재하는 영상
        LsDataRaw raw = raw(LocalDateTime.of(2026, 1, 15, 22, 0));
        approved();
        hasActiveSnapshot();

        // when — 승인 후 날씨를 '눈'으로 정정
        service.update(RAW_SN, new EnvironmentMetaUpdateRequest("눈", "NGT", "WINTER"), worker);

        // then — ①수동값이 엔티티에 반영되고 ②flush→advisory락→재동결(materialize) 순서로 스냅샷이 갱신되며
        //        ③완료 작업 수정 통지(TASK_MODIFIED)가 발행된다.
        assertThat(raw.getWthrNm()).isEqualTo("눈");
        InOrder ordered = inOrder(videoRepository, videoMetaRepository, snapshotService, eventPublisher);
        ordered.verify(videoRepository).flush();               // 상태 판정 전 raw 행 락(UPDATE flush)
        ordered.verify(videoMetaRepository).acquireRawLock(RAW_SN); // materialize 와 동일 advisory 락
        ordered.verify(snapshotService).materialize(eq(RAW_SN), any(LocalDateTime.class));
        ordered.verify(eventPublisher).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("승인후_촬영환경_수정해도_검수완료일시가_보존된다")
    void 승인후_촬영환경_수정해도_검수완료일시가_보존된다() {
        // given — 최초 검수 완료 시각(ORIGINAL_APPROVED_AT)을 가진 활성 스냅샷
        raw(LocalDateTime.of(2026, 1, 15, 22, 0));
        approved();
        hasActiveSnapshot();

        // when — 승인 후 날씨 정정
        service.update(RAW_SN, new EnvironmentMetaUpdateRequest("눈", "NGT", "WINTER"), worker);

        // then — 재동결은 now() 가 아니라 기존 활성 스냅샷의 승인 시각을 그대로 넘겨야 한다(RVW_CMPL_DT 보존)
        verify(snapshotService).materialize(RAW_SN, ORIGINAL_APPROVED_AT);
        verify(snapshotService, never()).materialize(any());   // 1-arg(now()) 경로는 절대 호출 금지
    }

    @Test
    @DisplayName("승인후_촬영환경_수정시_재export는_TaskModifiedEvent_regen플래그로만_트리거하고_DatasetReExportEvent는_발행하지_않는다")
    void 승인후_촬영환경_수정시_재export는_regen플래그로_트리거된다() {
        // given
        raw(LocalDateTime.of(2026, 1, 15, 22, 0));
        approved();
        hasActiveSnapshot();

        // when
        service.update(RAW_SN, new EnvironmentMetaUpdateRequest("눈", "NGT", "WINTER"), worker);

        // then — C-1b(Phase 5C): 재산출은 TaskModifiedEvent(exportRegenerated=true) 한 축으로만 트리거한다.
        //   별도 DatasetReExportEvent 를 병렬로 발행하지 않아 이중 export/이중 통지가 없다.
        //   (재산출 실행은 디바운스 flush 가 export→통지 순서로 직렬화한다.)
        verify(eventPublisher, never()).publishEvent(any(DatasetReExportEvent.class));
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(TaskModifiedEvent.class);
        assertThat(((TaskModifiedEvent) captor.getValue()).exportRegenerated()).isTrue();
    }

    @Test
    @DisplayName("미검수_영상_수정시_재동결_미트리거")
    void 미검수_영상_수정시_재동결_미트리거() {
        // given — 상태행 없음(미검수)이지만 스냅샷 조회가 가능하더라도 트리거되면 안 됨
        raw(LocalDateTime.of(2026, 1, 15, 22, 0));
        hasActiveSnapshot();

        // when
        service.update(RAW_SN, new EnvironmentMetaUpdateRequest("눈", "NGT", "WINTER"), worker);

        // then — 미승인 영상은 재동결 없음(최초 승인 시 materialize 가 수동값을 캡처)
        verify(snapshotService, never()).materialize(any());
        verify(snapshotService, never()).materialize(any(), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("APPROVED이나_활성스냅샷_없으면_재동결_스킵하고_TASK_MODIFIED만_발행")
    void APPROVED이나_활성스냅샷_없으면_재동결_스킵() {
        // given — 승인 상태이나 활성 동결 스냅샷 부재(백필 미완 등 이례)
        raw(LocalDateTime.of(2026, 1, 15, 22, 0));
        approved();

        // when
        service.update(RAW_SN, new EnvironmentMetaUpdateRequest("눈", "NGT", "WINTER"), worker);

        // then — fail-safe skip(선례 동일). 통지는 그대로 발행
        verify(snapshotService, never()).materialize(any());
        verify(snapshotService, never()).materialize(any(), any());
        verify(eventPublisher, times(1)).publishEvent(any(TaskModifiedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(DatasetReExportEvent.class));
    }

    @Test
    @DisplayName("촬영환경_저장은_dirty_checking만_사용하고_전체_save를_호출하지_않는다")
    void 촬영환경_저장은_dirty_checking만_사용() {
        // given
        raw(LocalDateTime.of(2026, 1, 15, 22, 0));

        // when
        service.update(RAW_SN, new EnvironmentMetaUpdateRequest("맑음", "DAY", "SUMMER"), worker);

        // then — 배치가 쓰는 다른 컬럼 덮어쓰기 방지(S8): save/saveAndFlush 미호출
        verify(videoRepository, never()).save(any());
        verify(videoRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("존재하지_않는_영상_조회시_404")
    void 존재하지_않는_영상_조회시_404() {
        // given
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.get(RAW_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
