package kr.co.cudo.authoring.meta;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepositoryCustom.MetaUpsert;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.meta.service.DerivedMetaCopier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DerivedMetaCopier} 단위 테스트 — 파생영상(증강·해상도) 메타 전체복사 + 검수행 정책의 단일 진실원 검증.
 *
 * <p>사용자 확정 3항목:
 * <ol>
 *   <li>메타 값 <b>전체 복사</b>({@code video.*} 기술메타 포함).</li>
 *   <li>부모에 검수행이 있던 메타키만 <b>미검수(PENDING)</b> 검수행 신규 생성 — 유형/출처는 승계, 상태는 미검수.</li>
 *   <li>중복/재실행 안전 — 이미 검수행이 있으면 선재 skip(같은 tx UNIQUE 위반 차단).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DerivedMetaCopierTest {

    @Mock LsDataMetaRepository metaRepository;
    @Mock LsDataMetaReviewRepository reviewRepository;
    @InjectMocks DerivedMetaCopier copier;

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private LsDataMeta meta(Long metaSn, Long rawSn, String key, String val) {
        LsDataMeta m = LsDataMeta.create(rawSn, key, val);
        setField(m, "metaSn", metaSn);
        return m;
    }

    private LsDataMetaReview review(Long dataMetaSn, Long rawSn, String type, String src, String stts) {
        return LsDataMetaReview.createAuto(dataMetaSn, rawSn, null, type, src, stts);
    }

    @Test
    @DisplayName("메타_전건복사_video기술메타도_포함해_upsert된다")
    void copiesAllMetaIncludingVideoTechnicalKeys() {
        Long parent = 100L, derived = 900L;
        when(metaRepository.findByRawSn(parent)).thenReturn(List.of(
                meta(1L, parent, "video.fps", "30"),
                meta(2L, parent, "video.resolution", "1920x1080"),
                meta(3L, parent, "weather", "snow")));
        when(reviewRepository.findAllByDataRawSn(parent)).thenReturn(List.of());

        DerivedMetaCopier.CopyResult r = copier.copyMetaAndReviews(parent, derived);

        assertThat(r.copiedMetaCount()).isEqualTo(3);
        assertThat(r.createdReviewCount()).isZero();
        // video.* 기술메타도 원본값으로 파생에 복사(전건 복사) — 단일 배치 upsert 1회로 위임(HIGH#1).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MetaUpsert>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(metaRepository).upsertMetaBatch(eq(derived), batchCaptor.capture());
        assertThat(batchCaptor.getValue())
                .extracting(MetaUpsert::metaKey, MetaUpsert::metaVl)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("video.fps", "30"),
                        org.assertj.core.groups.Tuple.tuple("video.resolution", "1920x1080"),
                        org.assertj.core.groups.Tuple.tuple("weather", "snow"));
        verify(reviewRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("부모에_VLM검수행_있던_메타만_파생에_미검수PENDING_검수행_신규생성_유형출처승계_APPROVED미승계")
    void createsPendingReviewOnlyForMetaThatHadParentReview() {
        Long parent = 101L, derived = 901L;
        // 부모: vlm 세그(검수행 APPROVED 존재) + video.fps(검수행 없음).
        when(metaRepository.findByRawSn(parent)).thenReturn(List.of(
                meta(10L, parent, "vlm.seg.0", "a person walking"),
                meta(11L, parent, "video.fps", "30")));
        when(reviewRepository.findAllByDataRawSn(parent)).thenReturn(List.of(
                review(10L, parent, LsDataMetaReview.META_TYPE_VLM, LsDataMetaReview.SRC_AI_SERVER,
                        LsDataMetaReview.STTS_APPROVED)));
        // 파생 새 metaSn — vlm.seg.0 만 검수행 대상이므로 그 키만 재조회된다.
        when(metaRepository.findByRawSnAndMetaKeyIn(eq(derived), anyCollection()))
                .thenReturn(List.of(meta(510L, derived, "vlm.seg.0", "a person walking")));
        when(reviewRepository.findByDataMetaSnIn(anyCollection())).thenReturn(List.of());

        DerivedMetaCopier.CopyResult r = copier.copyMetaAndReviews(parent, derived);

        assertThat(r.copiedMetaCount()).isEqualTo(2);
        assertThat(r.createdReviewCount()).isEqualTo(1);
        ArgumentCaptor<List<LsDataMetaReview>> captor = ArgumentCaptor.forClass(List.class);
        verify(reviewRepository).saveAll(captor.capture());
        List<LsDataMetaReview> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        LsDataMetaReview rv = saved.get(0);
        assertThat(rv.getDataMetaSn()).isEqualTo(510L);         // 파생 새 metaSn
        assertThat(rv.getDataRawSn()).isEqualTo(derived);
        assertThat(rv.getMetaTypeCd()).isEqualTo(LsDataMetaReview.META_TYPE_VLM);   // 유형 승계
        assertThat(rv.getSrcSysCd()).isEqualTo(LsDataMetaReview.SRC_AI_SERVER);     // 출처 승계
        assertThat(rv.getRvwSttsCd()).isEqualTo(LsDataMetaReview.STTS_PENDING);     // 미검수(APPROVED 승계 안 함)
    }

    @Test
    @DisplayName("부모_검수행없던_메타키는_값만복사_검수행_생성안함")
    void noReviewForMetaWithoutParentReview() {
        Long parent = 102L, derived = 902L;
        when(metaRepository.findByRawSn(parent)).thenReturn(List.of(
                meta(20L, parent, "weather", "rain"),
                meta(21L, parent, "video.codec", "h264")));
        when(reviewRepository.findAllByDataRawSn(parent)).thenReturn(List.of()); // 부모 검수행 전무

        DerivedMetaCopier.CopyResult r = copier.copyMetaAndReviews(parent, derived);

        assertThat(r.copiedMetaCount()).isEqualTo(2);
        assertThat(r.createdReviewCount()).isZero();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MetaUpsert>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(metaRepository).upsertMetaBatch(eq(derived), batchCaptor.capture());
        assertThat(batchCaptor.getValue())
                .extracting(MetaUpsert::metaKey, MetaUpsert::metaVl)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("weather", "rain"),
                        org.assertj.core.groups.Tuple.tuple("video.codec", "h264"));
        verify(reviewRepository, never()).saveAll(any());
        // 검수행 대상 메타키가 없으므로 파생 metaSn 재조회 자체가 불필요.
        verify(metaRepository, never()).findByRawSnAndMetaKeyIn(any(), anyCollection());
    }

    @Test
    @DisplayName("중복복사_이미_검수행존재하면_선재skip_예외없이_신규검수행_0건")
    void duplicateCopySkipsExistingReviewNoException() {
        Long parent = 103L, derived = 903L;
        when(metaRepository.findByRawSn(parent)).thenReturn(List.of(
                meta(30L, parent, "vlm.seg.0", "desc")));
        when(reviewRepository.findAllByDataRawSn(parent)).thenReturn(List.of(
                review(30L, parent, LsDataMetaReview.META_TYPE_VLM, LsDataMetaReview.SRC_AI_SERVER,
                        LsDataMetaReview.STTS_APPROVED)));
        when(metaRepository.findByRawSnAndMetaKeyIn(eq(derived), anyCollection()))
                .thenReturn(List.of(meta(530L, derived, "vlm.seg.0", "desc")));
        // 재실행: 파생 metaSn 530 에 이미 검수행이 존재 → 선재 skip.
        when(reviewRepository.findByDataMetaSnIn(anyCollection())).thenReturn(List.of(
                review(530L, derived, LsDataMetaReview.META_TYPE_VLM, LsDataMetaReview.SRC_AI_SERVER,
                        LsDataMetaReview.STTS_PENDING)));

        // 값 복사는 멱등 재수행, 검수행은 선재 skip 으로 신규 0건 — 예외 전파 없음(같은 tx UNIQUE abort 차단).
        assertThatCode(() -> {
            DerivedMetaCopier.CopyResult r = copier.copyMetaAndReviews(parent, derived);
            assertThat(r.copiedMetaCount()).isEqualTo(1);
            assertThat(r.createdReviewCount()).isZero();
        }).doesNotThrowAnyException();
        verify(reviewRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("한_metaSn에_VLM과_EXTERNAL_두유형_검수행이_있으면_파생에_유형별로_2건_모두_승계된다_MEDIUM3")
    void inheritsAllReviewTypesForSameMetaSn() {
        Long parent = 105L, derived = 905L;
        // 부모: 한 metaSn(40) 에 VLM + EXTERNAL 두 유형 검수행 공존(UNIQUE(metaSn, typeCd) 허용).
        when(metaRepository.findByRawSn(parent)).thenReturn(List.of(
                meta(40L, parent, "meta.both.0", "value")));
        when(reviewRepository.findAllByDataRawSn(parent)).thenReturn(List.of(
                review(40L, parent, LsDataMetaReview.META_TYPE_VLM, LsDataMetaReview.SRC_AI_SERVER,
                        LsDataMetaReview.STTS_APPROVED),
                review(40L, parent, LsDataMetaReview.META_TYPE_EXTERNAL, LsDataMetaReview.SRC_CONTROL_SERVER,
                        LsDataMetaReview.STTS_APPROVED)));
        when(metaRepository.findByRawSnAndMetaKeyIn(eq(derived), anyCollection()))
                .thenReturn(List.of(meta(540L, derived, "meta.both.0", "value")));
        when(reviewRepository.findByDataMetaSnIn(anyCollection())).thenReturn(List.of());

        DerivedMetaCopier.CopyResult r = copier.copyMetaAndReviews(parent, derived);

        // 유형 1건만 남기고 드롭하던 구 toMap 버그 회귀 가드 — 두 유형 모두 PENDING 신규 생성.
        assertThat(r.createdReviewCount()).isEqualTo(2);
        ArgumentCaptor<List<LsDataMetaReview>> captor = ArgumentCaptor.forClass(List.class);
        verify(reviewRepository).saveAll(captor.capture());
        List<LsDataMetaReview> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved).allSatisfy(rv -> {
            assertThat(rv.getDataMetaSn()).isEqualTo(540L);                       // 파생 새 metaSn
            assertThat(rv.getRvwSttsCd()).isEqualTo(LsDataMetaReview.STTS_PENDING); // 미검수(APPROVED 미승계)
        });
        assertThat(saved).extracting(LsDataMetaReview::getMetaTypeCd)
                .containsExactlyInAnyOrder(LsDataMetaReview.META_TYPE_VLM, LsDataMetaReview.META_TYPE_EXTERNAL);
        // 출처(SRC_SYS_CD)도 유형별로 정확히 승계된다.
        LsDataMetaReview vlm = saved.stream()
                .filter(rv -> LsDataMetaReview.META_TYPE_VLM.equals(rv.getMetaTypeCd())).findFirst().orElseThrow();
        LsDataMetaReview ext = saved.stream()
                .filter(rv -> LsDataMetaReview.META_TYPE_EXTERNAL.equals(rv.getMetaTypeCd())).findFirst().orElseThrow();
        assertThat(vlm.getSrcSysCd()).isEqualTo(LsDataMetaReview.SRC_AI_SERVER);
        assertThat(ext.getSrcSysCd()).isEqualTo(LsDataMetaReview.SRC_CONTROL_SERVER);
    }

    @Test
    @DisplayName("부모_메타_0건이면_복사0건_예외없음_no_op")
    void parentMetasEmptyIsNoOp() {
        Long parent = 104L, derived = 904L;
        when(metaRepository.findByRawSn(parent)).thenReturn(List.of());

        DerivedMetaCopier.CopyResult r = copier.copyMetaAndReviews(parent, derived);

        assertThat(r.copiedMetaCount()).isZero();
        assertThat(r.createdReviewCount()).isZero();
        verify(metaRepository, never()).upsertMetaBatch(any(), any());
        verify(reviewRepository, never()).saveAll(any());
        verify(reviewRepository, never()).findAllByDataRawSn(any());
    }
}
