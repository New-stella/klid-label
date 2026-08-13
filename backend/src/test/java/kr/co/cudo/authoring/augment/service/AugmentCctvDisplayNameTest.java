package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.augment.dto.AugmentJobResponse;
import kr.co.cudo.authoring.augment.dto.AugmentResultResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugDscdRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRvwRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 증강 화면의 영상 표시명 폴백 — <b>다른 화면과 같은 표기</b>를 쓴다.
 *
 * <h3>왜 필요한가</h3>
 * <p>증강 검수 목록과 증강 결과 조회는 각자 {@code "(이름 없음)"} 상수를 들고 있었다. 같은 영상이
 * 작업목록에서는 {@code 영상 #501}, 증강 화면에서는 {@code (이름 없음)} 으로 보였고, 후자는 어느
 * 영상인지 <b>식별조차 되지 않는다</b>(여러 행이 모두 같은 문구가 된다).
 *
 * <p>판정은 {@code CctvDisplayNamePolicy} 한 곳이 소유한다 — 화면이 자기 상수를 들면 한쪽만 갱신돼
 * 표기가 다시 갈린다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class AugmentCctvDisplayNameTest {

    /** 원본 영상 PK — 잡 카드의 videoId/jobId 이자 폴백 표기의 근거. */
    private static final long RAW_SN = 4_200L;
    /** 대표 프레임 PK — 증강 그룹 키. */
    private static final long SRC_SN = 9_000L;

    @Mock private LsDataAugRepository augRepository;
    @Mock private LsDataAugRvwRepository reviewRepository;
    @Mock private LsDataAugDscdRepository discardRepository;
    @Mock private AugmentDiscardStateMapper discardStateMapper;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private ExternalAugmentClient externalClient;
    @Mock private AugmentDiscardService discardService;
    @Mock private DeidentReportGate deidentReportGate;

    private AugmentReviewService reviewService;
    private AugmentResultViewService resultViewService;

    @BeforeEach
    void setUp() {
        reviewService = new AugmentReviewService(
                augRepository, reviewRepository, srcRepository, videoRepository,
                externalClient, discardService);
        resultViewService = new AugmentResultViewService(
                augRepository, reviewRepository, discardRepository, discardStateMapper,
                srcRepository, videoRepository, reviewService, deidentReportGate);

        when(reviewRepository.findByDataAugSnIn(any())).thenReturn(List.of());
        when(discardRepository.findByDataAugSnInOrderByDataAugDscdSnDesc(any())).thenReturn(List.of());
        when(deidentReportGate.isUnderDeidentReport(anyLong())).thenReturn(false);
        // SRC_SN → RAW_SN 역매핑은 항상 성립한다(운영 형상).
        when(srcRepository.findRawSnBySrcSnIn(any()))
                .thenReturn(List.<Object[]>of(new Object[]{SRC_SN, RAW_SN}));
    }

    private LsDataAug aug(long dataAugSn) {
        LsDataAug e = LsDataAug.builder()
                .srcSn(SRC_SN)
                .augTypeCd(LsDataAug.AUG_WINTER)
                .augProcSttsCd(LsDataAug.STTS_ACCEPTED)
                .regDt(LocalDateTime.of(2026, 8, 1, 9, 30))
                .regUserNo("1")
                .build();
        ReflectionTestUtils.setField(e, "dataAugSn", dataAugSn);
        return e;
    }

    /** CCTV명·CCTV ID 가 둘 다 없는 영상 1건(V185 이후 실재하는 형상). */
    private void videoWithoutAnyCctvIdentifier() {
        when(videoRepository.findCctvNamesByRawSns(any()))
                .thenReturn(List.<Object[]>of(new Object[]{RAW_SN, null, null}));
    }

    /** CCTV명이 있는 정상 영상 1건. */
    private void videoNamed(String cctvNm) {
        when(videoRepository.findCctvNamesByRawSns(any()))
                .thenReturn(List.<Object[]>of(new Object[]{RAW_SN, cctvNm, "CCTV-001"}));
    }

    // ------------------------------------------------------------ 증강 검수 목록

    @Test
    @DisplayName("증강_검수목록_영상명이_없으면_영상번호_표기로_폴백한다")
    void 증강_검수목록_영상명이_없으면_영상번호_표기로_폴백한다() {
        // given — 구 표기 "(이름 없음)" 은 여러 행이 같은 문구가 되어 식별 불가였다.
        videoWithoutAnyCctvIdentifier();
        when(augRepository.findBySrcSnOrderByAugTypeCd(SRC_SN)).thenReturn(List.of(aug(1L)));

        // when
        Page<AugmentJobResponse> page = reviewService.findBySource(SRC_SN);

        // then
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).cctvName()).isEqualTo("영상 #" + RAW_SN);
    }

    @Test
    @DisplayName("증강_검수목록_영상명이_있으면_그대로_쓴다")
    void 증강_검수목록_영상명이_있으면_그대로_쓴다() {
        // given / when — 회귀 가드.
        videoNamed("유성구 어은동 사거리");
        when(augRepository.findBySrcSnOrderByAugTypeCd(SRC_SN)).thenReturn(List.of(aug(1L)));

        Page<AugmentJobResponse> page = reviewService.findBySource(SRC_SN);

        // then
        assertThat(page.getContent().get(0).cctvName()).isEqualTo("유성구 어은동 사거리");
    }

    // ------------------------------------------------------------ 증강 결과 조회

    @Test
    @DisplayName("증강_결과조회_영상명이_없으면_영상번호_표기로_폴백한다")
    void 증강_결과조회_영상명이_없으면_영상번호_표기로_폴백한다() {
        // given
        videoWithoutAnyCctvIdentifier();
        when(augRepository.findByOriginalRawSn(RAW_SN)).thenReturn(List.of(aug(1L)));

        // when
        AugmentResultResponse res = resultViewService.result(RAW_SN, 0, 12, 0, 20);

        // then
        assertThat(res.results()).hasSize(1);
        assertThat(res.results().get(0).cctvName()).isEqualTo("영상 #" + RAW_SN);
    }

    @Test
    @DisplayName("증강_결과조회_영상_행이_아예_없어도_영상번호_표기로_식별한다")
    void 증강_결과조회_영상_행이_아예_없어도_영상번호_표기로_식별한다() {
        // given — 조회 결과가 0행이면 이름을 조달할 축이 전혀 없다. 그래도 빈칸/무의미 문구는 안 된다.
        when(videoRepository.findCctvNamesByRawSns(any())).thenReturn(List.of());
        when(augRepository.findByOriginalRawSn(RAW_SN)).thenReturn(List.of(aug(1L)));

        // when
        AugmentResultResponse res = resultViewService.result(RAW_SN, 0, 12, 0, 20);

        // then
        assertThat(res.results().get(0).cctvName()).isEqualTo("영상 #" + RAW_SN);
    }

    @Test
    @DisplayName("증강_결과조회_영상명이_있으면_그대로_쓴다")
    void 증강_결과조회_영상명이_있으면_그대로_쓴다() {
        // given / when — 회귀 가드.
        videoNamed("유성구 어은동 사거리");
        when(augRepository.findByOriginalRawSn(RAW_SN)).thenReturn(List.of(aug(1L)));

        AugmentResultResponse res = resultViewService.result(RAW_SN, 0, 12, 0, 20);

        // then
        assertThat(res.results().get(0).cctvName()).isEqualTo("유성구 어은동 사거리");
    }
}
