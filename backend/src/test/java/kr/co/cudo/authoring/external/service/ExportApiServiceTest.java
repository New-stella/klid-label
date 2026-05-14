package kr.co.cudo.authoring.external.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.external.dto.DatasetResponse;
import kr.co.cudo.authoring.external.dto.FrameLabelInfo;
import kr.co.cudo.authoring.external.dto.LabelInfo;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4 — 외부 학습데이터 API 서비스 단위 테스트.
 * (SpringBootTest + 실제 JPA Repository 로 통합)
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class ExportApiServiceTest {

    @Autowired private ExportApiService service;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsDataMetaRepository metaRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanup() {
        jdbcTemplate.execute("DELETE FROM LS_DATA_LBL");
        jdbcTemplate.execute("DELETE FROM LS_DATA_SRC");
        jdbcTemplate.execute("DELETE FROM LS_DATA_META");
        jdbcTemplate.execute("DELETE FROM LS_DATA_RAW");
    }

    @Test
    @DisplayName("존재하지_않는_rawSn_은_NOT_FOUND")
    void notFoundWhenRawMissing() {
        assertThatThrownBy(() -> service.build(99999L, false))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("정상_빌드_RAW_프레임만_포함_DEID_프레임_제외")
    void buildIncludesOnlyRawFrames() {
        LsDataRaw raw = saveRaw("clip-001", "ANONY");
        LsDataSrc src0 = srcRepository.save(LsDataSrc.create(raw.getRawSn(), 0, "/raw/0.jpg",
                LocalDateTime.now()));
        srcRepository.save(LsDataSrc.create(raw.getRawSn(), 1, "/raw/1.jpg", LocalDateTime.now()));
        // DEID 프레임은 응답에서 제외되어야 함
        srcRepository.save(LsDataSrc.createDeid(raw.getRawSn(), 0, "/deid/0.jpg", LocalDateTime.now()));

        lblRepository.save(LsDataLbl.createAutoBbox(src0.getSrcSn(), "person",
                "[[0,0],[10,10]]", new BigDecimal("0.9000"), "t-1"));

        DatasetResponse response = service.build(raw.getRawSn(), false);

        assertThat(response.video().rawSn()).isEqualTo(raw.getRawSn());
        assertThat(response.labels()).hasSize(2);
        List<Integer> frameNos = response.labels().stream().map(FrameLabelInfo::frameNo).toList();
        assertThat(frameNos).containsExactly(0, 1);
        // DEID 프레임은 포함되지 않음
        assertThat(response.labels().stream().map(FrameLabelInfo::filePath))
                .allSatisfy(p -> assertThat(p).startsWith("/raw/"));
    }

    @Test
    @DisplayName("meta_분리_모드_rawMeta_deidMeta_각각_채워짐")
    void separatedMetaMode() {
        LsDataRaw raw = saveRaw("clip-002", "PRVC");
        metaRepository.save(LsDataMeta.createWithType(raw.getRawSn(), "scene", "highway", LsDataMeta.META_TYPE_RAW));
        metaRepository.save(LsDataMeta.createWithType(raw.getRawSn(), "weather", "clear", LsDataMeta.META_TYPE_DEID));

        DatasetResponse response = service.build(raw.getRawSn(), false);

        assertThat(response.merged()).isFalse();
        assertThat(response.rawMeta()).containsEntry("scene", "highway").hasSize(1);
        assertThat(response.deidMeta()).containsEntry("weather", "clear").hasSize(1);
        assertThat(response.meta()).isNull();
    }

    @Test
    @DisplayName("meta_병합_모드_RAW_DEID_단독_key_모두_포함_RAW_가_앞순서")
    void mergedMetaModeIncludesBothSides() {
        // 주의: LS_DATA_META 의 UK 는 (RAW_SN, META_KEY) — 같은 key 의 RAW/DEID 충돌 row 는 DB 가 거부.
        // 외부 응답은 정상 시나리오(서로 다른 key)에서 양쪽 메타가 모두 포함되어야 한다.
        LsDataRaw raw = saveRaw("clip-003", "PSDO");
        metaRepository.save(LsDataMeta.createWithType(raw.getRawSn(), "scene", "highway", LsDataMeta.META_TYPE_RAW));
        metaRepository.save(LsDataMeta.createWithType(raw.getRawSn(), "weather", "clear", LsDataMeta.META_TYPE_DEID));

        DatasetResponse response = service.build(raw.getRawSn(), true);

        assertThat(response.merged()).isTrue();
        assertThat(response.meta())
                .containsEntry("scene", "highway")
                .containsEntry("weather", "clear");
        // RAW 가 앞 순서 (LinkedHashMap)
        assertThat(response.meta().keySet().iterator().next()).isEqualTo("scene");
        assertThat(response.rawMeta()).isNull();
        assertThat(response.deidMeta()).isNull();
    }

    @Test
    @DisplayName("라벨_없는_영상은_빈_라벨_리스트_원본_프레임_정보는_유지")
    void noLabelsReturnsEmptyArrays() {
        LsDataRaw raw = saveRaw("clip-004", "ANONY");
        srcRepository.save(LsDataSrc.create(raw.getRawSn(), 0, "/raw/0.jpg", LocalDateTime.now()));

        DatasetResponse response = service.build(raw.getRawSn(), false);

        assertThat(response.labels()).hasSize(1);
        assertThat(response.labels().get(0).labels()).isEmpty();
    }

    @Test
    @DisplayName("프레임_정렬은_frameNo_오름차순")
    void framesOrderedByFrameNo() {
        LsDataRaw raw = saveRaw("clip-005", "ANONY");
        srcRepository.save(LsDataSrc.create(raw.getRawSn(), 2, "/raw/2.jpg", LocalDateTime.now()));
        srcRepository.save(LsDataSrc.create(raw.getRawSn(), 0, "/raw/0.jpg", LocalDateTime.now()));
        srcRepository.save(LsDataSrc.create(raw.getRawSn(), 1, "/raw/1.jpg", LocalDateTime.now()));

        DatasetResponse response = service.build(raw.getRawSn(), false);

        List<Integer> nos = response.labels().stream().map(FrameLabelInfo::frameNo).toList();
        assertThat(nos).containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("VideoInfo_에_사용자_식별_정보_미포함")
    void videoInfoOmitsUserIdentifiers() {
        LsDataRaw raw = saveRaw("clip-006", "ANONY");

        DatasetResponse response = service.build(raw.getRawSn(), false);

        // VideoInfo record 의 필드만 노출 — 사용자 식별 컬럼(REG_USER_NO 등)은 미포함.
        // record 컴포넌트 이름으로 보안 가드.
        var components = java.util.Arrays.stream(response.video().getClass().getRecordComponents())
                .map(c -> c.getName().toLowerCase())
                .toList();
        assertThat(components).noneMatch(n -> n.contains("user") || n.contains("reporter")
                || n.contains("uploader") || n.contains("regno"));
    }

    @Test
    @DisplayName("라벨_좌표는_원본_좌표_그대로_트랙ID_신뢰도_포함")
    void labelsPreserveAllFields() {
        LsDataRaw raw = saveRaw("clip-007", "ANONY");
        LsDataSrc src = srcRepository.save(LsDataSrc.create(raw.getRawSn(), 0, "/raw/0.jpg",
                LocalDateTime.now()));
        lblRepository.save(LsDataLbl.createAutoBbox(src.getSrcSn(), "car",
                "[[1,2],[3,4]]", new BigDecimal("0.8500"), "track-7"));

        DatasetResponse response = service.build(raw.getRawSn(), false);

        LabelInfo info = response.labels().get(0).labels().get(0);
        assertThat(info.type()).isEqualTo("BBOX");
        assertThat(info.label()).isEqualTo("car");
        assertThat(info.points()).containsExactly(List.of(1.0, 2.0), List.of(3.0, 4.0));
        assertThat(info.autoGenerated()).isTrue();
        assertThat(info.confidence()).isEqualByComparingTo("0.8500");
        assertThat(info.trackId()).isEqualTo("track-7");
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private LsDataRaw saveRaw(String clipId, String prvcTypeCd) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, "CCTV-001", "EVT-001", "LCL-11", prvcTypeCd,
                "/clips/" + clipId + ".mp4",
                LocalDateTime.now(), 30
        );
        return videoRepository.save(raw);
    }
}
