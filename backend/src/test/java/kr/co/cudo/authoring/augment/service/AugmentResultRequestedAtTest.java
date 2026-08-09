package kr.co.cudo.authoring.augment.service;

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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 증강 결과 조회 응답의 <b>요청일시({@code requestedAt})</b> 회귀 가드.
 *
 * <h3>왜 이 테스트가 필요한가</h3>
 * <p>요청일시는 <b>잡 단위</b> 값인데 {@code AugmentResultViewService.result} 의 반환 지점은 <b>셋</b>이다
 * (① 항목 0건 조기 반환 ② 항목 축 페이지 범위 밖 조기 반환 ③ 정상 경로). 한 곳만 채우면 "빈 페이지에서만
 * 값이 사라지는" 결함이 되고, 화면은 그때만 {@code -} 를 보여 사용자가 데이터 유실로 오인한다.
 * 그래서 세 지점을 <b>각각</b> 고정한다.
 *
 * <h3>산출 축</h3>
 * <p>값은 목록 API({@code GET /v1/augments} → {@code AugmentJobResponse.requestedAt})와 <b>같은 축</b>
 * ({@code MIN(REG_DT)})이며 판정 단일 원천은 {@link AugmentReviewService#resolveRequestedAt} 다. 산출식을
 * 결과 조회 쪽에 복제하면 두 번째 진실원이 생겨 같은 잡이 화면마다 다른 시각을 표시하게 된다.
 *
 * <p>항목별 {@code decidedAt}(채택·반려 <b>결정</b> 시각)과는 축이 달라 서로 대체할 수 없다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class AugmentResultRequestedAtTest {

    private static final long JOB_ID = 4_100L;

    @Mock private LsDataAugRepository augRepository;
    @Mock private LsDataAugRvwRepository reviewRepository;
    @Mock private LsDataAugDscdRepository discardRepository;
    @Mock private AugmentDiscardStateMapper discardStateMapper;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private AugmentReviewService reviewService;
    @Mock private DeidentReportGate deidentReportGate;

    private AugmentResultViewService service;

    /** 이른 요청(그룹 최소 등록일시) — 요청일시의 정답. */
    private static final LocalDateTime EARLIEST = LocalDateTime.of(2026, 8, 1, 9, 30, 0);
    /** 같은 영상의 뒤이은 재요청 — 최소값이 아니므로 요청일시가 되면 안 된다. */
    private static final LocalDateTime LATER = LocalDateTime.of(2026, 8, 3, 18, 5, 0);

    @BeforeEach
    void setUp() {
        service = new AugmentResultViewService(
                augRepository, reviewRepository, discardRepository, discardStateMapper,
                srcRepository, videoRepository, reviewService, deidentReportGate);

        when(reviewService.aggregateResultStatus(anyLong())).thenReturn("COMPLETED");
        when(deidentReportGate.isUnderDeidentReport(anyLong())).thenReturn(false);
        when(videoRepository.findCctvNamesByRawSns(any())).thenReturn(List.of());
        when(reviewRepository.findByDataAugSnIn(any())).thenReturn(List.of());
        when(discardRepository.findByDataAugSnInOrderByDataAugDscdSnDesc(any())).thenReturn(List.of());
    }

    /** 외부 위탁 증강 행 1건 — 파생 매핑(NEW_RAW_SN)은 없어도 항목은 남는다(프레임 조회 불필요). */
    private LsDataAug externalAug(long dataAugSn, LocalDateTime regDt) {
        LsDataAug aug = LsDataAug.builder()
                .srcSn(9_000L)
                .augTypeCd(LsDataAug.AUG_WINTER)
                .augProcSttsCd(LsDataAug.STTS_ACCEPTED)
                .regDt(regDt)
                .regUserNo("1")
                .build();
        ReflectionTestUtils.setField(aug, "dataAugSn", dataAugSn);
        return aug;
    }

    // ============================================================
    // 반환 지점 ③ — 정상 경로
    // ============================================================

    @Test
    @DisplayName("정상_경로에_그룹_최소_등록일시가_요청일시로_실린다")
    void 정상_경로에_그룹_최소_등록일시가_요청일시로_실린다() {
        // given — 같은 영상에 요청이 2건(반복 요청 허용 정책). 요청일시는 <더 이른> 쪽이다.
        when(augRepository.findByOriginalRawSn(JOB_ID))
                .thenReturn(List.of(externalAug(1L, LATER), externalAug(2L, EARLIEST)));

        // when
        AugmentResultResponse res = service.result(JOB_ID, 0, 12, 0, 20);

        // then
        assertThat(res.requestedAt()).isEqualTo(EARLIEST);
        assertThat(res.results()).hasSize(2);
    }

    @Test
    @DisplayName("요청일시_추가로_기존_응답_필드는_바뀌지_않는다")
    void 요청일시_추가로_기존_응답_필드는_바뀌지_않는다() {
        // given — 외부 FE 팀도 쓰는 계약면이라 추가만 허용된다(이름·타입·의미·기본값 불변).
        when(augRepository.findByOriginalRawSn(JOB_ID))
                .thenReturn(List.of(externalAug(1L, EARLIEST)));

        // when
        AugmentResultResponse res = service.result(JOB_ID, 0, 12, 0, 20);

        // then
        assertThat(res.jobId()).isEqualTo(JOB_ID);
        assertThat(res.status()).isEqualTo("COMPLETED");
        assertThat(res.message()).isNotBlank();
        assertThat(res.page()).isZero();
        assertThat(res.size()).isEqualTo(12);
        assertThat(res.itemPage()).isZero();
        assertThat(res.itemSize()).isEqualTo(20);
        assertThat(res.totalElements()).isEqualTo(1);
        assertThat(res.totalPages()).isEqualTo(1);
    }

    // ============================================================
    // 반환 지점 ① — 항목 0건 조기 반환
    // ============================================================

    @Test
    @DisplayName("증강_행이_0건이면_요청일시는_null_이다")
    void 증강_행이_0건이면_요청일시는_null_이다() {
        // given — 지어내지 않는다(잡 진입 시각·현재 시각으로 대체 금지).
        when(augRepository.findByOriginalRawSn(JOB_ID)).thenReturn(List.of());

        // when
        AugmentResultResponse res = service.result(JOB_ID, 0, 12, 0, 20);

        // then
        assertThat(res.requestedAt()).isNull();
        assertThat(res.results()).isEmpty();
        assertThat(res.totalElements()).isZero();
    }

    @Test
    @DisplayName("등록일시가_모두_비면_요청일시는_null_이다")
    void 등록일시가_모두_비면_요청일시는_null_이다() {
        // given — 행은 있으나 REG_DT 가 없는 레거시 형상. 최소값을 만들 근거가 없으므로 null.
        when(augRepository.findByOriginalRawSn(JOB_ID))
                .thenReturn(List.of(externalAug(1L, null)));

        // when
        AugmentResultResponse res = service.result(JOB_ID, 0, 12, 0, 20);

        // then
        assertThat(res.requestedAt()).isNull();
        assertThat(res.results()).hasSize(1);
    }

    // ============================================================
    // 반환 지점 ② — 항목 축 페이지 범위 밖 조기 반환
    // ============================================================

    @Test
    @DisplayName("항목_페이지_범위_밖_조기반환에도_요청일시가_실린다")
    void 항목_페이지_범위_밖_조기반환에도_요청일시가_실린다() {
        // given — 항목이 1건인데 5페이지를 요청하면 슬라이스가 비어 조기 반환한다.
        //         요청일시는 페이징과 무관한 잡 단위 사실이라 이 경로에도 실려야 한다.
        when(augRepository.findByOriginalRawSn(JOB_ID))
                .thenReturn(List.of(externalAug(1L, EARLIEST)));

        // when
        AugmentResultResponse res = service.result(JOB_ID, 0, 12, 5, 20);

        // then
        assertThat(res.results()).isEmpty();
        assertThat(res.requestedAt()).isEqualTo(EARLIEST);
        assertThat(res.totalElements()).isEqualTo(1);
    }

    // ============================================================
    // 산출 축 자체 — 목록 API 와 공유하는 단일 원천
    // ============================================================

    @Test
    @DisplayName("요청일시_산출은_목록과_같은_최소_등록일시_규칙이다")
    void 요청일시_산출은_목록과_같은_최소_등록일시_규칙이다() {
        // given/when/then — null 입력·빈 그룹·전부 null 은 모두 null, 그 외는 최소값.
        assertThat(AugmentReviewService.resolveRequestedAt(null)).isNull();
        assertThat(AugmentReviewService.resolveRequestedAt(List.of())).isNull();
        assertThat(AugmentReviewService.resolveRequestedAt(List.of(externalAug(1L, null)))).isNull();
        assertThat(AugmentReviewService.resolveRequestedAt(
                List.of(externalAug(1L, LATER), externalAug(2L, null), externalAug(3L, EARLIEST))))
                .isEqualTo(EARLIEST);
    }
}
