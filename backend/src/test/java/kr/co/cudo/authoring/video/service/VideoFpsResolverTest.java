package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

/**
 * VideoFpsResolver 단위 테스트 — M-3(30fps 고정 가정) 수정의 단일 fps 소스 검증.
 *
 * <p>저장된 {@code video.fps} 우선 사용 + 미상/파싱불가/비양수 폴백(30.0)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class VideoFpsResolverTest {

    @Mock
    private LsDataMetaRepository metaRepository;

    @InjectMocks
    private VideoFpsResolver resolver;

    private void stubFps(Long rawSn, String storedValue) {
        LsDataMeta meta = LsDataMeta.create(rawSn, "video.fps", storedValue);
        when(metaRepository.findByRawSnAndMetaKey(rawSn, "video.fps")).thenReturn(Optional.of(meta));
    }

    @Test
    @DisplayName("저장fps_존재하면_그값사용_25")
    void resolveFps_stored25_returns25() {
        // given
        Long rawSn = 1L;
        stubFps(rawSn, "25.0");

        // when
        double fps = resolver.resolveFps(rawSn);

        // then
        assertThat(fps).isEqualTo(25.0);
    }

    @Test
    @DisplayName("분수fps_29.97_정확파싱")
    void resolveFps_fractional2997_parsedExactly() {
        // given
        Long rawSn = 2L;
        stubFps(rawSn, "29.97");

        // when
        double fps = resolver.resolveFps(rawSn);

        // then — 분수 fps 를 double 로 정확히 파싱해야 한다.
        assertThat(fps).isCloseTo(29.97, within(1e-9));
    }

    @Test
    @DisplayName("fps미상_메타없음_30폴백")
    void resolveFps_absent_fallback30() {
        // given — video.fps 메타가 아직 적재되지 않음 (Phase 2 async 미완/실패)
        Long rawSn = 3L;
        when(metaRepository.findByRawSnAndMetaKey(rawSn, "video.fps")).thenReturn(Optional.empty());

        // when
        double fps = resolver.resolveFps(rawSn);

        // then — 기존 30fps 고정 가정과 동일 (무회귀)
        assertThat(fps).isEqualTo(VideoFpsResolver.DEFAULT_FPS);
        assertThat(fps).isEqualTo(30.0);
    }

    @Test
    @DisplayName("파싱불가_문자열_30폴백")
    void resolveFps_unparsable_fallback30() {
        // given
        Long rawSn = 4L;
        stubFps(rawSn, "not-a-number");

        // when
        double fps = resolver.resolveFps(rawSn);

        // then — fail-safe: 예외 없이 30 폴백
        assertThat(fps).isEqualTo(30.0);
    }

    @Test
    @DisplayName("fps_0이면_30폴백")
    void resolveFps_zero_fallback30() {
        // given
        Long rawSn = 5L;
        stubFps(rawSn, "0");

        // when / then
        assertThat(resolver.resolveFps(rawSn)).isEqualTo(30.0);
    }

    @Test
    @DisplayName("fps_음수면_30폴백")
    void resolveFps_negative_fallback30() {
        // given
        Long rawSn = 6L;
        stubFps(rawSn, "-12.5");

        // when / then
        assertThat(resolver.resolveFps(rawSn)).isEqualTo(30.0);
    }

    @Test
    @DisplayName("fps_blank값_30폴백")
    void resolveFps_blank_fallback30() {
        // given
        Long rawSn = 7L;
        stubFps(rawSn, "   ");

        // when / then
        assertThat(resolver.resolveFps(rawSn)).isEqualTo(30.0);
    }

    @Test
    @DisplayName("rawSn_null이면_조회없이_30폴백")
    void resolveFps_nullRawSn_fallback30() {
        // when / then — 입력검증 fail-safe: null rawSn 은 DB 조회 없이 폴백
        assertThat(resolver.resolveFps(null)).isEqualTo(30.0);
    }

    @Test
    @DisplayName("fps_Infinity면_비유한수라_30폴백")
    void resolveFps_infinity_fallback30() {
        // given — 외부 ffprobe 유래 신뢰불가 값. Double.parseDouble("Infinity")=+Inf → isFinite=false.
        Long rawSn = 8L;
        stubFps(rawSn, "Infinity");

        // when / then — isFinite 분기로 폴백(0 나눗셈/오버플로 방지)
        assertThat(resolver.resolveFps(rawSn)).isEqualTo(30.0);
    }

    @Test
    @DisplayName("fps_NaN이면_비양수판정으로_30폴백")
    void resolveFps_nan_fallback30() {
        // given — Double.parseDouble("NaN")=NaN. NaN>0 은 false → 폴백.
        Long rawSn = 9L;
        stubFps(rawSn, "NaN");

        // when / then
        assertThat(resolver.resolveFps(rawSn)).isEqualTo(30.0);
    }
}
