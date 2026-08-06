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
        assertThat(row.getLclgvNm()).isEqualTo("서울특별시 동대문구");
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
        assertThat(row.getLclgvNm()).isEqualTo("서울특별시 강남구");
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
    @DisplayName("이벤트분류_카테고리코드가_인입_평면값에서_조회된다")
    void 이벤트분류_카테고리코드가_인입_평면값에서_조회된다() {
        // given: 관제가 이벤트 분류·카테고리 코드를 실어 보냈다. 이 두 컬럼은 LS_DATA_RAW 에 없어
        //   조회 시점 인입 조인이 유일한 조달 경로다(설계결정 D1) — 완료 통지 evnt_cls_cd/evnt_ctgry_cd.
        LsDataRaw origin = saveOrigin("O7");
        insertIngest(origin.getRawSn(), CLIP_PREFIX + "O7", "종로구 CCTV", "서울특별시 종로구",
                "Y", "N", "N", "01", "0103");

        // when
        IngestSourceRow row = sourceRepository.findSourceMeta(origin.getRawSn());

        // then
        assertThat(row.getEvntClsfCd()).isEqualTo("01");
        assertThat(row.getEvntCtgryCd()).isEqualTo("0103");
    }

    @Test
    @DisplayName("파생영상도_부모_인입의_이벤트코드를_상속받는다")
    void 파생영상도_부모_인입의_이벤트코드를_상속받는다() {
        // given: 파생영상은 자기 인입 행이 없다.
        LsDataRaw origin = saveOrigin("O8");
        insertIngest(origin.getRawSn(), CLIP_PREFIX + "O8", "용산구 CCTV", "서울특별시 용산구",
                "Y", "N", "N", "02", "0201");
        LsDataRaw derived = saveDerived(origin);

        // when
        IngestSourceRow row = sourceRepository.findSourceMeta(derived.getRawSn());

        // then: 개인정보 3필드와 달리 <이벤트 분류/카테고리는 부모 값이 파생에서도 유효>하므로
        //   ORGNL_RAW_SN 1단계 폴백을 그대로 탄다(V174 뷰 주석의 근거와 동일).
        assertThat(row.getEvntClsfCd()).isEqualTo("02");
        assertThat(row.getEvntCtgryCd()).isEqualTo("0201");
        assertThat(row.getSrcAnonyInclYn()).as("개인정보 3필드만 폴백 예외다").isNull();
    }

    @Test
    @DisplayName("관제가_이벤트코드를_안_보내면_null_로_조회된다")
    void 관제가_이벤트코드를_안_보내면_null_로_조회된다() {
        // given: dev 실측 40행 전량 NULL — 미송신이 정상 경로다(값을 지어내지 않는다).
        LsDataRaw origin = saveOrigin("O9");
        insertIngest(origin.getRawSn(), CLIP_PREFIX + "O9", "성북구 CCTV", "서울특별시 성북구",
                "Y", "N", "N", null, null);

        // when
        IngestSourceRow row = sourceRepository.findSourceMeta(origin.getRawSn());

        // then
        assertThat(row.getEvntClsfCd()).isNull();
        assertThat(row.getEvntCtgryCd()).isNull();
    }

    @Test
    @DisplayName("인입에_검증이벤트유형코드가_있으면_rawSn으로_조회된다")
    void 인입에_검증이벤트유형코드가_있으면_rawSn으로_조회된다() {
        // given: 관제가 검증이벤트유형(외부 VLM verify 의 event_type)을 실어 보냈다.
        //   이 값은 LS_DATA_RAW 에 없으므로 조회 시점 인입 조인이 유일한 조달 경로다(@req R5).
        LsDataRaw origin = saveOrigin("V1");
        insertIngest(origin.getRawSn(), CLIP_PREFIX + "V1", "관악구 CCTV", "서울특별시 관악구",
                "Y", "N", "N", "01", "0101", "car_accident");

        // when
        IngestSourceRow row = sourceRepository.findSourceMeta(origin.getRawSn());

        // then
        assertThat(row.getVrfcEvntTypeCd()).isEqualTo("car_accident");
    }

    @Test
    @DisplayName("인입_컬럼이_NULL이면_조회결과도_NULL이다")
    void 인입_컬럼이_NULL이면_조회결과도_NULL이다() {
        // given: 관제가 아직 보내지 않은 상태(마이그레이션 후 기존 행 전량이 이 상태다).
        //   백필하지 않으므로 null 이 정상이며, 상수로 지어내지 않는다.
        LsDataRaw origin = saveOrigin("V2");
        insertIngest(origin.getRawSn(), CLIP_PREFIX + "V2", "노원구 CCTV", "서울특별시 노원구",
                "Y", "N", "N", null, null, null);

        // when
        IngestSourceRow row = sourceRepository.findSourceMeta(origin.getRawSn());

        // then
        assertThat(row.getVrfcEvntTypeCd()).isNull();
    }

    @Test
    @DisplayName("파생영상도_부모_인입의_검증이벤트유형을_상속받는다 — 개인정보_3필드만_폴백_예외다")
    void 파생영상도_부모_인입의_검증이벤트유형을_상속받는다() {
        // given: 검증이벤트유형은 "분석 대상 지정"이지 개인정보 <판정>이 아니므로 파생 예외를
        //   새로 만들지 않는다(개인정보 3필드 예외의 근거가 성립하지 않는다).
        LsDataRaw origin = saveOrigin("V3");
        insertIngest(origin.getRawSn(), CLIP_PREFIX + "V3", "구로구 CCTV", "서울특별시 구로구",
                "Y", "N", "N", "01", "0101", "flooding");
        LsDataRaw derived = saveDerived(origin);

        // when
        IngestSourceRow row = sourceRepository.findSourceMeta(derived.getRawSn());

        // then — ORGNL_RAW_SN 1단계 폴백을 그대로 탄다
        assertThat(row.getVrfcEvntTypeCd()).isEqualTo("flooding");
        assertThat(row.getSrcAnonyInclYn()).as("개인정보 3필드만 폴백 예외다").isNull();
    }

    @Test
    @DisplayName("기존_인입_조회_동작은_변하지_않는다")
    void 기존_인입_조회_동작은_변하지_않는다() {
        // given: 신규 컬럼이 SELECT 절에 끼어들어도 기존 프로젝션 별칭·값이 밀리면 안 된다
        //   (같은 타입 String 컬럼이라 뒤바뀌어도 조용히 통과한다).
        LsDataRaw origin = saveOrigin("V4");
        insertIngest(origin.getRawSn(), CLIP_PREFIX + "V4", "중랑구 CCTV", "서울특별시 중랑구",
                "Y", "N", "Y", "03", "0302", "fire");

        // when
        IngestSourceRow row = sourceRepository.findSourceMeta(origin.getRawSn());

        // then — 기존 7필드가 전부 자기 값을 그대로 갖는다
        assertThat(row.getCctvNm()).isEqualTo("중랑구 CCTV");
        assertThat(row.getLclgvNm()).isEqualTo("서울특별시 중랑구");
        assertThat(row.getEvntClsfCd()).isEqualTo("03");
        assertThat(row.getEvntCtgryCd()).isEqualTo("0302");
        assertThat(row.getEvntId()).isEqualTo("ABA_0001");
        assertThat(row.getSrcAnonyInclYn()).isEqualTo("Y");
        assertThat(row.getSrcPsdoInclYn()).isEqualTo("N");
        assertThat(row.getSrcPrvcInclYn()).isEqualTo("Y");
        assertThat(row.getVrfcEvntTypeCd()).isEqualTo("fire");
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
        assertThat(row.getLclgvNm()).isNull();
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
    private void insertIngest(Long rawSn, String clipId, String cctvNm, String lclgvNm,
                              String anony, String psdo, String prvc) {
        insertIngest(rawSn, clipId, cctvNm, lclgvNm, anony, psdo, prvc, null, null);
    }

    private void insertIngest(Long rawSn, String clipId, String cctvNm, String lclgvNm,
                              String anony, String psdo, String prvc,
                              String evntClsfCd, String evntCtgryCd) {
        insertIngest(rawSn, clipId, cctvNm, lclgvNm, anony, psdo, prvc, evntClsfCd, evntCtgryCd, null);
    }

    private void insertIngest(Long rawSn, String clipId, String cctvNm, String lclgvNm,
                              String anony, String psdo, String prvc,
                              String evntClsfCd, String evntCtgryCd, String vrfcEvntTypeCd) {
        jdbc.update("""
                INSERT INTO LS_DATA_INGEST
                    (RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, SRC_TYPE,
                     RCPTN_DT, PRCS_STTS_CD, CCTV_NM, LCLGV_NM,
                     ANONY_INCL_YN, PSDO_INCL_YN, PRVC_INCL_YN, EVNT_CLSF_CD, EVNT_CTGRY_CD,
                     EVNT_ID, VRFC_EVNT_TYPE_CD)
                VALUES (?, ?, 'CCTV-SRCMETA', 'f.mp4', '/var/raw/f.mp4', 'ORIGINAL',
                        CURRENT_TIMESTAMP, 'DONE', ?, ?, ?, ?, ?, ?, ?, 'ABA_0001', ?)
                """, rawSn, clipId, cctvNm, lclgvNm, anony, psdo, prvc, evntClsfCd, evntCtgryCd,
                vrfcEvntTypeCd);
    }
}
