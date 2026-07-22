package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionChangeRequest;
import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse;
import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse.CreatedDerivative;
import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse.DerivativeStatus;
import kr.co.cudo.authoring.video.dto.ResolutionDerivativeResponse;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.ResolutionDerivativeService;
import kr.co.cudo.authoring.video.service.VideoResolutionService;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 해상도 변경 오케스트레이션 단위 테스트 — Phase 3 (파생영상 전환).
 *
 * <p>검증 범위: 동일 해상도 스킵 · 업스케일 허용 · 프리셋별 파생영상 생성 위임 · 부분 실패 격리 ·
 * 원본/검수 게이트. 실제 예약·부모 락·PII 게이트는 {@link ResolutionDerivativeService} 를 mock 으로
 * 격리한다(Phase 2 재사용, 중복 검증 금지).
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class VideoResolutionServiceTest {

    @Mock VideoRepository videoRepository;
    @Mock LsRawDataStatusRepository statusRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock ImageResizer imageResizer;
    @Mock ResolutionDerivativeService resolutionDerivativeService;

    VideoResolutionService service;

    private final AtomicLong newRawSeq = new AtomicLong(500L);

    @BeforeEach
    void setup() {
        service = new VideoResolutionService(videoRepository, statusRepository, srcRepository,
                imageResizer, resolutionDerivativeService);
        ReflectionTestUtils.setField(service, "storageRawPath", "/tmp/klid-res-p3");

        // 기본: 원본 해상도 실측 3840x2160(4K, 모든 프리셋과 상이), createDerivative 는 순번 RAW_SN 성공 반환.
        // doAnswer/doThrow 형식 — 재-stubbing 시 이전 answer 가 when() 기록 중 실행되는 Mockito 함정 회피.
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{3840, 2160});
        org.mockito.Mockito.doAnswer(inv -> {
                    ResolutionPreset p = inv.getArgument(1);
                    long newRawSn = newRawSeq.getAndIncrement();
                    return new ResolutionDerivativeResponse(newRawSn, newRawSn + 10L,
                            3840, 2160, p.width(), p.height(), 0.5, 0.5);
                }).when(resolutionDerivativeService).createDerivative(any(), any(), anyString());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private LsDataRaw raw(Long rawSn, Long orgnlRawSn) {
        LsDataRaw r = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, rawSn + ".mp4", null, 60);
        setField(r, "rawSn", rawSn);
        if (orgnlRawSn != null) {
            setField(r, "orgnlRawSn", orgnlRawSn);
        }
        return r;
    }

    private void seedFrame(Long rawSn) {
        LsDataSrc frame = LsDataSrc.create(rawSn, 0L, "frames/" + rawSn + "/f0.jpg", null);
        when(srcRepository.findByRawSnAndFrameNo(eq(rawSn), eq(0))).thenReturn(Optional.of(frame));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(rawSn)).thenReturn(List.of(frame));
    }

    private void approved(Long rawSn) {
        LsRawDataStatus st = LsRawDataStatus.initial(rawSn);
        st.transitionTo(LsRawDataStatus.STTS_APPROVED);
        when(statusRepository.findByRawDataIdIn(any())).thenReturn(List.of(st));
    }

    private void srcDimensions(int w, int h) {
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{w, h});
    }

    private ResolutionChangeResponse call(Long rawSn) {
        // presets 미지정(기본) → 표준 3종 전체 생성
        return service.changeResolution(rawSn, new ResolutionChangeRequest(null), "reviewer-1");
    }

    @Test
    @DisplayName("해상도변경_요청시_동일해상도를_제외한_프리셋마다_파생영상이_생성된다")
    void createsDerivativePerPreset() {
        // given: 원본 3840x2160 → 어떤 프리셋과도 동일하지 않음
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedFrame(1L);

        // when
        ResolutionChangeResponse res = call(1L);

        // then: 3종 프리셋 전부 생성
        assertThat(res.derivatives()).hasSize(3);
        assertThat(res.derivatives()).extracting(CreatedDerivative::goalResCd)
                .containsExactlyInAnyOrder("RESL_1080P", "RESL_720P", "RESL_480P");
        assertThat(res.derivatives()).allMatch(d -> d.status() == DerivativeStatus.CREATED);
        assertThat(res.derivatives()).allMatch(d -> d.rawSn() != null);
        verify(resolutionDerivativeService).createDerivative(eq(1L), eq(ResolutionPreset.RESL_1080P), eq("reviewer-1"));
        verify(resolutionDerivativeService).createDerivative(eq(1L), eq(ResolutionPreset.RESL_720P), eq("reviewer-1"));
        verify(resolutionDerivativeService).createDerivative(eq(1L), eq(ResolutionPreset.RESL_480P), eq("reviewer-1"));
    }

    @Test
    @DisplayName("업스케일_프리셋도_400없이_정상_생성된다")
    void upscaleAllowedNo400() {
        // given: 원본 854x480 (== RESL_480P). RESL_480P 는 스킵, 720p/1080p 는 확대 생성.
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedFrame(1L);
        srcDimensions(854, 480);

        // when: 업스케일이라도 예외 없이 생성
        ResolutionChangeResponse res = call(1L);

        // then
        assertThat(res.derivatives()).extracting(CreatedDerivative::goalResCd)
                .containsExactlyInAnyOrder("RESL_1080P", "RESL_720P");
        assertThat(res.derivatives()).allMatch(d -> d.status() == DerivativeStatus.CREATED);
        verify(resolutionDerivativeService).createDerivative(eq(1L), eq(ResolutionPreset.RESL_1080P), anyString());
        verify(resolutionDerivativeService).createDerivative(eq(1L), eq(ResolutionPreset.RESL_720P), anyString());
        verify(resolutionDerivativeService, never()).createDerivative(eq(1L), eq(ResolutionPreset.RESL_480P), anyString());
    }

    @Test
    @DisplayName("원본과_동일_해상도_프리셋은_스킵된다")
    void sameResolutionSkipped() {
        // given: 원본 1280x720 (== RESL_720P)
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedFrame(1L);
        srcDimensions(1280, 720);

        // when
        ResolutionChangeResponse res = call(1L);

        // then: RESL_720P 는 목록에서 제외, 나머지 2종만 생성
        assertThat(res.derivatives()).extracting(CreatedDerivative::goalResCd)
                .containsExactlyInAnyOrder("RESL_1080P", "RESL_480P")
                .doesNotContain("RESL_720P");
        verify(resolutionDerivativeService, never())
                .createDerivative(any(), eq(ResolutionPreset.RESL_720P), anyString());
    }

    @Test
    @DisplayName("모든_대상_프리셋이_원본과_동일해상도면_400_INVALID_INPUT")
    void allTargetPresetsSameResolutionRejected400() {
        // given: 원본 1280x720 (== RESL_720P). 대상 프리셋을 720P 단독으로 지정 → 전량 스킵.
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedFrame(1L);
        srcDimensions(1280, 720);

        // when/then: 적용 가능한 프리셋 0개 → INVALID_INPUT(400). 500(전량 실패)과 구분되는 별개 분기.
        assertThatThrownBy(() -> service.changeResolution(
                1L, new ResolutionChangeRequest(List.of(ResolutionPreset.RESL_720P)), "reviewer-1"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        // 파생 생성은 단 한 번도 시도되지 않는다(전량 스킵이므로).
        verify(resolutionDerivativeService, never()).createDerivative(any(), any(), anyString());
    }

    @Test
    @DisplayName("증강본_orgnlRawSn_null아님_원본은_거부된다")
    void derivedParentRejected() {
        // given: orgnlRawSn 보유(이미 파생/증강본)
        when(videoRepository.findById(2L)).thenReturn(Optional.of(raw(2L, 1L)));

        // when/then
        assertThatThrownBy(() -> call(2L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(resolutionDerivativeService, never()).createDerivative(any(), any(), anyString());
    }

    @Test
    @DisplayName("비APPROVED_원본_해상도변경은_거부된다")
    void notApprovedRejected() {
        // given: IN_REVIEW 상태(미검수)
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        LsRawDataStatus st = LsRawDataStatus.initial(1L);
        st.transitionTo(LsRawDataStatus.STTS_IN_REVIEW);
        when(statusRepository.findByRawDataIdIn(any())).thenReturn(List.of(st));

        // when/then
        assertThatThrownBy(() -> call(1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(resolutionDerivativeService, never()).createDerivative(any(), any(), anyString());
    }

    @Test
    @DisplayName("영상_미존재_시_404")
    void notFound404() {
        when(videoRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> call(404L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("응답에_생성된_rawSn목록과_상태가_포함된다")
    void responseContainsRawSnsAndStatus() {
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedFrame(1L);

        ResolutionChangeResponse res = call(1L);

        assertThat(res.derivatives()).isNotEmpty();
        assertThat(res.derivatives()).allSatisfy(d -> {
            assertThat(d.rawSn()).isNotNull();
            assertThat(d.status()).isEqualTo(DerivativeStatus.CREATED);
            assertThat(d.goalResCd()).startsWith("RESL_");
            assertThat(d.targetW()).isPositive();
            assertThat(d.targetH()).isPositive();
        });
    }

    @Test
    @DisplayName("한_프리셋_생성실패가_다른_프리셋_생성을_막지않는다")
    void partialFailureIsolated() {
        // given: 원본 3840x2160 → 3종 시도. RESL_720P 생성만 실패.
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedFrame(1L);
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.CONFLICT, "동일 해상도 파생 결과 이미 존재"))
                .when(resolutionDerivativeService)
                .createDerivative(eq(1L), eq(ResolutionPreset.RESL_720P), anyString());

        // when
        ResolutionChangeResponse res = call(1L);

        // then: 3건 모두 결과에 표기 — 720p 는 FAILED, 나머지는 CREATED
        assertThat(res.derivatives()).hasSize(3);
        CreatedDerivative failed = res.derivatives().stream()
                .filter(d -> d.goalResCd().equals("RESL_720P")).findFirst().orElseThrow();
        assertThat(failed.status()).isEqualTo(DerivativeStatus.FAILED);
        assertThat(failed.rawSn()).isNull();
        assertThat(res.derivatives()).filteredOn(d -> !d.goalResCd().equals("RESL_720P"))
                .allMatch(d -> d.status() == DerivativeStatus.CREATED && d.rawSn() != null);
        // 실패해도 나머지 프리셋 생성은 계속 시도됨
        verify(resolutionDerivativeService).createDerivative(eq(1L), eq(ResolutionPreset.RESL_1080P), anyString());
        verify(resolutionDerivativeService).createDerivative(eq(1L), eq(ResolutionPreset.RESL_480P), anyString());
    }

    @Test
    @DisplayName("특정_프리셋_목록_지정시_그_목록만_생성된다")
    void specifiedPresetsOnly() {
        // given: 원본 3840x2160 → 어떤 프리셋과도 상이. RESL_720P 만 지정.
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedFrame(1L);

        // when: presets=[RESL_720P] 만 지정
        ResolutionChangeResponse res = service.changeResolution(
                1L, new ResolutionChangeRequest(List.of(ResolutionPreset.RESL_720P)), "reviewer-1");

        // then: 지정한 720p 만 생성, 나머지 프리셋은 시도조차 안 함
        assertThat(res.derivatives()).extracting(CreatedDerivative::goalResCd)
                .containsExactly("RESL_720P");
        verify(resolutionDerivativeService).createDerivative(eq(1L), eq(ResolutionPreset.RESL_720P), anyString());
        verify(resolutionDerivativeService, never())
                .createDerivative(any(), eq(ResolutionPreset.RESL_1080P), anyString());
        verify(resolutionDerivativeService, never())
                .createDerivative(any(), eq(ResolutionPreset.RESL_480P), anyString());
    }

    @Test
    @DisplayName("모든_프리셋_생성이_실패하면_에러상태를_반환한다")
    void allFailedReturnsError() {
        // given: 원본 3840x2160 → 3종 시도. 모든 createDerivative 가 실패.
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedFrame(1L);
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.CONFLICT, "생성 실패"))
                .when(resolutionDerivativeService).createDerivative(any(), any(), anyString());

        // when/then: 전부 FAILED 면 201 성공이 아니라 처리 실패(INTERNAL_ERROR)로 응답
        assertThatThrownBy(() -> call(1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
        // 부분 실패 격리로 3종 모두 시도는 됐다
        verify(resolutionDerivativeService).createDerivative(eq(1L), eq(ResolutionPreset.RESL_1080P), anyString());
        verify(resolutionDerivativeService).createDerivative(eq(1L), eq(ResolutionPreset.RESL_720P), anyString());
        verify(resolutionDerivativeService).createDerivative(eq(1L), eq(ResolutionPreset.RESL_480P), anyString());
    }
}
