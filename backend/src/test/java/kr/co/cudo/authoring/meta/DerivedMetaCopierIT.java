package kr.co.cudo.authoring.meta;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.meta.service.DerivedMetaCopier;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link DerivedMetaCopier} 통합 테스트(실 DB, PostgreSQL Testcontainer) — 파생 메타 전건 복사 + 검수행
 * 정책을 실제 UNIQUE 제약·native upsert 로 실증한다. Mockito 단위 테스트가 검증할 수 없는 다음을 커버한다:
 * <ul>
 *   <li>{@code video.*} 포함 전건 복사가 실제 (RAW_SN, META_KEY) UK 하에 정확히 적재된다.</li>
 *   <li>부모 검수행 있던 메타만 파생에 미검수(PENDING) 검수행 신규 생성(APPROVED 미승계).</li>
 *   <li><b>재실행(중복 복사)</b> 시 실제 UNIQUE(DATA_META_SN, META_TYPE_CD) 위반 없이 흡수(선재 skip).</li>
 * </ul>
 *
 * <p>공유 컨테이너 오염 방지를 위해 시드는 {@code MCP-} 고유 clipId 로 만들고 단언은 시드한 RAW 로만 좁힌다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager") // 프로덕션은 persist() REQUIRES_NEW 안에서 호출 — IT 는 control tx 로 감싸고 롤백 정리.
class DerivedMetaCopierIT {

    @Autowired DerivedMetaCopier copier;
    @Autowired LsDataMetaRepository metaRepository;
    @Autowired LsDataMetaReviewRepository reviewRepository;
    @Autowired VideoRepository videoRepository;

    private LsDataRaw seedRaw(String suffix) {
        return videoRepository.save(LsDataRaw.createFromIngest(
                "MCP-" + suffix, "CCTV-MCP", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/MCP-" + suffix + ".mp4",
                LocalDateTime.now(), 30));
    }

    @Test
    @DisplayName("실DB_해상도·증강공용_메타전건복사_video포함_및_VLM검수행만_PENDING신규생성")
    void copiesAllMetaAndCreatesPendingReviewForVlmOnly() {
        LsDataRaw parent = seedRaw("A-PARENT");
        LsDataRaw derived = seedRaw("A-DERIVED");

        LsDataMeta vlm = metaRepository.save(LsDataMeta.create(parent.getRawSn(), "vlm.seg.0", "a person walking"));
        metaRepository.save(LsDataMeta.create(parent.getRawSn(), "video.fps", "30"));
        metaRepository.save(LsDataMeta.create(parent.getRawSn(), "video.resolution", "1920x1080"));
        metaRepository.save(LsDataMeta.create(parent.getRawSn(), "weather", "snow"));
        // 부모 VLM 메타는 APPROVED 검수행 보유(원본은 검수 완료됨).
        reviewRepository.save(LsDataMetaReview.createAuto(
                vlm.getMetaSn(), parent.getRawSn(), null,
                LsDataMetaReview.META_TYPE_VLM, LsDataMetaReview.SRC_AI_SERVER,
                LsDataMetaReview.STTS_APPROVED));

        DerivedMetaCopier.CopyResult r = copier.copyMetaAndReviews(parent.getRawSn(), derived.getRawSn());

        assertThat(r.copiedMetaCount()).isEqualTo(4);
        assertThat(r.createdReviewCount()).isEqualTo(1);

        Map<String, String> copied = metaRepository.findByRawSn(derived.getRawSn()).stream()
                .collect(Collectors.toMap(LsDataMeta::getMetaKey, LsDataMeta::getMetaVl));
        // video.* 포함 전건 복사 — 원본값 그대로.
        assertThat(copied).containsEntry("video.fps", "30")
                .containsEntry("video.resolution", "1920x1080")
                .containsEntry("weather", "snow")
                .containsEntry("vlm.seg.0", "a person walking");

        List<LsDataMetaReview> derivedReviews = reviewRepository.findAllByDataRawSn(derived.getRawSn());
        assertThat(derivedReviews).hasSize(1);
        LsDataMetaReview rv = derivedReviews.get(0);
        assertThat(rv.getMetaTypeCd()).isEqualTo(LsDataMetaReview.META_TYPE_VLM);
        assertThat(rv.getSrcSysCd()).isEqualTo(LsDataMetaReview.SRC_AI_SERVER);
        // 파생은 미검수로 시작 — 원본 APPROVED 를 승계하지 않는다.
        assertThat(rv.getRvwSttsCd()).isEqualTo(LsDataMetaReview.STTS_PENDING);
        // 검수행이 없던 video.* 메타에는 파생 검수행이 만들어지지 않는다.
        Long vlmDerivedMetaSn = metaRepository.findByRawSnAndMetaKey(derived.getRawSn(), "vlm.seg.0")
                .orElseThrow().getMetaSn();
        assertThat(rv.getDataMetaSn()).isEqualTo(vlmDerivedMetaSn);
    }

