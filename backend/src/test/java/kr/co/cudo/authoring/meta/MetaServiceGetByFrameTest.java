package kr.co.cudo.authoring.meta;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.meta.dto.MetaResponse;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.meta.service.MetaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MetaService.getByFrame 의 시계열 메타 조회 + 검토상태 조인 단위 테스트 (Mockito).
 *
 * <p>R6(Phase 6-D): 라벨링 화면 시계열 메타에 REVIEWER 검수 표면(승인/반려)을 배선하려면
 * 조회 응답이 각 메타의 검토행 PK(dataMetaReviewSn)와 상태(reviewStatus)를 담아야 한다.
 * N+1 방지를 위해 metaSn 집합으로 검토행을 배치 조회한다.
 */
class MetaServiceGetByFrameTest {

    private static final Long SRC_SN = 7001L;
    private static final Long RAW_SN = 8001L;
    private static final String ACTOR_SUB = "1001";

    private LsDataMetaRepository metaRepository;
    private LsDataSrcRepository srcRepository;
    private LsTaskAssignmentRepository authrtRepository;
    private LsDataMetaReviewRepository metaReviewRepository;
    private ApplicationEventPublisher eventPublisher;
    private ReviewApprovalGate approvalGate;
    private MetaService service;

    @BeforeEach
    void setUp() {
        metaRepository = mock(LsDataMetaRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        authrtRepository = mock(LsTaskAssignmentRepository.class);
        metaReviewRepository = mock(LsDataMetaReviewRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        approvalGate = mock(ReviewApprovalGate.class);

        service = new MetaService(metaRepository, srcRepository, authrtRepository,
                metaReviewRepository, eventPublisher, approvalGate);

        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "/raw/f0.jpg", null);
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(src));
    }

    private TokenClaims reviewer() {
        return new TokenClaims(ACTOR_SUB, Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private LsDataMeta metaWithSn(String key, String val, long metaSn) {
        LsDataMeta meta = LsDataMeta.create(RAW_SN, key, val);
        ReflectionTestUtils.setField(meta, "metaSn", metaSn);
        return meta;
    }

    private LsDataMetaReview reviewWithSn(long metaSn, String status, long reviewSn) {
        LsDataMetaReview review = LsDataMetaReview.createAuto(
                metaSn, RAW_SN, null, LsDataMetaReview.META_TYPE_EXTERNAL, null, status);
        ReflectionTestUtils.setField(review, "dataMetaReviewSn", reviewSn);
        return review;
    }

    @Test
    @DisplayName("getByFrame_검토행_있으면_reviewStatus_metaReviewSn_포함")
    void getByFrame_검토행_있으면_포함() {
        // given
        LsDataMeta m1 = metaWithSn("weather", "rain", 500L);
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(m1));
        when(metaReviewRepository.findByDataMetaSnIn(anyCollection()))
                .thenReturn(List.of(reviewWithSn(500L, LsDataMetaReview.STTS_PENDING, 9001L)));

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then
        MetaResponse.Item item = res.items().get(0);
        assertThat(item.metaSn()).isEqualTo(500L);
        assertThat(item.dataMetaReviewSn()).isEqualTo(9001L);
        assertThat(item.reviewStatus()).isEqualTo(LsDataMetaReview.STTS_PENDING);
    }

    @Test
    @DisplayName("getByFrame_검토행_없는_메타는_두값_null")
    void getByFrame_검토행_없으면_null() {
        // given — 검토행 없는 메타
        LsDataMeta m1 = metaWithSn("weather", "rain", 500L);
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(m1));
        when(metaReviewRepository.findByDataMetaSnIn(anyCollection())).thenReturn(List.of());

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then
        MetaResponse.Item item = res.items().get(0);
        assertThat(item.dataMetaReviewSn()).isNull();
        assertThat(item.reviewStatus()).isNull();
    }

    @Test
    @DisplayName("getByFrame_N+1_없음_검토행_배치조회_1회")
    void getByFrame_N플러스1_없음() {
        // given — 메타 3건 (검토행 배치 조회가 1회여야 함, per-meta 조회 금지)
        LsDataMeta m1 = metaWithSn("k1", "v1", 501L);
        LsDataMeta m2 = metaWithSn("k2", "v2", 502L);
        LsDataMeta m3 = metaWithSn("k3", "v3", 503L);
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(m1, m2, m3));
        when(metaReviewRepository.findByDataMetaSnIn(anyCollection()))
                .thenReturn(List.of(reviewWithSn(502L, LsDataMetaReview.STTS_APPROVED, 9002L)));

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then — 검토행 배치 조회는 정확히 1회, per-meta 단건 조회(exists/findById) 미사용
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(metaReviewRepository, times(1)).findByDataMetaSnIn(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(501L, 502L, 503L);
        verify(metaReviewRepository, never()).existsByDataMetaSn(anyLong());
        verify(metaReviewRepository, never()).findById(anyLong());

        // then — 매핑 정확: 502 만 검토행 보유
        assertThat(res.items()).hasSize(3);
        MetaResponse.Item withReview = res.items().stream()
                .filter(i -> i.metaSn().equals(502L)).findFirst().orElseThrow();
        assertThat(withReview.dataMetaReviewSn()).isEqualTo(9002L);
        assertThat(withReview.reviewStatus()).isEqualTo(LsDataMetaReview.STTS_APPROVED);
    }

    @Test
    @DisplayName("getByFrame_메타_0건이면_검토행_조회_생략_빈응답")
    void getByFrame_메타_0건() {
        // given
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of());

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then — 조회할 metaSn 이 없으면 배치 조회조차 하지 않음(불필요 쿼리 제거)
        assertThat(res.items()).isEmpty();
        verify(metaReviewRepository, never()).findByDataMetaSnIn(anyCollection());
    }
}
