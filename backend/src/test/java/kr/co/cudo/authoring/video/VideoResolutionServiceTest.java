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
import kr.co.cudo.authoring.video.service.ParentDeidArtifactGuard;
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
 * <p>검증 범위: 산출 크기 동일 스킵 · 업스케일 허용 · 프리셋별 파생영상 생성 위임 · 부분 실패 격리 ·
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
    @Mock ParentDeidArtifactGuard parentDeidArtifactGuard;

    VideoResolutionService service;

    private final AtomicLong newRawSeq = new AtomicLong(500L);

    @BeforeEach
    void setup() {
        service = new VideoResolutionService(videoRepository, statusRepository, srcRepository,
                imageResizer, resolutionDerivativeService, parentDeidArtifactGuard);
        ReflectionTestUtils.setField(service, "storageRawPath", "/tmp/klid-res-p3");
        ReflectionTestUtils.setField(service, "storageDeidentifiedPath", "/tmp/klid-res-p3-deid");

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

    /** 첫 프레임의 비식별 경로(deidFilePath)를 지정한 절대 경로로 세팅. 실측 소스는 deid 우선이다. */
    private void seedDeidFrame(Long rawSn, String deidAbsolutePath) {
        LsDataSrc frame = LsDataSrc.create(rawSn, 0L, "frames/" + rawSn + "/f0.jpg", null);
        frame.attachDeidPath(deidAbsolutePath);
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
    @DisplayName("4대3_원본은_1080p가_스킵되어_파생이_3건이_아니라_2건이다")
    void fourByThreeSourceSkips1080pPreset() {
        // given: 원본 1440x1080(4:3). 짧은 변이 이미 1080 이라 RESL_1080P 의 배율이 1.0 이고
        //        산출 크기가 원본과 같아 스킵된다(@design ADR-018).
        //        ⚠ 사용자 가시 동작 변화 — 구 고정 캔버스 규칙에서는 1920x1080 필러박스 파생이
        //        생성되어 파생이 3건이었다. 이제 2건이다.
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedFrame(1L);
        srcDimensions(1440, 1080);

        // when: presets 미지정 → 표준 3종 전체가 대상.
        ResolutionChangeResponse res = call(1L);

        // then: 1080p 만 빠지고 720p(960x720) · 480p(640x480) 2건이 생성된다.
        assertThat(res.derivatives()).hasSize(2);
        assertThat(res.derivatives()).extracting(CreatedDerivative::goalResCd)
                .containsExactlyInAnyOrder("RESL_720P", "RESL_480P")
                .doesNotContain("RESL_1080P");
        verify(resolutionDerivativeService, never())
                .createDerivative(any(), eq(ResolutionPreset.RESL_1080P), anyString());
        assertThat(res.derivatives()).filteredOn(d -> d.goalResCd().equals("RESL_720P"))
                .allMatch(d -> d.targetW() == 960 && d.targetH() == 720);
        assertThat(res.derivatives()).filteredOn(d -> d.goalResCd().equals("RESL_480P"))
                .allMatch(d -> d.targetW() == 640 && d.targetH() == 480);
    }

    @Test
    @DisplayName("비16대9_원본은_응답의_targetW_targetH가_프리셋수치가_아니라_실제_산출크기다")
    void responseCarriesActualOutputSize() {
        // given: 원본 1440x1080(4:3). 720p 프리셋은 상한이므로 산출은 960x720 이다(@design ADR-018).
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedFrame(1L);
        srcDimensions(1440, 1080);

        // when
        ResolutionChangeResponse res = service.changeResolution(
                1L, new ResolutionChangeRequest(List.of(ResolutionPreset.RESL_720P)), "reviewer-1");

        // then: 프리셋 수치(1280x720)가 아니라 산출 크기(960x720)를 싣는다.
        CreatedDerivative d = res.derivatives().get(0);
        assertThat(d.goalResCd()).isEqualTo("RESL_720P");
        assertThat(d.targetW()).isEqualTo(960);
        assertThat(d.targetH()).isEqualTo(720);
        assertThat(d.targetW()).isNotEqualTo(ResolutionPreset.RESL_720P.width());
    }

    @Test
    @DisplayName("세로영상은_프리셋_긴변보다_긴_세로로_산출된다")
    void portraitSourceProducesTallerOutput() {
        // given: 원본 1080x1920(세로). 720p 산출은 720x1280 — 박스 피팅이면 405x720 로 과소 산출된다.
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedFrame(1L);
        srcDimensions(1080, 1920);

        ResolutionChangeResponse res = service.changeResolution(
                1L, new ResolutionChangeRequest(List.of(ResolutionPreset.RESL_720P)), "reviewer-1");

        CreatedDerivative d = res.derivatives().get(0);
        assertThat(d.targetW()).isEqualTo(720);
        assertThat(d.targetH()).isEqualTo(1280);
    }

    @Test
    @DisplayName("파생실패도_프리셋수치가_아니라_산출크기로_표기된다")
    void failedDerivativeAlsoCarriesActualOutputSize() {
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedFrame(1L);
        srcDimensions(1440, 1080);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(resolutionDerivativeService)
                .createDerivative(eq(1L), eq(ResolutionPreset.RESL_720P), anyString());

        // 720p 는 실패, 480p(산출 640x480)는 성공 → 201 유지(부분 실패 격리).
        // ⚠ 1080p 는 이 원본에서 배율 1.0(산출 1440x1080 = 원본)이라 스킵되므로 성공 짝으로 쓸 수 없다.
        ResolutionChangeResponse res = service.changeResolution(
                1L, new ResolutionChangeRequest(
                        List.of(ResolutionPreset.RESL_720P, ResolutionPreset.RESL_480P)), "reviewer-1");

        CreatedDerivative failed = res.derivatives().stream()
                .filter(d -> d.goalResCd().equals("RESL_720P")).findFirst().orElseThrow();
        assertThat(failed.status()).isEqualTo(DerivativeStatus.FAILED);
        assertThat(failed.targetW()).isEqualTo(960);
        assertThat(failed.targetH()).isEqualTo(720);
    }

    @Test
    @DisplayName("산출크기가_원본과_같으면_프리셋수치가_달라도_스킵된다")
    void skipsWhenOutputSizeEqualsSourceEvenIfPresetDiffers() {
        // given: 원본 960x720. 720p 프리셋 수치(1280x720)와는 다르지만 산출 크기는 960x720 = 원본이다.
        //        구 판정(preset.width()==srcW)은 1280!=960 이라 스킵하지 않아 <b>원본과 동일한 파생본</b>을
        //        만들었다 — 그 회귀를 여기서 고정한다.
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedFrame(1L);
        srcDimensions(960, 720);

        assertThatThrownBy(() -> service.changeResolution(
                1L, new ResolutionChangeRequest(List.of(ResolutionPreset.RESL_720P)), "reviewer-1"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
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

    /**
     * 적대검증 MEDIUM-1 — {@code 'F'} 가 "비식별 API 실패(산출물 부재)" 인 요청은 예약 이전에 동기 거부해야
     * 한다. 통과시키면 201 CREATED 후 async 확정이 반드시 실패하고 cleanup 이 파생 RAW·예약행을 지워
     * 사용자에겐 성공으로 보이면서 목록은 0건이 된다(가시성 회귀).
     */
    @Test
    @DisplayName("부모_비식별_산출물이_없으면_예약전에_동기거부되고_파생생성은_시도되지_않는다")
    void missingParentDeidArtifactRejectedBeforeReservation() {
        // given: APPROVED 원본이지만 부모 비식별 산출물이 실재하지 않는다(게이트가 CONFLICT).
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedFrame(1L);
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.CONFLICT,
                        "원본의 비식별 산출물을 찾을 수 없어 해상도 파생을 만들 수 없습니다."))
                .when(parentDeidArtifactGuard).requireParentDeidVideoPresent(any());

        // when/then: 201 이 아니라 동기 4xx. 예약(파생 RAW·aug 행) 자체가 생기지 않는다.
        assertThatThrownBy(() -> call(1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(resolutionDerivativeService, never()).createDerivative(any(), any(), anyString());
    }

    @Test
    @DisplayName("부모가_신고_F여도_비식별_산출물이_있으면_파생영상이_정상_생성된다")
    void reportedParentWithArtifactStillCreatesDerivatives() {
        // given: 게이트 통과(산출물 실재) — 신고 여부는 파생 생성을 막지 않는다(2026-07-29 확정).
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedFrame(1L);

        // when
        ResolutionChangeResponse res = call(1L);

        // then
        assertThat(res.derivatives()).hasSize(3);
        assertThat(res.derivatives()).allMatch(d -> d.status() == DerivativeStatus.CREATED);
        verify(parentDeidArtifactGuard).requireParentDeidVideoPresent(any());
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
    @DisplayName("비식별_프레임_경로는_deid_base로_통과하여_해상도변경이_성공한다")
    void deidFramePathPassesViaDeidBase() {
        // given: 첫 프레임 소스가 비식별 base(/tmp/klid-res-p3-deid) 하위 절대경로 — raw base 밖.
        //        구버전은 raw base 만 검증해 "경로가 허용된 저장 경로를 벗어납니다" 로 전 영상 실패했다.
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedDeidFrame(1L, "/tmp/klid-res-p3-deid/frames/deid/1/f0.jpg");

        // when: deid base 로 통과 → 정상 파생 생성
        ResolutionChangeResponse res = call(1L);

        // then: 예외 없이 3종 파생 생성(=경로 오류 해소)
        assertThat(res.derivatives()).hasSize(3);
        assertThat(res.derivatives()).allMatch(d -> d.status() == DerivativeStatus.CREATED);
    }

    @Test
    @DisplayName("원본_프레임_경로는_raw_base로_통과한다")
    void rawFramePathPassesViaRawBase() {
        // given: deid 경로가 없어 srcFilePathNm(raw base 하위 상대경로) 로 실측
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedFrame(1L);

        // when/then: raw base 로 통과
        ResolutionChangeResponse res = call(1L);
        assertThat(res.derivatives()).hasSize(3);
    }

    @Test
    @DisplayName("상위경로_traversal(..)_은_여전히_INVALID_INPUT으로_차단된다")
    void traversalStillRejected() {
        // given: deid base 를 벗어나는 traversal 절대경로(CWE-22)
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedDeidFrame(1L, "/tmp/klid-res-p3-deid/../../etc/passwd");

        // when/then: normalize 후 두 base 모두 밖 → INVALID_INPUT(400) 유지
        assertThatThrownBy(() -> call(1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(resolutionDerivativeService, never()).createDerivative(any(), any(), anyString());
    }

    @Test
    @DisplayName("raw도_deid도_아닌_경로는_INVALID_INPUT으로_차단된다")
    void outsideBothBasesRejected() {
        // given: 두 base 어디에도 속하지 않는 절대경로
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        approved(1L);
        seedDeidFrame(1L, "/tmp/klid-res-elsewhere/secret.jpg");

        // when/then
        assertThatThrownBy(() -> call(1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(resolutionDerivativeService, never()).createDerivative(any(), any(), anyString());
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

    // ==========================================================================================
    // listDerivatives — 프리셋 판별은 AUG_TYPE_CD 컬럼 단일 원천(구 VMS_CLIP_ID 역파싱 폐기)
    // ==========================================================================================

    /**
     * 확정 파생 1건 — {@code AUG_TYPE_CD} 컬럼값과 {@code VMS_CLIP_ID} 원문을 <b>따로</b> 지정한다.
     * clipId 를 마커 없는 값으로 덮어써 "판별이 컬럼에서만 나온다" 는 사실을 드러낸다.
     */
    private LsDataRaw derivative(Long rawSn, String augTypeCd, String vmsClipId) {
        LsDataRaw d = raw(rawSn, 1L);
        setField(d, "augTypeCd", augTypeCd);
        setField(d, "vmsClipId", vmsClipId);
        d.markDeidentified("Y");
        d.markCompleted();
        return d;
    }

    @Test
    @DisplayName("해상도_파생의_프리셋_판별이_컬럼값으로_동작한다")
    void listDerivativesResolvesPresetFromColumn() {
        // given — clipId 에 마커가 전혀 없다(구 파서라면 판별 실패로 제외됐을 형태).
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        when(videoRepository.findAllByOrgnlRawSnOrderByRawSnAsc(1L)).thenReturn(List.of(
                derivative(501L, "RESL_1080P", "clip-no-marker-a"),
                derivative(502L, "RESL_480P", "clip-no-marker-b")));

        // when
        ResolutionChangeResponse res = service.listDerivatives(1L);

        // then — 컬럼값이 프리셋으로 해석되고 크기(px)까지 채워진다.
        //        이 케이스는 프레임을 심지 않아 원본 실측이 불가하므로 프리셋 상한 폴백 값이 실린다
        //        (실측 가능할 때 실제 산출 크기를 싣는 것은 listDerivativesReportsActualOutputSize 가 고정).
        assertThat(res.derivatives()).hasSize(2);
        assertThat(res.derivatives()).extracting(CreatedDerivative::goalResCd)
                .containsExactly("RESL_1080P", "RESL_480P");
        CreatedDerivative first = res.derivatives().get(0);
        assertThat(first.rawSn()).isEqualTo(501L);
        assertThat(first.targetW()).isEqualTo(ResolutionPreset.RESL_1080P.width());
        assertThat(first.targetH()).isEqualTo(ResolutionPreset.RESL_1080P.height());
        assertThat(first.status()).isEqualTo(DerivativeStatus.COMPLETED);
    }

    @Test
    @DisplayName("파생목록조회도_실측가능하면_실제_산출크기를_돌려준다")
    void listDerivativesReportsActualOutputSize() {
        // given: 원본 1440x1080(4:3) 실측 가능. 720p 파생의 산출 크기는 프리셋 수치 1280x720 이 아니라
        //        960x720 이다 — 생성 API 와 같은 축을 돌려줘야 화면이 두 값을 다르게 보여주지 않는다.
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        seedFrame(1L);
        srcDimensions(1440, 1080);
        when(videoRepository.findAllByOrgnlRawSnOrderByRawSnAsc(1L)).thenReturn(List.of(
                derivative(521L, "RESL_720P", "clip-a")));

        ResolutionChangeResponse res = service.listDerivatives(1L);

        CreatedDerivative d = res.derivatives().get(0);
        assertThat(d.targetW()).isEqualTo(960);
        assertThat(d.targetH()).isEqualTo(720);
    }

    @Test
    @DisplayName("파생목록조회는_원본_실측이_불가해도_프리셋상한으로_폴백하고_실패하지_않는다")
    void listDerivativesFallsBackWhenSourceUnmeasurable() {
        // 프레임을 심지 않아 실측이 불가한 상태 — 확정 실패를 보여주는 통로라 4xx/5xx 를 내면 안 된다.
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        when(videoRepository.findAllByOrgnlRawSnOrderByRawSnAsc(1L)).thenReturn(List.of(
                derivative(531L, "RESL_720P", "clip-b")));

        ResolutionChangeResponse res = service.listDerivatives(1L);

        CreatedDerivative d = res.derivatives().get(0);
        assertThat(d.targetW()).isEqualTo(ResolutionPreset.RESL_720P.width());
        assertThat(d.targetH()).isEqualTo(ResolutionPreset.RESL_720P.height());
    }

    @Test
    @DisplayName("해상도_파생이_아닌_파생과_미상값은_목록에서_제외된다")
    void listDerivativesExcludesNonResolutionAndUnknown() {
        // given — 외부 증강(WINTER) / 레거시 단일코드(RESOLUTION) / 미지 코드 / AUG_TYPE_CD 미채움(null).
        //         네 경우 모두 <예외 없이> 조용히 제외돼야 한다(fail-safe, CWE-20).
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(1L, null)));
        when(videoRepository.findAllByOrgnlRawSnOrderByRawSnAsc(1L)).thenReturn(List.of(
                derivative(511L, "WINTER", "clip_AUG_WINTER_1"),
                derivative(512L, "RESOLUTION", "clip_AUG_RESOLUTION_1"),
                derivative(513L, "RESL_2160P", "clip_RESL_2160P_1"),
                derivative(514L, null, "clip_RESL_720P_1"),
                derivative(515L, "RESL_720P", "clip-no-marker")));

        // when
        ResolutionChangeResponse res = service.listDerivatives(1L);

        // then — 표준 프리셋 코드를 가진 1건만 남는다.
        assertThat(res.derivatives()).hasSize(1);
        assertThat(res.derivatives().get(0).goalResCd()).isEqualTo("RESL_720P");
        assertThat(res.derivatives().get(0).rawSn()).isEqualTo(515L);
    }
}
