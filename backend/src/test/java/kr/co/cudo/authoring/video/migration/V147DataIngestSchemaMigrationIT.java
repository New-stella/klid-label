package kr.co.cudo.authoring.video.migration;

import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 — 관제 인입 스키마(V147 {@code LS_DATA_INGEST} 신설 / V148 {@code LS_DATA_RAW} 출처·증강종류
 * 컬럼 추가) 실동작 검증 (Testcontainers PostgreSQL, Flyway migrate + {@code ddl-auto=validate} 부팅).
 *
 * <p>검증 축:
 * <ul>
 *   <li>Flyway 전체 마이그레이션이 V148 까지 성공하고 컨텍스트가 뜬다(= 기존 엔티티 매핑 무파손).</li>
 *   <li>{@code information_schema} 기준 44컬럼 전량의 물리명·타입·길이(정밀도/스케일)·NULL 허용이
 *       설계 {@code .cc-design.md} §4-1 확정값과 1:1 일치한다.
 *       (V147 시점 37컬럼 + V166 관제 수신 4 + V168 관제 수신 2 + V176 관제 수신 1
 *        − V185 관제 수신 1 제거(OG_CD) + V16 관제 수신 2(THMB·OG_CD 복원)
 *        − V17 관제 수신 1 제거(THMB) = 44)</li>
 *   <li>제약 2종(PK·UK)과 PENDING 부분 인덱스가 실재하고, UK 가 중복 INSERT 를 실제로 거부한다.</li>
 *   <li>관제가 저작도구 운영 컬럼을 생략해도 적재된다(DEFAULT 판단 검증).</li>
 *   <li>{@code BIT} 은 PostgreSQL 에서 무인용으로 읽고 쓸 수 있다(예약어 우려 실증).</li>
 *   <li>{@code LS_DATA_RAW} 신규 2컬럼은 nullable + DEFAULT 없음 → 기존 행은 NULL 로 보존된다.</li>
 * </ul>
 *
 * <p>이 Phase 는 <b>순수 스키마</b>다. 인입 엔티티({@code LsDataIngest})는 Phase 2 담당이므로
 * 여기서는 JDBC 로만 검증한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class V147DataIngestSchemaMigrationIT {

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager controlTxManager;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    /**
     * 설계 §4-1 확정값(정보 스키마는 소문자 물리명을 돌려준다).
     *
     * @param charLen    문자형 최대 길이(비문자형 null)
     * @param precision  수치형 정밀도(비수치형 null)
     * @param scale      수치형 스케일(비수치형 null)
     */
    private record Col(String name, String dataType, Integer charLen, Integer precision, Integer scale,
                       boolean nullable) {

        static Col varchar(String name, int len, boolean nullable) {
            return new Col(name, "character varying", len, null, null, nullable);
        }

        static Col bigint(String name, boolean nullable) {
            return new Col(name, "bigint", null, 64, 0, nullable);
        }

        static Col integer(String name, boolean nullable) {
            return new Col(name, "integer", null, 32, 0, nullable);
        }

        static Col numeric(String name, int precision, int scale, boolean nullable) {
            return new Col(name, "numeric", null, precision, scale, nullable);
        }

        static Col timestamp(String name, boolean nullable) {
            return new Col(name, "timestamp without time zone", null, null, null, nullable);
        }

        /** 여부 컬럼(CHAR(1)) — information_schema 는 {@code character} 로 돌려준다. */
        static Col character(String name, int len, boolean nullable) {
            return new Col(name, "character", len, null, null, nullable);
        }
    }

    /**
     * LS_DATA_INGEST 44컬럼 = 저작도구 운영 8 + 관제 수신 36.
     *
     * <p>숫자의 출처: V147 신설 시점 37컬럼(운영 8 + 수신 29) + V166 에서 관제 수신 4컬럼
     * (EVNT_TYPE_CD · ANONY_INCL_YN · PSDO_INCL_YN · PRVC_INCL_YN) 추가 = 41
     * + V168 에서 관제 수신 2컬럼(EVNT_CLSF_CD·EVNT_CTGRY_CD) 추가 = 43
     * + V176 에서 관제 수신 1컬럼(VRFC_EVNT_TYPE_CD) 추가 = 44
     * − V185 에서 관제 수신 1컬럼(OG_CD) 제거 = 43
     * + V16 에서 관제 수신 2컬럼(THMB_FILE_PATH_NM 신설·OG_CD 복원) = 45
     * <b>− V17 에서 관제 수신 1컬럼(THMB_FILE_PATH_NM) 제거 = 44</b>.
     * 스키마를 바꾸면 이 목록도 같은 커밋에서 갱신한다.
     *
     * <p>V17 이 THMB 를 지운 이유: 대표 이미지 조달원이 관제 인입값 pass-through 에서
     * 저작도구 비식별 첫 프레임({@code LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM})으로 바뀌어
     * 이 컬럼의 소비자가 0 이 됐다. 뷰 출력명 {@code THMB_FILE_PATH_NM} 은 그대로다.
     */
    private static final List<Col> INGEST_COLUMNS = List.of(
            // ---- 저작도구 운영 (8) ----
            Col.bigint("rcptn_sn", false),
            Col.timestamp("rcptn_dt", false),
            Col.varchar("prcs_stts_cd", 20, false),
            Col.bigint("raw_sn", true),
            Col.integer("rty_cnt", false),
            Col.timestamp("prcs_dt", true),
            // 차기재시도일시(NXTM_RTRY_DT, 연월일시분초D) — 미도착 backoff 축(설계 §6-0-1-a ㉢).
            // V172 개명 — 구 NEXT_RTRY_DT('NEXT' 미등록 표준단어). 차기=NXTM · 재시도=RTY.
            Col.timestamp("nxtm_rtry_dt", true),
            Col.varchar("err_msg", 4000, true),
            // ---- 관제 수신 (V147 29 − V185 OG_CD 제거 = 28) ----
            Col.varchar("vms_clip_id", 128, false),
            // V185 — NOT NULL 해제. 관제 회신(2026-08-12) "CCTV 식별자가 없는 영상(수동 업로드 등)이
            //   존재". LS_DATA_RAW 쪽도 함께 풀었다(한쪽만 풀면 적재가 제약 위반으로 터진다).
            Col.varchar("vms_cctv_id", 64, true),
            Col.varchar("vdo_file_nm", 300, false),
            Col.varchar("raw_file_path_nm", 500, false),
            Col.varchar("src_type", 20, false),
            Col.timestamp("sht_dt", true),
            Col.varchar("file_fmt", 20, true),
            Col.varchar("vdo_cdc", 20, true),
            // 파일크기는 관제 수신 바이트 수(수B20 = BIGINT). '4800KB' 표기는 export 직렬화 산물이라
            // 인입을 문자열로 받지 않는다(등록 물리명을 쓰면서 타입만 이탈하면 표준 위반).
            Col.bigint("file_sz", true),
            // V172 — 구 RGN_NM(명V200) → 표준용어 LCLGV_NM · 표준도메인 명V100.
            Col.varchar("lclgv_nm", 100, true),
            Col.numeric("vdo_len_sec", 10, 0, true),
            Col.varchar("fps", 10, true),
            Col.numeric("frme_cnt", 10, 0, true),
            Col.varchar("asprt_rt", 20, true),
            Col.numeric("wdth", 10, 0, true),
            Col.numeric("vrtc", 10, 0, true),
            Col.varchar("resl", 20, true),
            Col.varchar("bit", 20, true),
            Col.varchar("pxl", 20, true),
            Col.numeric("wgs84_lat", 10, 7, true),
            Col.numeric("wgs84_lot", 10, 7, true),
            Col.varchar("cctv_nm", 300, true),
            Col.numeric("cctv_hgt", 4, 1, true),
            Col.integer("main_surv_pan_ang", true),
            Col.varchar("evnt_id", 50, true),
            Col.varchar("evnt_nm", 200, true),
            // V168 — 이벤트분류코드(대분류). 관제 송신 대상이며 코드에서 유도하지 않는다.
            //   V172 로 표준도메인 코드C2(CHAR(2))에 정합시켰다(실제 값도 2자 고정).
            Col.character("evnt_clsf_cd", 2, true),
            // V168 — 이벤트카테고리코드(3계층 중간 레벨). 표시명 폴백의 근거. V172 로 코드C4(CHAR(4)).
            Col.character("evnt_ctgry_cd", 4, true),
            Col.varchar("mntr_cn", 4000, true),
            // 지방자치단체코드 — LS_DATA_RAW.LCLGV_CD 의 원천. 없으면 관제 완료통지 페이로드의
            // lclgv_cd(required)가 빈다. LCLGV_NM(지방자치단체명)과 다른 값이다.
            Col.varchar("lclgv_cd", 20, true),
            // ---- 관제 수신 (V166 추가 4) ----
            // 이벤트유형코드 직접 수신 통로(코드V20). MNG_CLIP_EVNT_LST 조인 해석의 대체 경로이며
            // EVNT_ID(식별자)와 다른 값이다. 관제 미채움 시 null 이라 nullable + DEFAULT 없음.
            Col.varchar("evnt_type_cd", 20, true),
            // 원천 영상(비식별 처리 <전>)의 개인정보 3필드 — LS_DATA_RAW 동명 컬럼(V163, 비식별 축
            // 수동 판정)과 <다른 축>이다. 타입은 사내 선례(V85/V163)를 따라 CHAR(1).
            // ⚠ 구 서술 "서버 보정·DB DEFAULT 없이 null 을 보존한다"는 <폐기>됐다 — V170 이 fail-closed
            //   DEFAULT('N'/'N'/'Y')를 부여한다(2026-08-04 사용자 확정). 이 목록은 타입·크기만 대조하므로
            //   DEFAULT 검증은 IngestReceiveColumnsMigrationIT 가 담당한다.
            Col.character("anony_incl_yn", 1, true),
            Col.character("psdo_incl_yn", 1, true),
            Col.character("prvc_incl_yn", 1, true),
            // ---- 관제 수신 (V176 추가 1) ----
            // 검증이벤트유형코드(코드V20) — 외부 VLM verify 요청의 event_type 조달처. 저작도구가
            // 매핑표로 만들지 않고 관제 인입으로 수신한다. 미송신이면 null(백필 없음).
            Col.varchar("vrfc_evnt_type_cd", 20, true),
            // ---- 관제 수신 (V16 추가 2 − V17 제거 1 = 1) ----
            // V16 이 신설한 THMB_FILE_PATH_NM 은 V17 에서 제거됐다 — 대표 이미지는 관제 인입값이
            //   아니라 저작도구 비식별 첫 프레임에서 조달한다. 인입 축에는 더 이상 없다.
            // 기관코드(코드V20) — 관제 "실보유" 재확인으로 재추가(구 V185 제거분 복원). LCLGV_CD·NM 과 다른 값.
            Col.varchar("og_cd", 20, true));

    @Test
    @DisplayName("마이그레이션_적용후_LS_DATA_INGEST_테이블이_존재한다")
    void 마이그레이션_적용후_LS_DATA_INGEST_테이블이_존재한다() {
        // given / when — Flyway 가 V148 까지 적용된 상태로 컨텍스트가 로드됨
        Integer tableCount = jdbc().queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'ls_data_ingest'",
                Integer.class);

        // then — 인입 테이블이 실재한다
        assertThat(tableCount).isEqualTo(1);

        // then — 제약 2종(PK·UK)이 설계 확정 이름으로 실재한다
        List<Map<String, Object>> constraints = jdbc().queryForList(
                "SELECT conname, contype FROM pg_constraint WHERE conrelid = 'ls_data_ingest'::regclass");
        assertThat(constraints)
                .as("PK_LS_DATA_INGEST(PK) · UK_LS_DATA_INGEST_CLIP(UK)")
                .extracting(row -> row.get("conname") + ":" + row.get("contype"))
                .contains("pk_ls_data_ingest:p", "uk_ls_data_ingest_clip:u");
    }

    @Test
    // ★ 테스트명에 컬럼 개수를 박지 않는다 — 컬럼이 추가될 때마다 rename 이 강제돼 같은 마찰이
    //   반복된다. 개수 단언은 아래 코드에 그대로 남으므로 가드는 약화되지 않는다.
    @DisplayName("LS_DATA_INGEST_컬럼_전량의_타입과_길이가_설계와_일치한다")
    void LS_DATA_INGEST_컬럼_전량의_타입과_길이가_설계와_일치한다() {
        // given — 설계 §4-1 확정값
        //   (V147 37 + V166 4 + V168 2 + V176 1 − V185 1(OG_CD) + V16 2(THMB·OG_CD 복원)
        //    − V17 1(THMB) = 44건)
        assertThat(INGEST_COLUMNS).as("설계 §4-1 총 컬럼 수").hasSize(44);

        // when — 실제 스키마 컬럼 수
        Integer actualCount = jdbc().queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'ls_data_ingest'",
                Integer.class);

        // then — 설계 외 컬럼이 끼어들지 않았다(44 정확히 일치)
        assertThat(actualCount).as("LS_DATA_INGEST 컬럼 수").isEqualTo(44);

        // then — 컬럼별 물리명/타입/길이/정밀도/스케일/NULL 허용이 1:1 일치
        for (Col col : INGEST_COLUMNS) {
            Map<String, Object> meta = jdbc().queryForMap(
                    "SELECT data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable "
                            + "FROM information_schema.columns WHERE table_name = 'ls_data_ingest' AND column_name = ?",
                    col.name());

            assertThat(meta.get("data_type")).as("%s 타입", col.name()).isEqualTo(col.dataType());
            assertThat(intOrNull(meta.get("character_maximum_length"))).as("%s 길이", col.name())
                    .isEqualTo(col.charLen());
            assertThat(intOrNull(meta.get("numeric_precision"))).as("%s 정밀도", col.name())
                    .isEqualTo(col.precision());
            assertThat(intOrNull(meta.get("numeric_scale"))).as("%s 스케일", col.name()).isEqualTo(col.scale());
            assertThat(meta.get("is_nullable")).as("%s NULL 허용", col.name())
                    .isEqualTo(col.nullable() ? "YES" : "NO");
        }
    }

    @Test
    @DisplayName("VMS_CLIP_ID_유니크제약이_중복INSERT를_거부한다")
    void VMS_CLIP_ID_유니크제약이_중복INSERT를_거부한다() {
        // given — 같은 클립 ID 로 두 번 INSERT.
        //   PostgreSQL 은 제약 위반 시 트랜잭션 전체를 abort 하므로, 바깥 테스트 트랜잭션을 오염시키지
        //   않도록 REQUIRES_NEW 로 격리한다(위반 후 내부 트랜잭션만 롤백된다).
        TransactionTemplate isolated = new TransactionTemplate(controlTxManager);
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // when / then — 두 번째 INSERT 가 UK 로 거부된다(관제 재송신 방어)
        assertThatThrownBy(() -> isolated.execute(status -> {
            insertMinimalIngest("DUP-CLIP-001");
            insertMinimalIngest("DUP-CLIP-001");
            return null;
        }))
                .as("UK_LS_DATA_INGEST_CLIP 위반")
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("PENDING_부분인덱스가_생성되어_있다")
    void PENDING_부분인덱스가_생성되어_있다() {
        // given / when — 폴링 전용 부분 인덱스 정의를 조회
        List<String> defs = jdbc().queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'ls_data_ingest' "
                        + "AND indexname = 'ix_ls_data_ingest_poll'",
                String.class);

        // then — 실재하며 <폴링 술어와 일치>한다(설계 §6-0-1-a ㉢).
        //   실제 술어: PRCS_STTS_CD='PENDING' AND (NXTM_RTRY_DT IS NULL OR NXTM_RTRY_DT <= now)
        //             ORDER BY RCPTN_DT, RCPTN_SN
        //   · 정렬 축(rcptn_dt, rcptn_sn)이 인덱스 키다.
        //   · nxtm_rtry_dt 는 INCLUDE 로 실린다 — now 가 immutable 이 아니라 부분 인덱스 <술어>에는
        //     넣을 수 없고, 실어두면 고착 행을 힙 방문 없이 인덱스에서 걸러낸다.
        //   · 상태는 부분 인덱스 술어(전체 인덱스가 아니다 — 완료분이 영구 누적돼도 크기가 미처리에 비례).
        assertThat(defs).as("IX_LS_DATA_INGEST_POLL").hasSize(1);
        assertThat(defs.get(0))
                .contains("rcptn_dt")
                .contains("rcptn_sn")
                .contains("nxtm_rtry_dt")
                .contains("WHERE")
                .contains("prcs_stts_cd")
                .contains("'PENDING'");
    }

    @Test
    @DisplayName("관제가_저작도구_운영컬럼을_생략해도_PENDING_0으로_적재된다")
    void 관제가_저작도구_운영컬럼을_생략해도_PENDING_0으로_적재된다() {
        // given — 관제는 우리 처리 상태를 모른다. 필수 수신 5컬럼만 넣는다.
        insertMinimalIngest("DEFAULT-CLIP-001");

        // when
        Map<String, Object> row = jdbc().queryForMap(
                "SELECT rcptn_sn, rcptn_dt, prcs_stts_cd, rty_cnt, raw_sn, prcs_dt "
                        + "FROM ls_data_ingest WHERE vms_clip_id = 'DEFAULT-CLIP-001'");

        // then — 운영 컬럼은 DEFAULT 로 채워져 즉시 폴링 대상이 된다
        assertThat(row.get("rcptn_sn")).as("IDENTITY 자동 발급").isNotNull();
        assertThat(row.get("rcptn_dt")).as("수신일시 DEFAULT").isNotNull();
        assertThat(row.get("prcs_stts_cd")).isEqualTo("PENDING");
        assertThat(((Number) row.get("rty_cnt")).intValue()).isZero();
        // then — 적재 결과 컬럼은 미처리 상태이므로 비어 있다
        assertThat(row.get("raw_sn")).isNull();
        assertThat(row.get("prcs_dt")).isNull();
    }

    @Test
    @DisplayName("BIT_컬럼은_인용없이_읽고_쓸_수_있다")
    void BIT_컬럼은_인용없이_읽고_쓸_수_있다() {
        // given — BIT 은 PostgreSQL 예약어 우려가 있던 컬럼명(비트레이트 bps 정수를 담는다, V16 정정)
        insertMinimalIngest("BIT-CLIP-001");

        // when — 무인용 UPDATE / SELECT
        jdbc().update("UPDATE ls_data_ingest SET bit = ?, pxl = ?, resl = ? WHERE vms_clip_id = ?",
                "2050627", "4K", "FHD", "BIT-CLIP-001");
        Map<String, Object> row = jdbc().queryForMap(
                "SELECT bit, pxl, resl FROM ls_data_ingest WHERE vms_clip_id = 'BIT-CLIP-001'");

        // then — 큰따옴표 인용 없이 왕복된다(인용하면 대문자 식별자가 고정돼 규칙이 갈린다)
        assertThat(row.get("bit")).isEqualTo("2050627");
        assertThat(row.get("pxl")).isEqualTo("4K");
        assertThat(row.get("resl")).isEqualTo("FHD");
    }

    @Test
    @DisplayName("LS_DATA_RAW에_SRC_TYPE과_AUG_TYPE_CD가_추가되고_기존행은_NULL이다")
    void LS_DATA_RAW에_SRC_TYPE과_AUG_TYPE_CD가_추가되고_기존행은_NULL이다() {
        // given — 신규 2컬럼은 nullable + DEFAULT 없음(기존 행 보존 + NOT NULL 즉시부여 실패 회피)
        for (String column : List.of("src_type", "aug_type_cd")) {
            Map<String, Object> meta = jdbc().queryForMap(
                    "SELECT data_type, character_maximum_length, is_nullable, column_default "
                            + "FROM information_schema.columns WHERE table_name = 'ls_data_raw' AND column_name = ?",
                    column);
            assertThat(meta.get("data_type")).as("%s 타입", column).isEqualTo("character varying");
            assertThat(((Number) meta.get("character_maximum_length")).intValue()).as("%s 길이", column)
                    .isEqualTo(20);
            assertThat(meta.get("is_nullable")).as("%s NULL 허용", column).isEqualTo("YES");
            assertThat(meta.get("column_default"))
                    .as("%s DEFAULT 없음 — 백필은 파서 제거와 짝을 이루는 별도 범위", column)
                    .isNull();
        }

        // when — 신규 컬럼을 지정하지 않고 영상 행을 적재(= 마이그레이션 이전 기존 행과 동일한 형상)
        long rawSn = RawVideoFixture.newRaw(jdbc());

        // then — 기존 행은 손실 없이 보존되고 신규 2컬럼은 NULL 이다
        Map<String, Object> raw = jdbc().queryForMap(
                "SELECT vms_clip_id, src_type, aug_type_cd FROM ls_data_raw WHERE raw_sn = ?", rawSn);
        assertThat(raw.get("vms_clip_id")).as("기존 컬럼 보존").isNotNull();
        assertThat(raw.get("src_type")).isNull();
        assertThat(raw.get("aug_type_cd")).isNull();

        // then — 설계 §4-2 / §4-2-1 값 체계가 그대로 저장된다(길이 20 수용)
        jdbc().update("UPDATE ls_data_raw SET src_type = ?, aug_type_cd = ? WHERE raw_sn = ?",
                "AUGMENTED", "RESL_1080P", rawSn);
        Map<String, Object> updated = jdbc().queryForMap(
                "SELECT src_type, aug_type_cd FROM ls_data_raw WHERE raw_sn = ?", rawSn);
        assertThat(updated.get("src_type")).isEqualTo("AUGMENTED");
        assertThat(updated.get("aug_type_cd")).isEqualTo("RESL_1080P");
    }

    /** 관제가 넣는 최소 형상 — NOT NULL 수신 5컬럼만 지정하고 운영 컬럼은 DEFAULT 에 맡긴다. */
    private void insertMinimalIngest(String vmsClipId) {
        jdbc().update("""
                INSERT INTO ls_data_ingest
                    (vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, src_type)
                VALUES (?, 'CCTV-001', 'clip.mp4', '/nas-storage/raw/clip.mp4', 'RELAY')
                """, vmsClipId);
    }

    private static Integer intOrNull(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }
}
