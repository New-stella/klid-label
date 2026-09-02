package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DerivativeSourceVideoResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 위탁 입력 프레임의 <b>조달처</b> — 관제는 비식별본, 포털 업로드 자산은 본인 원본.
 *
 * <h3>이 시험이 지키는 것</h3>
 * <ul>
 *   <li><b>기본값은 비식별본</b> — 출처를 판별하지 못해도 관제 원본이 나가지 않는다.</li>
 *   <li><b>서로 폴백하지 않는다</b> — 관제 자산은 비식별 경로가 비어 있어도 원본 조달처를
 *       <b>부르지조차 않는다</b>. 「없으면 원본」으로 뒤집으면 그 순간 fail-open 이다.</li>
 * </ul>
 *
 * <p>판정기는 실물을 쓴다 — 목으로 대체하면 채널 판정을 이 시험이 확인하지 못한다.
 */
class AugmentInputFrameSourceTest {

    private static final long RAW_SN = 700L;

    private VideoRepository videoRepository;
    private LsDataSrcRepository srcRepository;
    private AugmentInputFrameSource source;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        source = new AugmentInputFrameSource(videoRepository, srcRepository,
                new DerivativeSourceVideoResolver(null, null, null));

        when(srcRepository.findDeidFramePathsByRawSn(anyLong()))
                .thenReturn(rows("/deid/0.jpg"));
        when(srcRepository.findOriginalFramePathsByRawSn(anyLong()))
                .thenReturn(rows("/raw/0.jpg"));
    }

    /** {@code [srcSn, path]} 한 줄 — 리포지토리 계약 모양 그대로. */
    private static List<Object[]> rows(String path) {
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(new Object[]{1L, path});
        return rows;
    }

    private void givenParent(String srcType) {
        LsDataRaw parent = LsDataRaw.createPortalUpload("owner", "/p/v.mp4");
        ReflectionTestUtils.setField(parent, "srcType", srcType);
        ReflectionTestUtils.setField(parent, "rawSn", RAW_SN);
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(parent));
    }

    @Test
    @DisplayName("관제_영상은_비식별_프레임_경로로_조달한다")
    void controlAssetUsesDeidentifiedPaths() {
        givenParent(LsDataRaw.SRC_TYPE_IMPORTED);

        assertThat(source.pathsOf(RAW_SN)).extracting(r -> r[1]).containsExactly("/deid/0.jpg");
        verify(srcRepository, never()).findOriginalFramePathsByRawSn(anyLong());
    }

    @Test
    @DisplayName("포털_업로드_자산은_본인_원본_프레임_경로로_조달한다")
    void portalAssetUsesOriginalPaths() {
        givenParent(LsDataRaw.SRC_TYPE_PORTAL_ULD);

        assertThat(source.pathsOf(RAW_SN)).extracting(r -> r[1]).containsExactly("/raw/0.jpg");
        verify(srcRepository, never()).findDeidFramePathsByRawSn(anyLong());
    }

    @Test
    @DisplayName("★출처를_판별할_수_없으면_비식별본이다 — 기본값이_관제_쪽이라_원본이_새지_않는다")
    void unknownSourceFallsBackToDeidentified() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.empty());

        assertThat(source.pathsOf(RAW_SN)).extracting(r -> r[1]).containsExactly("/deid/0.jpg");
        verify(srcRepository, never()).findOriginalFramePathsByRawSn(anyLong());
    }

    @Test
    @DisplayName("영상_식별자가_없어도_비식별본이다 — 조달처가_비어_열리지_않는다")
    void nullRawSnFallsBackToDeidentified() {
        source.pathsOf(null);

        verify(srcRepository).findDeidFramePathsByRawSn(null);
        verify(srcRepository, never()).findOriginalFramePathsByRawSn(anyLong());
        verify(srcRepository, never()).findOriginalFramePathsByRawSn(null);
    }

    /**
     * ★ 가드 — 포털 조달처를 연 것이 <b>관제의 비식별 요구를 무르게 하지 않는다</b>.
     * 관제 자산의 비식별 경로가 비어 있어도 원본 조달처는 부르지 않으며, 빈 경로가 그대로 올라가
     * 호출부가 fail-closed 로 위탁을 거부한다.
     */
    @Test
    @DisplayName("★관제_자산의_비식별_경로가_비어도_원본으로_폴백하지_않는다")
    void controlAssetNeverFallsBackToOriginalWhenDeidPathMissing() {
        givenParent(LsDataRaw.SRC_TYPE_IMPORTED);
        when(srcRepository.findDeidFramePathsByRawSn(RAW_SN))
                .thenReturn(rows((String) null));

        assertThat(source.pathsOf(RAW_SN)).extracting(r -> r[1]).containsOnlyNulls();
        verify(srcRepository, never()).findOriginalFramePathsByRawSn(anyLong());
    }
}