    @Test
    @DisplayName("실DB_재실행_중복복사시_UNIQUE위반없이_흡수_신규검수행_0건_예외전파없음")
    void reRunAbsorbsUniqueConflict() {
        LsDataRaw parent = seedRaw("B-PARENT");
        LsDataRaw derived = seedRaw("B-DERIVED");
        LsDataMeta vlm = metaRepository.save(LsDataMeta.create(parent.getRawSn(), "vlm.seg.0", "desc"));
        reviewRepository.save(LsDataMetaReview.createAuto(
                vlm.getMetaSn(), parent.getRawSn(), null,
                LsDataMetaReview.META_TYPE_VLM, LsDataMetaReview.SRC_AI_SERVER,
                LsDataMetaReview.STTS_PENDING));

        DerivedMetaCopier.CopyResult first = copier.copyMetaAndReviews(parent.getRawSn(), derived.getRawSn());
        assertThat(first.createdReviewCount()).isEqualTo(1);

        // 2차 복사 — 실제 DB 에 이미 (DATA_META_SN, META_TYPE_CD) 검수행 존재. 선재 skip 이 없으면 UNIQUE 위반.
        assertThatCode(() -> {
            DerivedMetaCopier.CopyResult second = copier.copyMetaAndReviews(parent.getRawSn(), derived.getRawSn());
            assertThat(second.createdReviewCount()).isZero();
        }).doesNotThrowAnyException();

        assertThat(reviewRepository.findAllByDataRawSn(derived.getRawSn())).hasSize(1);
    }

    @Test
    @DisplayName("실DB_한metaSn에_VLM과_EXTERNAL_두유형검수행이면_파생에_유형별_2건_PENDING_생성_UNIQUE위반없음_MEDIUM3")
    void inheritsBothReviewTypesForSameMetaSnOnRealDb() {
        LsDataRaw parent = seedRaw("C-PARENT");
        LsDataRaw derived = seedRaw("C-DERIVED");

        LsDataMeta both = metaRepository.save(LsDataMeta.create(parent.getRawSn(), "meta.both.0", "value"));
        // 한 metaSn 에 VLM + EXTERNAL 두 유형 검수행 공존(UNIQUE(DATA_META_SN, META_TYPE_CD) 하에 실제 2행).
        reviewRepository.save(LsDataMetaReview.createAuto(
                both.getMetaSn(), parent.getRawSn(), null,
                LsDataMetaReview.META_TYPE_VLM, LsDataMetaReview.SRC_AI_SERVER,
                LsDataMetaReview.STTS_APPROVED));
        reviewRepository.save(LsDataMetaReview.createAuto(
                both.getMetaSn(), parent.getRawSn(), null,
                LsDataMetaReview.META_TYPE_EXTERNAL, LsDataMetaReview.SRC_CONTROL_SERVER,
                LsDataMetaReview.STTS_APPROVED));

        DerivedMetaCopier.CopyResult r = copier.copyMetaAndReviews(parent.getRawSn(), derived.getRawSn());

        // 유형별로 모두 승계 — 구 toMap 이 1건만 남기고 조용히 드롭하던 회귀 가드(실 UNIQUE 제약 하 2행 공존).
        assertThat(r.createdReviewCount()).isEqualTo(2);
        List<LsDataMetaReview> derivedReviews = reviewRepository.findAllByDataRawSn(derived.getRawSn());
        assertThat(derivedReviews).hasSize(2);
        Long derivedMetaSn = metaRepository.findByRawSnAndMetaKey(derived.getRawSn(), "meta.both.0")
                .orElseThrow().getMetaSn();
        assertThat(derivedReviews).allSatisfy(rv -> {
            assertThat(rv.getDataMetaSn()).isEqualTo(derivedMetaSn);
            assertThat(rv.getRvwSttsCd()).isEqualTo(LsDataMetaReview.STTS_PENDING);
        });
        assertThat(derivedReviews).extracting(LsDataMetaReview::getMetaTypeCd)
                .containsExactlyInAnyOrder(LsDataMetaReview.META_TYPE_VLM, LsDataMetaReview.META_TYPE_EXTERNAL);
    }
}
