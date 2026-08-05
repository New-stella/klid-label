package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관제 인입 평면값 조회({@link IngestSourceRepository}) 실동작 검증 — 관제 공유 마스터 4종 제거 후
 * <b>CCTV 명·지자체명·원천 개인정보 3필드의 유일한 조달 경로</b>다.
 *
 * <p>검증 축:
 * <ul>
 *   <li><b>원본</b> — 인입 평면값이 그대로 조회된다(구 {@code MNG_RESOURCE_CCTV} 조인 대체).</li>
 *   <li><b>파생</b> — 자기 인입 행이 없어도 {@code ORGNL_RAW_SN} 1단계 폴백으로 부모 값을 본다.</li>
 *   <li><b>파생의 원천 개인정보 3필드는 null</b> — 폴백을 <b>타지 않는</b> 유일한 예외
 *       ({@link IngestSourceLink} javadoc 의 근거 참조). 결손이 아니라 정상이다.</li>
 *   <li><b>인입 행 다중</b> — 수기 정정 등으로 같은 {@code RAW_SN} 에 2행이 있어도 LATERAL 상한이
 *       <b>최신 1행</b>만 고르고 행이 증식하지 않는다.</li>
 * </ul>
 *
 * <p>시드는 {@code SRCMETA-IT-} 접두로 격리하고 테스트 트랜잭션 롤백으로 정리한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class IngestSourceRepositoryIT {

    private static final String CLIP_PREFIX = "SRCMETA-IT-";

    @Autowired private IngestSourceRepository sourceRepository;
    @Autowired private VideoRepository videoRepository;

    private final JdbcTemplate jdbc;

    IngestSourceRepositoryIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    @DisplayName("CCTV명이_인입_평면값에서_조회된다")
    void CCTV명이_인입_평면값에서_조회된다() {
        // given: 원본 영상 + 관제가 CCTV 명·지역명·개인정보 3필드를 실어 보낸 인입 행
        LsDataRaw origin = saveOrigin("O1");
        insertIngest(origin.getRawSn(), CLIP_PREFIX + "O1", "동대문구 회기로 CCTV", "서울특별시 동대문구",
                "Y", "N", "N");

        // when
        IngestSourceRow row = sourceRepository.findSourceMeta(origin.getRawSn());

        // then: 마스터 조인 없이 인입 평면값이 그대로 나온다
        assertThat(row).isNotNull();
        assertThat(row.getCctvNm()).isEqualTo("동대문구 회기로 CCTV");
        assertThat(row.getRgnNm()).isEqualTo("서울특별시 동대문구");
        assertThat(row.getSrcAnonyInclYn()).isEqualTo("Y");
        assertThat(row.getSrcPsdoInclYn()).isEqualTo("N");
        assertThat(row.getSrcPrvcInclYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("파생영상도_부모_인입행_폴백으로_CCTV명이_표시된다")
    void 파생영상도_부모_인입행_폴백으로_CCTV명이_표시된다() {
        // given: 파생영상은 자기 인입 행이 없다(저작도구가 직접 만든 것이라 관제 인입으로 오지 않는다)
        LsDataRaw origin = saveOrigin("O2");
        insertIngest(origin.getRawSn(), CLIP_PREFIX + "O2", "강남구 테헤란로 CCTV", "서울특별시 강남구",
                "Y", "N", "N");
        LsDataRaw derived = saveDerived(origin);
        assertThat(countIngestOf(derived.getRawSn())).isZero();

        // when
        IngestSourceRow row = sourceRepository.findSourceMeta(derived.getRawSn());

        // then: ORGNL_RAW_SN 1단계 폴백으로 부모 인입 행의 이름·지역이 보인다
        assertThat(row).isNotNull();
        assertThat(row.getCctvNm()).isEqualTo("강남구 테헤란로 CCTV");
        assertThat(row.getRgnNm()).isEqualTo("서울특별시 강남구");
    }

    @Test
    @DisplayName("파생영상은_원천_개인정보_3필드가_null_이다")
    void 파생영상은_원천_개인정보_3필드가_null_이다() {
        // given: 부모 인입 행에는 개인정보 3필드가 모두 채워져 있다
        LsDataRaw origin = saveOrigin("O3");
        insertIngest(origin.getRawSn(), CLIP_PREFIX + "O3", "마포구 CCTV", "서울특별시 마포구",
                "Y", "Y", "Y");
        LsDataRaw derived = saveDerived(origin);

        // when
        IngestSourceRow row = sourceRepository.findSourceMeta(derived.getRawSn());

        // then: 이름·지역은 폴백하지만 <원천 개인정보 3필드는 폴백하지 않는다>.
        //   파생의 개인정보 판정은 비식별 축이고 그 값은 이미 자기 RAW 행에 계승돼 있다
        //   (copyPrivacyMetaFrom) — 여기서 부모의 <비식별 전> 판정을 실으면 축이 뒤섞인다.
        assertThat(row.getCctvNm()).isEqualTo("마포구 CCTV");
        assertThat(row.getSrcAnonyInclYn()).isNull();
        assertThat(row.getSrcPsdoInclYn()).isNull();
        assertThat(row.getSrcPrvcInclYn()).isNull();
    }

    @Test
    @DisplayName("같은_영상에_인입행이_둘이어도_최신_1행만_보고_행이_증식하지_않는다")
    void 같은_영상에_인입행이_둘이어도_최신_1행만_보고_행이_증식하지_않는다() {
        // given: RAW_SN 에는 UNIQUE 가 없어 수기 정정 등으로 2행이 생길 수 있다
        LsDataRaw origin = saveOrigin("O4");
        insertIngest(origin.getRawSn(), CLIP_PREFIX + "O4-old", "옛 이름", "옛 지역", "N", "N", "N");
        insertIngest(origin.getRawSn(), CLIP_PREFIX + "O4-new", "새 이름", "새 지역", "Y", "N", "Y");

        // when
        IngestSourceRow row = sourceRepository.findSourceMeta(origin.getRawSn());

        // then: 최신(RCPTN_SN DESC) 1행 — 예외 없이 결정적으로 1행이 나온다
        assertThat(row).isNotNull();
        assertThat(row.getCctvNm()).isEqualTo("새 이름");
        assertThat(row.getSrcPrvcInclYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("인입행이_없어도_예외없이_전필드_null_로_조회된다")
    void 인입행이_없어도_예외없이_전필드_null_로_조회된다() {
        // given: 인입 행이 아직/영영 없는 영상
        LsDataRaw origin = saveOrigin("O5");

        // when
        IngestSourceRow row = sourceRepository.findSourceMeta(origin.getRawSn());

        // then: 행 자체는 나오되 값이 전부 null (호출부가 VMS_CCTV_ID 폴백을 판단할 수 있어야 한다)
        assertThat(row).isNotNull();
        assertThat(row.getCctvNm()).isNull();
        assertThat(row.getRgnNm()).isNull();
        assertThat(row.getSrcAnonyInclYn()).isNull();
    }

    @Test
    @DisplayName("배치_CCTV명_조회도_파생영상을_부모_인입행으로_해석한다")
    void 배치_CCTV명_조회도_파생영상을_부모_인입행으로_해석한다() {
        // given
        LsDataRaw origin = saveOrigin("O6");
        insertIngest(origin.getRawSn(), CLIP_PREFIX + "O6", "송파구 CCTV", "서울특별시 송파구",
                "Y", "N", "N");
        LsDataRaw derived = saveDerived(origin);

        // when: 목록 화면들이 공유하는 배치 lookup
        List<Object[]> rows = videoRepository.findCctvNamesByRawSns(
                List.of(origin.getRawSn(), derived.getRawSn()));

        // then: 원본·파생 각 1행씩, 둘 다 같은 CCTV 명
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat((String) r[1]).isEqualTo("송파구 CCTV"));
    }

    // ---------------------------------------------------------------- fixtures

    private LsDataRaw saveOrigin(String suffix) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                CLIP_PREFIX + suffix, "CCTV-SRCMETA-" + suffix, "EV01000101", "11110",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + CLIP_PREFIX + suffix + ".mp4",
                LocalDateTime.of(2026, 5, 10, 0, 0), 30);
        return videoRepository.saveAndFlush(raw);
    }

    private LsDataRaw saveDerived(LsDataRaw parent) {
        LsDataRaw derived = LsDataRaw.createFromResolution(
                parent, "/var/raw/deriv-" + parent.getRawSn() + ".mp4", "RESL_480P");
        return videoRepository.saveAndFlush(derived);
    }

    private long countIngestOf(Long rawSn) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_DATA_INGEST WHERE RAW_SN = ?", Long.class, rawSn);
        return n == null ? 0L : n;
    }

    /** 관제가 INSERT 하는 인입 행을 JDBC 로 재현한다(우리는 이 행을 만들지 않는다). */
    private void insertIngest(Long rawSn, String clipId, String cctvNm, String rgnNm,
                              String anony, String psdo, String prvc) {
        jdbc.update("""
                INSERT INTO LS_DATA_INGEST
                    (RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, SRC_TYPE,
                     RCPTN_DT, PROC_STTS_CD, CCTV_NM, RGN_NM,
                     ANONY_INCL_YN, PSDO_INCL_YN, PRVC_INCL_YN)
                VALUES (?, ?, 'CCTV-SRCMETA', 'f.mp4', '/var/raw/f.mp4', 'ORIGINAL',
                        CURRENT_TIMESTAMP, 'DONE', ?, ?, ?, ?, ?)
                """, rawSn, clipId, cctvNm, rgnNm, anony, psdo, prvc);
    }
}
