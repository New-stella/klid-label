package kr.co.cudo.authoring.video.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V149 — {@code LS_DATA_RAW.SRC_TYPE}·{@code AUG_TYPE_CD} 백필이 <b>정규화 규칙대로</b> 동작하는지
 * 실 DB(Testcontainers PostgreSQL)에서 검증한다 (Phase 4 도입 · Phase 5 개정).
 *
 * <h2>두 컬럼의 판정축이 다르다 (Phase 6 개정 — 설계 §4-2-1 ★정정 2026-08-01)</h2>
 * <ul>
 *   <li>{@code SRC_TYPE} — <b>{@code ORGNL_RAW_SN} 이 진실원</b>이다(파생의 정의 자체). clipId 파싱으로
 *       정하면 파싱 불가한 파생행이 {@code ORIGINAL} 로, 이름에 마커가 우연히 섞인 원본이
 *       {@code AUGMENTED} 로 적재된다.</li>
 *   <li>{@code AUG_TYPE_CD} — 종류는 {@code ORGNL_RAW_SN} 으로 알 수 없으므로 마커 파싱을 유지한다.</li>
 * </ul>
 * <p>따라서 아래 케이스 대조는 <b>{@code AUG_TYPE_CD} 축만</b> 리터럴로 고정하고, {@code SRC_TYPE} 은
 * 시드 행의 {@code ORGNL_RAW_SN} 유무로 기대한다({@code seedRaw} 는 원본, {@code seedDerivativeRaw} 는 파생).
 *
 * <h2>기대값이 리터럴인 이유 (Phase 5 개정)</h2>
 * 도입 당시 기대값은 {@code video/util/AugTypeParser.parse()} <b>호출</b>로 계산했다(백필 SQL 이 파서
 * 규칙의 SQL 이식본이므로 파서를 오라클로 삼는 것이 자기충족을 피하는 방법이었다). Phase 5 에서 파서를
 * <b>삭제</b>했으므로 그 오라클은 더 이상 존재하지 않는다 — 그래서 기대값을 <b>명시 리터럴로 동결</b>한다.
 * 리터럴은 삭제 <b>이전에</b> 파서 규칙(마커 위치 기반·가장 오른쪽 마커·마커 뒤 세그먼트 판별·폴백 금지·
 * 해상도 코드 첫 일치)으로 산출해 확인한 값이며, 케이스별 근거는 각 항목 주석에 적어 둔다.
 *
 * <h2>공허 PASS 회피</h2>
 * Flyway 는 {@code @SpringBootTest} 컨텍스트 기동 시 V149 까지 이미 적용하므로 "기동 후 SELECT 만" 하면
 * 비교 대상이 0건이라 무조건 통과한다. 그래서 이 테스트는 케이스 행을 <b>직접 시드</b>하고(두 컬럼은
 * 비운 채 {@code VMS_CLIP_ID} 원문만), <b>V149 파일에서 읽은 실제 SQL</b>({@link #runBackfill()})을
 * 실행시켜 채운 뒤, 대조 대상 행수와 <b>마커 보유 행수가 0이 아님</b>을 스스로 단언한다.
 *
 * <p>마이그레이션 SQL 을 파일에서 읽어 재실행하는 방식은 같은 레포의
 * {@code ResolutionTablesDropAndBackfillIT}(V126) 가 이미 쓰는 정본 해법이다 — 테스트가 SQL 사본을
 *들고 있지 않으므로 드리프트가 발생하지 않는다.
 *
 * <p>격리: 클래스 레벨 {@code @Transactional} 롤백 트랜잭션 안에서 시드·백필을 수행한다. 백필 UPDATE 는
 * 테이블 전체를 대상으로 하므로(마이그레이션 원본 그대로) 커밋하면 공유 컨테이너의 다른 테스트 데이터까지
 * 바꾼다 — 롤백이 그 오염을 막는다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class V149BackfillParityIT {

    /** 백필 마이그레이션 파일(클래스패스) — 사본을 들지 않고 이 파일을 읽어 그대로 실행한다. */
    private static final String MIGRATION_PATH = "db/migration/V149__backfill_src_type_aug_type.sql";

    /** 시드 행 식별 마커 — {@code VMS_CLIP_ID} 는 케이스마다 형태가 달라 CCTV_ID 로 태깅한다. */
    private static final String SEED_CCTV_ID = "V149-SEED";

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    // ------------------------------------------------------------------ 핵심 대조

    @Test
    @DisplayName("백필후_전행이_판정되고_규칙_전영역_케이스가_기대값과_일치한다")
    void 백필후_전행이_판정되고_규칙_전영역_케이스가_기대값과_일치한다() {
        // given — 정규화 규칙 전 영역을 덮는 클립 ID 를 원문 그대로 시드(두 컬럼은 비운다)
        Map<String, String> cases = parityCases();
        cases.keySet().forEach(this::seedRaw);
        // and — 마이그레이션 직전 형상 재현: 이미 채워진 행(신규 쓰기 경로 산출분 등)도 전부 비워
        //       "전 행"이 백필 대상이 되게 한다(롤백 트랜잭션 안이라 공유 컨테이너는 오염되지 않는다).
        jdbc().update("UPDATE LS_DATA_RAW SET SRC_TYPE = NULL, AUG_TYPE_CD = NULL");

        // when — V149 파일의 실제 SQL 실행
        runBackfill();

        // then — 전 행 조회 (샘플링 아님)
        List<Map<String, Object>> rows = jdbc().queryForList(
                "SELECT RAW_SN, VMS_CLIP_ID, ORGNL_RAW_SN, SRC_TYPE, AUG_TYPE_CD FROM LS_DATA_RAW");

        // then #0 — 공허 PASS 방지: 대조 대상 자체가 존재해야 하고, 그 중 마커 보유 케이스도 있어야 한다
        assertThat(rows).as("대조 대상 행이 0건이면 이 테스트는 아무것도 검증하지 못한다").isNotEmpty();
        assertThat(cases.values()).as("시드 자체에 마커 보유 케이스가 없으면 대조가 성립하지 않는다")
                .anyMatch(Objects::nonNull);

        // then #1 — 전 행 불변식: 백필은 판정을 <남기지 않는다>. SRC_TYPE 이 비어 있는 행이 있으면 안 되고,
        //           그 값은 <ORGNL_RAW_SN 축>과 정확히 일치해야 한다(AUG_TYPE_CD 축과 교차 검증하지 않는다 —
        //           두 컬럼은 서로 다른 축이라 'ORIGINAL' + 종류 비어있지 않음 조합이 정상적으로 존재한다).
        for (Map<String, Object> row : rows) {
            String clip = (String) row.get("VMS_CLIP_ID");
            Object srcType = row.get("SRC_TYPE");
            boolean derivative = row.get("ORGNL_RAW_SN") != null;
            assertThat(srcType).as("백필 후에도 SRC_TYPE 이 비어 있다 clip=%s", clip).isNotNull();
            assertThat(srcType).as("SRC_TYPE 이 ORGNL_RAW_SN 축과 어긋난다 clip=%s", clip)
                    .isEqualTo(derivative ? "AUGMENTED" : "ORIGINAL");
        }

        // then #2 — 규칙 전 영역 케이스는 <기대 리터럴>과 정확히 일치한다
        Map<String, Map<String, Object>> byClip = new HashMap<>();
        for (Map<String, Object> row : rows) {
            byClip.put((String) row.get("VMS_CLIP_ID"), row);
        }
        for (Map.Entry<String, String> c : cases.entrySet()) {
            Map<String, Object> row = byClip.get(c.getKey());
            assertThat(row).as("시드한 케이스 행이 조회되지 않았다 clip=%s", c.getKey()).isNotNull();
            assertThat(row.get("AUG_TYPE_CD")).as("AUG_TYPE_CD clip=%s", c.getKey()).isEqualTo(c.getValue());
            // 케이스 행은 전부 원본 시드(ORGNL_RAW_SN null)다 — 마커가 있어도 SRC_TYPE 은 ORIGINAL 이다.
            assertThat(row.get("SRC_TYPE")).as("SRC_TYPE clip=%s", c.getKey()).isEqualTo("ORIGINAL");
        }
    }

    // ------------------------------------------------------------- 개별 정규화 규칙

    @Test
    @DisplayName("이중접두_RESL_RESL_480P_행이_RESL_480P로_백필된다")
    void 이중접두_RESL_RESL_480P_행이_RESL_480P로_백필된다() {
        // given — 실데이터 드리프트: createFromResolution 이 만드는 형태 그대로
        String clip = seedRaw("V149-dup-1784778573069_RESL_RESL_480P_1784779138682");

        // when
        runBackfill();

        // then
        assertBackfilled(clip, "RESL_480P");
    }

    @Test
    @DisplayName("구형접두_RES_RES_480P_행이_RESL_480P로_백필된다")
    void 구형접두_RES_RES_480P_행이_RESL_480P로_백필된다() {
        // given — 구형 마커 드리프트(_RES_)
        String clip = seedRaw("V149-legacy-1784680607679_RES_RES_480P_1784683613093");

        // when
        runBackfill();

        // then — RESL_ 접두로 정규화
        assertBackfilled(clip, "RESL_480P");
    }

    @Test
    @DisplayName("원본명에_winter가_섞여도_마커가_없으면_ORIGINAL로_백필된다")
    void 원본명에_winter가_섞여도_마커가_없으면_ORIGINAL로_백필된다() {
        // given — 마커 없이 우연히 종류 토큰이 섞인 순수 원본명(전체 스캔 폴백 금지)
        String clip = seedRaw("V149-winter480p-plain-clip");

        // when
        runBackfill();

        // then
        assertBackfilled(clip, null);
    }

    @Test
    @DisplayName("마커가_여러번이면_가장_오른쪽_마커가_채택된다")
    void 마커가_여러번이면_가장_오른쪽_마커가_채택된다() {
        // given — 같은 마커 반복 / 다른 마커 혼합 (접미 timestamp 직전 종류가 진실)
        String repeated = seedRaw("V149-multi_AUG_WINTER_x_AUG_RAIN_1784779138682");
        String mixed = seedRaw("V149-mixed_RESL_1080P_x_AUG_NIGHT_1784779138682");
        String reslWins = seedRaw("V149-mixed2_AUG_WINTER_x_RESL_720P_1784779138682");

        // when
        runBackfill();

        // then
        assertBackfilled(repeated, "RAIN");
        assertBackfilled(mixed, "NIGHT");
        assertBackfilled(reslWins, "RESL_720P");
    }

    @Test
    @DisplayName("소문자_혼용_마커도_대문자와_동일하게_백필된다")
    void 소문자_혼용_마커도_대문자와_동일하게_백필된다() {
        // given — 파서는 Locale.ROOT 대문자화 후 매칭한다. SQL 도 동일해야 한다.
        String lowerAug = seedRaw("V149-lower_aug_winter_1784779138682");
        String lowerResl = seedRaw("V149-lower_resl_resl_480p_1784779138682");
        String mixedCase = seedRaw("V149-mixed_Aug_NiGhT_1784779138682");

        // when
        runBackfill();

        // then
        assertBackfilled(lowerAug, "WINTER");
        assertBackfilled(lowerResl, "RESL_480P");
        assertBackfilled(mixedCase, "NIGHT");
    }

    @Test
    @DisplayName("마커는_있는데_뒤_세그먼트에_토큰이_없으면_ORIGINAL이다")
    void 마커는_있는데_뒤_세그먼트에_토큰이_없으면_ORIGINAL이다() {
        // given — ① 마커만 있고 종류 토큰이 없는 경우
        String augNoToken = seedRaw("V149-nofallback_AUG_FOG_1784779138682");
        // and — ② 오른쪽 _AUG_ 가 이겼지만 토큰이 없다. 파서는 왼쪽 _RESL_ 로 <폴백하지 않고> 즉시 null.
        String augBeatsResl = seedRaw("V149-nofallback2_RESL_720P_AUG_FOG_1784779138682");
        // and — ③ 해상도 마커 뒤에 코드가 없는 경우
        String reslNoCode = seedRaw("V149-nofallback3_RESL_2160P_1784779138682");

        // when
        runBackfill();

        // then — 셋 다 미매칭 → ORIGINAL + AUG_TYPE_CD NULL
        assertBackfilled(augNoToken, null);
        assertBackfilled(augBeatsResl, null);
        assertBackfilled(reslNoCode, null);
    }

    @Test
    @DisplayName("세그먼트에_해상도코드가_여러개면_첫_일치가_채택된다")
    void 세그먼트에_해상도코드가_여러개면_첫_일치가_채택된다() {
        // given — 마커 뒤 세그먼트에 코드가 2개(파서는 정규식 첫 일치=왼쪽부터 채택)
        String first720 = seedRaw("V149-two_RESL_720P_480P_1784779138682");
        String first480 = seedRaw("V149-two2_RESL_480P_1080P_1784779138682");

        // when
        runBackfill();

        // then — "긴 코드 우선"도 "오른쪽 우선"도 아니다
        assertBackfilled(first720, "RESL_720P");
        assertBackfilled(first480, "RESL_480P");
    }

    // -------------------------------------------------- SRC_TYPE 판정축 (Phase 6 정정)

    @Test
    @DisplayName("마커_파싱이_불가한_파생행도_AUGMENTED로_백필된다")
    void 마커_파싱이_불가한_파생행도_AUGMENTED로_백필된다() {
        // given — 부모가 있는 <실제 파생>이지만 clipId 마커가 파싱되지 않는다(레거시 _AUG_RESOLUTION_).
        //         구 판정축(clipId 파싱)에서는 이 행이 'ORIGINAL' 로 적재돼 파생이 원본으로 뒤집혔다.
        String clip = seedDerivativeRaw("V149-legacyderiv_AUG_RESOLUTION_1784779138682");

        // when
        runBackfill();

        // then — 출처유형은 ORGNL_RAW_SN 축으로 AUGMENTED, 종류는 파싱 불가라 NULL(두 축이 독립)
        assertBackfilled(clip, null, "AUGMENTED");
    }

    @Test
    @DisplayName("이름에_마커가_섞인_원본은_ORIGINAL로_백필된다")
    void 이름에_마커가_섞인_원본은_ORIGINAL로_백필된다() {
        // given — 부모가 없는 <실제 원본>인데 파일명에 증강 마커가 우연히 섞였다.
        //         구 판정축에서는 이 행이 'AUGMENTED' 로 적재돼 원본이 파생으로 뒤집혔다.
        String clip = seedRaw("V149-falsepositive_AUG_WINTER_1784779138682");

        // when
        runBackfill();

        // then — 출처유형은 ORIGINAL. 종류(AUG_TYPE_CD)는 마커 파싱 축이라 WINTER 로 남으며,
        //        두 컬럼은 서로 다른 축이므로 이 조합은 모순이 아니다.
        assertBackfilled(clip, "WINTER", "ORIGINAL");
    }

    // ------------------------------------------------------------------ 데이터 보존

    @Test
    @DisplayName("백필이_기존_컬럼값을_훼손하지_않는다")
    void 백필이_기존_컬럼값을_훼손하지_않는다() {
        // given — 백필 대상 1행 + 이미 출처유형이 채워진 1행(신규 쓰기 경로/인입 산출분)
        String target = seedRaw("V149-keep_AUG_RAIN_1784779138682");
        String prefilled = seedRaw("V149-prefilled_AUG_WINTER_1784779138682");
        jdbc().update("UPDATE LS_DATA_RAW SET SRC_TYPE = 'RELAY' WHERE VMS_CLIP_ID = ?", prefilled);

        Integer before = jdbc().queryForObject("SELECT COUNT(*) FROM LS_DATA_RAW", Integer.class);
        Map<String, Object> snapshot = fullRow(target);

        // when
        runBackfill();

        // then — 행 수 불변
        Integer after = jdbc().queryForObject("SELECT COUNT(*) FROM LS_DATA_RAW", Integer.class);
        assertThat(after).isEqualTo(before);

        // then — 백필 대상 행에서 두 컬럼 외 모든 컬럼이 불변(원문 VMS_CLIP_ID 포함)
        Map<String, Object> updated = fullRow(target);
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
            String column = entry.getKey();
            if ("SRC_TYPE".equalsIgnoreCase(column) || "AUG_TYPE_CD".equalsIgnoreCase(column)) {
                continue;
            }
            assertThat(updated.get(column)).as("백필이 %s 컬럼을 건드렸다", column).isEqualTo(entry.getValue());
        }
        assertBackfilled(target, "RAIN");

        // then — 이미 채워진 행은 백필 대상이 아니다(신규 쓰기 경로 값 훼손 금지)
        Map<String, Object> kept = jdbc().queryForMap(
                "SELECT SRC_TYPE, AUG_TYPE_CD FROM LS_DATA_RAW WHERE VMS_CLIP_ID = ?", prefilled);
        assertThat(kept.get("SRC_TYPE")).isEqualTo("RELAY");
        assertThat(kept.get("AUG_TYPE_CD")).isNull();
    }

    @Test
    @DisplayName("백필은_재실행해도_결과가_동일하다")
    void 백필은_재실행해도_결과가_동일하다() {
        // given
        String clip = seedRaw("V149-idem_RESL_RESL_1080P_1784779138682");

        // when — 2회 실행(운영 재적용·복구 시나리오)
        runBackfill();
        Map<String, Object> first = jdbc().queryForMap(
                "SELECT SRC_TYPE, AUG_TYPE_CD FROM LS_DATA_RAW WHERE VMS_CLIP_ID = ?", clip);
        runBackfill();
        Map<String, Object> second = jdbc().queryForMap(
                "SELECT SRC_TYPE, AUG_TYPE_CD FROM LS_DATA_RAW WHERE VMS_CLIP_ID = ?", clip);

        // then
        assertThat(second).isEqualTo(first);
        assertThat(second.get("AUG_TYPE_CD")).isEqualTo("RESL_1080P");
    }

    @Test
    @DisplayName("특수문자와_정규식_메타문자가_섞여도_예외없이_판정된다")
    void 특수문자와_정규식_메타문자가_섞여도_예외없이_판정된다() {
        // given — 원본명에 정규식 메타문자·퍼센트·언더스코어(LIKE 와일드카드)가 섞인 경우 (CWE-20)
        String meta = seedRaw("V149-%_[a-z]+_(x)_AUG_RAIN_1784779138682");
        String onlyMeta = seedRaw("V149-%%__[0-9]*$^-plain");

        // when
        runBackfill();

        // then — 파싱은 문자열 함수 기반이라 메타문자에 영향받지 않는다
        assertBackfilled(meta, "RAIN");
        assertBackfilled(onlyMeta, null);
    }

    // ------------------------------------------------------------------ 헬퍼

    /**
     * 정규화 규칙 전 영역을 덮는 케이스 — {@code VMS_CLIP_ID} → 기대 {@code AUG_TYPE_CD}(null = 원본 판정).
     *
     * <p>기대값은 파서 삭제 <b>이전</b>의 {@code AugTypeParser} 규칙으로 산출한 리터럴이며, 각 줄 주석이
     * 어느 규칙에서 그 값이 나오는지를 밝힌다. 순서 유지를 위해 {@link LinkedHashMap} 을 쓰고,
     * {@code Map.of} 대신 명시 put 을 쓰는 이유는 <b>null 값(원본 판정)을 담아야</b> 하기 때문이다.
     */
    private static Map<String, String> parityCases() {
        Map<String, String> cases = new LinkedHashMap<>();
        // 마커 없음 → 전체 스캔 폴백을 두지 않으므로 원본 판정
        cases.put("V149-p-plain-clip", null);
        cases.put("V149-p-winter480p-plain", null);        // 우연 토큰(winter/480p)이 섞여도 마커가 없으면 원본
        // _AUG_ 뒤 세그먼트에서 종류 토큰 판별
        cases.put("V149-p-a_AUG_WINTER_1784779138682", "WINTER");
        cases.put("V149-p-b_AUG_NIGHT_1784779138682", "NIGHT");
        cases.put("V149-p-c_AUG_RAIN_1784779138682", "RAIN");
        cases.put("winter-park-V149-p_AUG_NIGHT_1784779138682", "NIGHT"); // 원본명 winter 오탐 금지
        // _RESL_ 뒤 해상도 코드 판별 + 접두 정규화
        cases.put("V149-p-d_RESL_1080P_1784779138682", "RESL_1080P");
        cases.put("V149-p-e_RESL_720P_1784779138682", "RESL_720P");
        cases.put("V149-p-f_RESL_RESL_480P_1784779138682", "RESL_480P");  // 이중 접두 드리프트
        cases.put("V149-p-g_RES_RES_480P_1784779138682", "RESL_480P");    // 구형 _RES_ 드리프트
        cases.put("road480p-clip-V149-p_RESL_720P_1784779138682", "RESL_720P"); // 원본명 480p 오탐 금지
        cases.put("V149-p-h_aug_winter_1784779138682", "WINTER");         // 소문자 마커(대문자화 후 매칭)
        // 마커는 있으나 뒤 세그먼트에 토큰 없음 → 폴백 없이 원본 판정
        cases.put("V149-p-i_AUG_FOG_1784779138682", null);
        cases.put("V149-p-j_RESL_720P_AUG_FOG_1784779138682", null);      // 오른쪽 _AUG_ 가 이기고 폴백 금지
        // 마커 다중 → 가장 오른쪽 채택 / 코드 다중 → 첫 일치 채택
        cases.put("V149-p-k_AUG_WINTER_x_AUG_RAIN_1784779138682", "RAIN");
        cases.put("V149-p-l_RESL_720P_480P_1784779138682", "RESL_720P");
        // 경계 — 마커가 맨 앞(인덱스 0) / 접미 timestamp 없이 끝남
        cases.put("_AUG_WINTER_V149-p-m", "WINTER");
        cases.put("V149-p-n_RESL_1080P", "RESL_1080P");
        return cases;
    }

    /** {@code VMS_CLIP_ID} 원문만 넣고 두 컬럼은 비운 채 <b>원본</b> 영상 1행을 시드한다(자기충족 방지). */
    private String seedRaw(String vmsClipId) {
        jdbc().update("""
                INSERT INTO LS_DATA_RAW
                    (VMS_CLIP_ID, VMS_CCTV_ID, PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN,
                     RAW_FILE_PATH_NM, DATA_STTS_CD, REG_DT)
                VALUES (?, ?, 'ANONY', 'N', 'N', ?, 'PENDING', CURRENT_TIMESTAMP)
                """, vmsClipId, SEED_CCTV_ID, "/nas-storage/raw/v149-seed.mp4");
        return vmsClipId;
    }

    /**
     * <b>파생</b> 영상 1행을 시드한다 — {@code ORGNL_RAW_SN} 이 채워진 행. 부모는 같은 방식으로 먼저
     * 시드해 실제 존재하는 {@code RAW_SN} 을 참조한다(계보 self-reference 에 FK 는 없지만 실형상과 맞춘다).
     */
    private String seedDerivativeRaw(String vmsClipId) {
        String parentClip = seedRaw(vmsClipId + "-parent");
        Long parentRawSn = jdbc().queryForObject(
                "SELECT RAW_SN FROM LS_DATA_RAW WHERE VMS_CLIP_ID = ?", Long.class, parentClip);
        jdbc().update("""
                INSERT INTO LS_DATA_RAW
                    (VMS_CLIP_ID, VMS_CCTV_ID, PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN,
                     RAW_FILE_PATH_NM, DATA_STTS_CD, REG_DT, ORGNL_RAW_SN)
                VALUES (?, ?, 'ANONY', 'N', 'N', ?, 'PENDING', CURRENT_TIMESTAMP, ?)
                """, vmsClipId, SEED_CCTV_ID, "/nas-storage/raw/v149-seed-deriv.mp4", parentRawSn);
        return vmsClipId;
    }

    /**
     * 원본 시드 행의 백필 결과를 고정한다 — {@code AUG_TYPE_CD} 는 기대 리터럴(파서 삭제 전 규칙으로 산출),
     * {@code SRC_TYPE} 은 {@code ORGNL_RAW_SN} 축이므로 <b>항상 {@code ORIGINAL}</b> 이다.
     */
    private void assertBackfilled(String vmsClipId, String expectedAugType) {
        assertBackfilled(vmsClipId, expectedAugType, "ORIGINAL");
    }

    /** 백필 결과를 두 축(종류·출처유형) 각각의 기대값으로 고정한다. */
    private void assertBackfilled(String vmsClipId, String expectedAugType, String expectedSrcType) {
        Map<String, Object> row = jdbc().queryForMap(
                "SELECT SRC_TYPE, AUG_TYPE_CD FROM LS_DATA_RAW WHERE VMS_CLIP_ID = ?", vmsClipId);
        assertThat(row.get("AUG_TYPE_CD")).as("AUG_TYPE_CD clip=%s", vmsClipId)
                .isEqualTo(expectedAugType);
        assertThat(row.get("SRC_TYPE")).as("SRC_TYPE clip=%s", vmsClipId)
                .isEqualTo(expectedSrcType);
    }

    private Map<String, Object> fullRow(String vmsClipId) {
        return jdbc().queryForMap("SELECT * FROM LS_DATA_RAW WHERE VMS_CLIP_ID = ?", vmsClipId);
    }

    /** V149 파일의 실제 SQL 을 파일에서 읽어 순서대로 실행한다(테스트가 SQL 사본을 들지 않는다). */
    private void runBackfill() {
        for (String statement : parseStatements()) {
            jdbc().execute(statement);
        }
    }

    /**
     * V149 파일을 라인 주석 제거 후 문 단위로 분할한다.
     * {@code $$ ... $$} 달러 인용 블록(PL/pgSQL 본문) 내부의 ';' 는 문 경계로 보지 않는다.
     */
    private List<String> parseStatements() {
        String sql;
        try {
            sql = StreamUtils.copyToString(
                    new ClassPathResource(MIGRATION_PATH).getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("V149 마이그레이션 파일을 읽을 수 없습니다: " + MIGRATION_PATH, e);
        }
        String noComments = Arrays.stream(sql.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));

        List<String> statements = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inDollar = false;
        int i = 0;
        while (i < noComments.length()) {
            if (noComments.startsWith("$$", i)) {
                inDollar = !inDollar;
                cur.append("$$");
                i += 2;
                continue;
            }
            char c = noComments.charAt(i);
            if (c == ';' && !inDollar) {
                String s = cur.toString().trim();
                if (!s.isEmpty()) {
                    statements.add(s);
                }
                cur.setLength(0);
            } else {
                cur.append(c);
            }
            i++;
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }
}
