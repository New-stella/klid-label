package kr.co.cudo.authoring.video.repository;

import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관제 인입 엔티티·리포지토리 실동작 검증 (Phase 2, Testcontainers PostgreSQL).
 *
 * <p>검증 축:
 * <ul>
 *   <li><b>매핑 정합</b> — 엔티티 {@code @Column} 43건을 {@code information_schema} 실측과
 *       1:1 대조하고(이름·타입·길이·NOT NULL), 관제가 JDBC 로 넣은 값이 <b>전 컬럼 왕복</b>한다.
 *       <b>기동 성공에 의존하지 않는다</b> — 이 프로젝트는 {@code JpaBuilderConfig} 가
 *       {@code spring.jpa.hibernate.ddl-auto} 를 EMF 로 넘기지 않아 <b>부팅 시 스키마 검증이
 *       실제로는 수행되지 않는다</b>(존재하지 않는 컬럼을 매핑해도 기동 성공 — 실측 확인).</li>
 *   <li><b>폴링 조회</b> — {@code PENDING} 만, 수신일시 오름차순, 상한(Pageable) 준수.</li>
 *   <li><b>원자 클레임</b> — 착수 전이({@code PENDING}→{@code PROCESSING})가 조건부 UPDATE 로만
 *       일어나고, 같은 행을 두 번 노리면 <b>한쪽만 1행</b>을 얻는다(CWE-362 중복 적재 차단).</li>
 *   <li><b>미처리 복귀</b> — 파일 미도착처럼 실패가 아닌 경우 {@code PROCESSING} 행을
 *       {@code PENDING} 으로 되돌려 다음 주기가 다시 집는다(영구 좀비·영구 미적재 차단, 설계 §6-0).</li>
 *   <li><b>종결 재큐</b> — {@code FAILED} 종결도 <b>가역</b>이다. 조건부 UPDATE 로만 되살아나며
 *       처리 중·성공 종결 행은 건드리지 않는다(설계 §6-0-1 ② — 설정 오류로 인한 영구 소실 차단).</li>
 *   <li><b>상태 전이 영속화</b> — {@code markDone}/{@code markFailed} 가 DB 에 실제 반영되고
 *       {@code ERR_MSG} 는 개행·제어문자가 제거된 형태로 저장된다(CWE-117).</li>
 *   <li><b>관제 소유값 보호</b> — 우리 상태 전이 flush 가 관제 수신 33컬럼을 stale 값으로 덮지 않는다
 *       ({@code @DynamicUpdate}, CWE-362 lost update / CWE-915).</li>
 * </ul>
 *
 * <p>시드는 {@code INGEST-IT-*} 접두로 격리하고 테스트 트랜잭션 롤백으로 정리한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LsDataIngestRepositoryIT {

    private static final String CLIP_PREFIX = "INGEST-IT-";

    /**
     * V147 DDL 의 {@code NUMERIC} 자릿수 정본 — {@code (precision, scale)}.
     *
     * <p>엔티티 {@code @Column} 과 실제 스키마를 <b>양쪽 다</b> 이 표에 맞춘다. 타입명("numeric")만
     * 대조하면 {@code DECIMAL(4,1)} 을 {@code DECIMAL(10,7)} 로 잘못 선언해도 통과하고,
     * {@code precision}/{@code scale} 을 아예 생략하면 Hibernate 기본값 {@code NUMERIC(19,2)} 가
     * 적용된다. 이 프로젝트는 부팅 시 {@code ddl-auto=validate} 가 실제로 수행되지 않아
     * (아래 매핑 정합 테스트 주석 참조) <b>이 대조가 유일한 매핑 자릿수 가드</b>다.
     */
    private static final Map<String, int[]> EXPECTED_NUMERIC_PRECISION_SCALE = Map.of(
            "vdo_len_sec", new int[]{10, 0},   // 수N10  = NUMERIC(10)
            "frme_cnt", new int[]{10, 0},       // 수N10
            "wdth", new int[]{10, 0},          // 수N10
            "vrtc", new int[]{10, 0},          // 수N10
            "wgs84_lat", new int[]{10, 7},     // 좌표D10 = DECIMAL(10,7)
            "wgs84_lot", new int[]{10, 7},     // 좌표D10
            "cctv_hgt", new int[]{4, 1});      // 수D5    = DECIMAL(4,1)

    @Autowired
    private LsDataIngestRepository ingestRepository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @PersistenceContext
    private EntityManager em;

    private JdbcTemplate jdbc;

    /** 클래스 내 시드 클립 ID 를 서로 겹치지 않게 하는 실행 단위 접미(컨테이너 재사용 대비). */
    private String runId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        runId = String.valueOf(System.nanoTime());
    }

    @Test
    @DisplayName("엔티티_매핑이_실제_스키마와_정합한다")
    void 엔티티_매핑이_실제_스키마와_정합한다() {
        // given — 엔티티가 선언한 @Column 매핑 전량
        List<Field> mapped = Arrays.stream(LsDataIngest.class.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(Column.class))
                .toList();

        // then — 총 컬럼 수(관제 수신 36 + 저작도구 운영 8)를 빠짐없이 매핑했다.
        //   관제 수신 = V147 의 29 + V166 신설 4(EVNT_TYPE_CD + 원천 개인정보 3필드)
        //   + V168 신설 2(EVNT_CLSF_CD·EVNT_CTGRY_CD) + V176 신설 1(VRFC_EVNT_TYPE_CD)
        //   − V185 제거 1(OG_CD) + V16 신설 2(THMB_FILE_PATH_NM·OG_CD 복원)
        //   − V17 제거 1(THMB_FILE_PATH_NM — 대표 이미지 조달원이 비식별 프레임으로 바뀜) = 36.
        assertThat(mapped).as("LS_DATA_INGEST 매핑 컬럼 수").hasSize(44);

        // then — 컬럼별로 실제 스키마와 이름·타입·길이·NULL 허용이 일치한다
        //   ★ 이 단언을 컨텍스트 기동(ddl-auto=validate)에 위임하지 않는 이유:
        //     JpaBuilderConfig 가 EntityManagerFactoryBuilder 에 spring.jpa.properties.* 만 넘겨
        //     spring.jpa.hibernate.ddl-auto 가 EMF 에 도달하지 않는다(실측 — 존재하지 않는 컬럼을
        //     매핑해도 기동이 성공했다). 즉 <b>기동 성공은 매핑 정합의 증거가 아니다</b>.
        for (Field field : mapped) {
            Column column = field.getAnnotation(Column.class);
            String columnName = column.name().toLowerCase(Locale.ROOT);

            List<Map<String, Object>> meta = jdbc.queryForList(
                    "SELECT data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable "
                            + "FROM information_schema.columns "
                            + "WHERE table_name = 'ls_data_ingest' AND column_name = ?", columnName);
            assertThat(meta).as("%s 컬럼 실재", columnName).hasSize(1);

            Map<String, Object> actual = meta.get(0);
            assertThat(actual.get("data_type")).as("%s 타입", columnName)
                    .isEqualTo(expectedDataType(field.getType(), columnName));

            if (field.getType() == String.class) {
                assertThat(((Number) actual.get("character_maximum_length")).intValue())
                        .as("%s 길이", columnName).isEqualTo(column.length());
            }
            if (field.getType() == BigDecimal.class) {
                // 타입명 대조만으로는 자릿수 드리프트가 통과한다 — V147 정본 표와 3자 대조한다
                // (실제 스키마 ↔ 정본 표 ↔ 엔티티 @Column).
                int[] expected = EXPECTED_NUMERIC_PRECISION_SCALE.get(columnName);
                assertThat(expected)
                        .as("%s 자릿수 기대값 미등록 — V147 정본에 맞춰 표에 추가하라", columnName)
                        .isNotNull();

                assertThat(((Number) actual.get("numeric_precision")).intValue())
                        .as("%s 실제 스키마 precision", columnName).isEqualTo(expected[0]);
                assertThat(((Number) actual.get("numeric_scale")).intValue())
                        .as("%s 실제 스키마 scale", columnName).isEqualTo(expected[1]);

                // @Column 에 자릿수를 명시하지 않으면 Hibernate 기본값 NUMERIC(19,2) 로 해석돼
                // ddl-auto=validate 가 되살아나는 순간 기동이 깨진다.
                assertThat(column.precision())
                        .as("%s @Column precision 명시", columnName).isEqualTo(expected[0]);
                assertThat(column.scale())
                        .as("%s @Column scale 명시", columnName).isEqualTo(expected[1]);
            }
            if (!column.nullable()) {
                assertThat(actual.get("is_nullable")).as("%s NOT NULL", columnName).isEqualTo("NO");
            }
        }

        // then — BigDecimal 매핑 7종이 빠짐없이 정본 표에 등록돼 있다(표 자체의 누락 방지)
        assertThat(mapped.stream()
                .filter(f -> f.getType() == BigDecimal.class)
                .map(f -> f.getAnnotation(Column.class).name().toLowerCase(Locale.ROOT)))
                .as("NUMERIC 자릿수 정본 표 커버리지")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_NUMERIC_PRECISION_SCALE.keySet());
    }

    /**
     * 고정길이({@code CHAR}) 매핑분 — 여부(YN) 3종은 여부C1, 이벤트 코드 2종은 코드C2·코드C4(V172).
     *
     * <p>Java 타입은 {@code String} 이라 타입만으로는 {@code VARCHAR} 와 구분되지 않는다.
     * 엔티티가 {@code @JdbcTypeCode(SqlTypes.CHAR)} 로 고정한 컬럼을 여기 명시해 실제 스키마
     * ({@code character})와 대조한다 — 누락하면 {@code VARCHAR} 로 드리프트해도 통과한다.
     */
    private static final java.util.Set<String> CHAR_COLUMNS =
            java.util.Set.of("anony_incl_yn", "psdo_incl_yn", "prvc_incl_yn",
                    "evnt_clsf_cd", "evnt_ctgry_cd");

    /** Java 매핑 타입 → PostgreSQL {@code information_schema.data_type}. */
    private static String expectedDataType(Class<?> javaType, String columnName) {
        if (javaType == String.class) {
            return CHAR_COLUMNS.contains(columnName) ? "character" : "character varying";
        }
        if (javaType == Long.class) {
            return "bigint";
        }
        if (javaType == Integer.class) {
            return "integer";
        }
        if (javaType == BigDecimal.class) {
            return "numeric";
        }
        if (javaType == LocalDateTime.class) {
            return "timestamp without time zone";
        }
        throw new IllegalArgumentException("매핑 타입 대응표 미등록: " + javaType);
    }

    @Test
    @DisplayName("관제가_INSERT한_41컬럼이_엔티티로_왕복한다")
    void 관제가_INSERT한_41컬럼이_엔티티로_왕복한다() {
        // given — 관제가 41컬럼 중 수신 33컬럼을 전부 채워 INSERT 한 행
        String clipId = clip("FULL");
        LocalDateTime shtDt = LocalDateTime.of(2026, 7, 31, 13, 45, 12);
        seedFullIngest(clipId, shtDt);

        // when — JPA 로 로드 (컨텍스트가 이미 ddl-auto=validate 를 통과해 기동된 상태)
        LsDataIngest ingest = ingestRepository.findByVmsClipId(clipId).orElseThrow();

        // then — 저작도구 운영 8컬럼
        assertThat(ingest.getRcptnSn()).isNotNull();
        assertThat(ingest.getRcptnDt()).isNotNull();
        assertThat(ingest.getPrcsSttsCd()).isEqualTo(LsDataIngest.PRCS_STTS_PENDING);
        assertThat(ingest.getRawSn()).isNull();
        assertThat(ingest.getRtyCnt()).isZero();
        assertThat(ingest.getPrcsDt()).isNull();
        assertThat(ingest.getErrMsg()).isNull();
        assertThat(ingest.getNxtmRtryDt()).as("신규 인입 행은 재시도 예정이 없다(즉시 후보)").isNull();

        // then — 관제 수신 33컬럼이 손실·형변환 오류 없이 왕복한다
        assertThat(ingest.getVmsClipId()).isEqualTo(clipId);
        assertThat(ingest.getVmsCctvId()).isEqualTo("CCTV-INGEST-01");
        assertThat(ingest.getVdoFileNm()).isEqualTo("clip.mp4");
        assertThat(ingest.getRawFilePathNm()).isEqualTo("/nas-storage/raw/clip.mp4");
        assertThat(ingest.getSrcType()).isEqualTo("RELAY");
        assertThat(ingest.getShtDt()).isEqualTo(shtDt);
        assertThat(ingest.getFileFmt()).isEqualTo("mp4");
        assertThat(ingest.getVdoCdc()).isEqualTo("h264");
        assertThat(ingest.getFileSz()).isEqualTo(4_915_200L);
        assertThat(ingest.getLclgvNm()).isEqualTo("대전광역시 유성구");
        assertThat(ingest.getVdoLenSec()).isEqualByComparingTo("30");
        assertThat(ingest.getFps()).isEqualTo("30");
        assertThat(ingest.getFrmeCnt()).isEqualByComparingTo("900");
        assertThat(ingest.getAsprtRt()).isEqualTo("16:9");
        assertThat(ingest.getWdth()).isEqualByComparingTo("1920");
        assertThat(ingest.getVrtc()).isEqualByComparingTo("1080");
        assertThat(ingest.getResl()).isEqualTo("FHD");
        assertThat(ingest.getBit()).as("BIT — 비트레이트 bps 정수(V16, 예약어 우려 컬럼)").isEqualTo("2050627");
        assertThat(ingest.getPxl()).isEqualTo("4K");
        assertThat(ingest.getWgs84Lat()).isEqualByComparingTo("36.3504119");
        assertThat(ingest.getWgs84Lot()).isEqualByComparingTo("127.3845475");
        // ★ 기관코드(OG_CD) 왕복 단언은 V185(관제 "공급 불가") 때 제거됐고, V16 에서 컬럼이 복원된
        //   뒤에도 되살리지 않았다 — 이 픽스처의 INSERT 가 그 컬럼을 채우지 않기 때문이다.
        //   OG_CD 는 LCLGV_CD·LCLGV_NM 과 서로 다른 값이다.
        assertThat(ingest.getCctvNm()).isEqualTo("유성구 어은동 사거리");
        assertThat(ingest.getCctvHgt()).isEqualByComparingTo("4.5");
        assertThat(ingest.getMainSurvPanAng()).isEqualTo(135);
        assertThat(ingest.getEvntId()).isEqualTo("ABA_0001");
        assertThat(ingest.getEvntNm()).isEqualTo("배회");
        assertThat(ingest.getMntrCn()).isEqualTo("관제일지 내용");
        // 지방자치단체코드 — 관제 완료통지 페이로드 lclgv_cd(required)의 값 출처.
        // 지역명(LCLGV_NM '대전광역시 유성구')과 <서로 다른 값>이다.
        assertThat(ingest.getLclgvCd()).as("지방자치단체코드 — LCLGV_NM 과 별개 값").isEqualTo("3020000000");
        // 이벤트유형코드(V166 신설) — 관제 공유 테이블(MNG_CLIP_EVNT_LST) 조인 해석의 대체 경로.
        // EVNT_ID('ABA_0001', 식별자형)와 <서로 다른 값>이다 — 대체·통합하지 않는다.
        assertThat(ingest.getEvntTypeCd()).as("이벤트유형코드 — EVNT_ID 와 별개 값").isEqualTo("INTRUSION");
        // 원천 영상(비식별 처리 전) 개인정보 3필드(V166 신설). CHAR(1) 이 공백 패딩·트림 없이 왕복한다.
        // ★ LS_DATA_RAW 의 동명 컬럼(V163)은 <비식별 영상에 대한 사람의 수동 판정>이라 별개 축이다.
        assertThat(ingest.getAnonyInclYn()).as("원천 익명정보 포함여부").isEqualTo("N");
        assertThat(ingest.getPsdoInclYn()).as("원천 가명정보 포함여부").isEqualTo("N");
        assertThat(ingest.getPrvcInclYn()).as("원천 개인정보 포함여부").isEqualTo("Y");
    }

    @Test
    @DisplayName("PENDING_행만_수신일시_오름차순으로_조회된다")
    void PENDING_행만_수신일시_오름차순으로_조회된다() {
        // given — PENDING 2건(수신일시 역순으로 INSERT) + 종결 1건
        String older = clip("OLD");
        String newer = clip("NEW");
        String done = clip("DONE");
        LocalDateTime base = LocalDateTime.now().minusHours(3);
        seedMinimalIngest(newer, base.plusMinutes(30), LsDataIngest.PRCS_STTS_PENDING);
        seedMinimalIngest(older, base, LsDataIngest.PRCS_STTS_PENDING);
        seedMinimalIngest(done, base.minusMinutes(30), LsDataIngest.PRCS_STTS_DONE);

        // when
        List<String> found = ingestRepository.findPendingReadyForPolling(LocalDateTime.now(), PageRequest.of(0, 500)).stream()
                .map(LsDataIngest::getVmsClipId)
                .filter(id -> id.startsWith(CLIP_PREFIX))
                .toList();

        // then — PENDING 만, 수신일시 오름차순(INSERT 순서가 아니다)
        assertThat(found).containsExactly(older, newer);
        assertThat(found).doesNotContain(done);

        // then — 상한이 실제로 적용된다(전량 조회 금지 규칙)
        assertThat(ingestRepository.findPendingReadyForPolling(LocalDateTime.now(), PageRequest.of(0, 1))).hasSize(1);
    }

    @Test
    @DisplayName("findByVmsClipId로_기적재_여부를_조회할_수_있다")
    void findByVmsClipId로_기적재_여부를_조회할_수_있다() {
        // given
        String clipId = clip("LOOKUP");
        seedMinimalIngest(clipId, LocalDateTime.now(), LsDataIngest.PRCS_STTS_PENDING);

        // when / then — 존재하면 조회된다(관제 재송신·중복 적재 판정의 진입점)
        assertThat(ingestRepository.findByVmsClipId(clipId))
                .map(LsDataIngest::getVmsClipId)
                .contains(clipId);

        // when / then — 없으면 빈 Optional (null 반환 금지)
        Optional<LsDataIngest> missing = ingestRepository.findByVmsClipId(clip("NOT-EXIST"));
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("PENDING_행을_클레임하면_PROCESSING으로_전이되고_1을_반환한다")
    void PENDING_행을_클레임하면_PROCESSING으로_전이되고_1을_반환한다() {
        // given — 관제가 INSERT 한 미처리 행
        String clipId = clip("CLAIM-OK");
        seedMinimalIngest(clipId, LocalDateTime.now(), LsDataIngest.PRCS_STTS_PENDING);
        Long rcptnSn = ingestRepository.findByVmsClipId(clipId).orElseThrow().getRcptnSn();

        // when — 원자 클레임
        int claimed = ingestRepository.claimForProcessing(rcptnSn);

        // then — 영향 행 수 1 = 이 실행이 잡았다
        assertThat(claimed).as("클레임 성공").isEqualTo(1);

        // then — DB 상태가 실제로 전이됐고, 착수 시점에 종결 시각(PRCS_DT)은 찍지 않는다
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT prcs_stts_cd, prcs_dt, raw_sn FROM ls_data_ingest WHERE vms_clip_id = ?", clipId);
        assertThat(row.get("prcs_stts_cd")).isEqualTo(LsDataIngest.PRCS_STTS_PROCESSING);
        assertThat(row.get("prcs_dt")).as("종결 시각은 착수 시점에 찍지 않는다").isNull();
        assertThat(row.get("raw_sn")).isNull();

        // then — 폴링 후보에서 빠진다(다음 tick 이 다시 집지 않는다)
        assertThat(ingestRepository.findPendingReadyForPolling(LocalDateTime.now(), PageRequest.of(0, 500)))
                .extracting(LsDataIngest::getVmsClipId)
                .doesNotContain(clipId);
    }

    @Test
    @DisplayName("동시에_같은_행을_클레임하면_하나만_성공한다")
    void 동시에_같은_행을_클레임하면_하나만_성공한다() {
        // given — 두 실행(2노드 Active-Active)이 같은 후보 목록에서 같은 PENDING 행을 집었다
        String clipId = clip("CLAIM-RACE");
        seedMinimalIngest(clipId, LocalDateTime.now(), LsDataIngest.PRCS_STTS_PENDING);
        Long rcptnSn = ingestRepository.findByVmsClipId(clipId).orElseThrow().getRcptnSn();

        // when — 둘 다 클레임을 시도한다.
        //   ★ 순차 호출이 실제 경합의 충실한 대역인 이유: PostgreSQL 은 UPDATE 시 행 락을 얻은 뒤
        //     <갱신된 최신 버전으로 WHERE 를 재평가>한다. 즉 진짜 동시 실행에서도 패자는 승자의
        //     커밋 결과(PROCESSING)에 대해 술어를 다시 보고 0행을 얻는다 — 아래와 같은 상황이다.
        int first = ingestRepository.claimForProcessing(rcptnSn);
        int second = ingestRepository.claimForProcessing(rcptnSn);

        // then — 승자만 1행. 패자는 0행이라 "내가 잡았다"고 착각하지 않는다(중복 적재 차단, CWE-362)
        assertThat(first).as("먼저 도달한 실행").isEqualTo(1);
        assertThat(second).as("나중 실행 — 이미 다른 실행이 가져갔다").isZero();

        // then — 상태는 한 번만 전이됐다
        assertThat(jdbc.queryForObject(
                "SELECT prcs_stts_cd FROM ls_data_ingest WHERE vms_clip_id = ?", String.class, clipId))
                .isEqualTo(LsDataIngest.PRCS_STTS_PROCESSING);
    }

    @Test
    @DisplayName("이미_PROCESSING인_행은_클레임에_실패한다")
    void 이미_PROCESSING인_행은_클레임에_실패한다() {
        // given — 다른 실행이 이미 착수한 행
        String clipId = clip("CLAIM-BUSY");
        seedMinimalIngest(clipId, LocalDateTime.now(), LsDataIngest.PRCS_STTS_PROCESSING);
        Long rcptnSn = ingestRepository.findByVmsClipId(clipId).orElseThrow().getRcptnSn();

        // when / then — 술어(PENDING)가 맞지 않아 0행
        assertThat(ingestRepository.claimForProcessing(rcptnSn)).isZero();
    }

    @Test
    @DisplayName("종결된_행과_없는_행은_클레임되지_않는다")
    void 종결된_행과_없는_행은_클레임되지_않는다() {
        // given — 이미 종결된 두 행(fail-safe: 늦게 온 클레임이 종결을 되돌리면 안 된다)
        String done = clip("CLAIM-DONE");
        String failed = clip("CLAIM-FAILED");
        seedMinimalIngest(done, LocalDateTime.now(), LsDataIngest.PRCS_STTS_DONE);
        seedMinimalIngest(failed, LocalDateTime.now(), LsDataIngest.PRCS_STTS_FAILED);

        // when / then — 종결 상태는 재착수되지 않는다
        assertThat(ingestRepository.claimForProcessing(
                ingestRepository.findByVmsClipId(done).orElseThrow().getRcptnSn())).isZero();
        assertThat(ingestRepository.claimForProcessing(
                ingestRepository.findByVmsClipId(failed).orElseThrow().getRcptnSn())).isZero();

        // then — 종결 상태가 PROCESSING 으로 되돌아가지 않았다
        assertThat(jdbc.queryForObject(
                "SELECT prcs_stts_cd FROM ls_data_ingest WHERE vms_clip_id = ?", String.class, done))
                .isEqualTo(LsDataIngest.PRCS_STTS_DONE);

        // when / then — 존재하지 않는 PK 도 0행(예외가 아니라 클레임 실패로 다룬다)
        assertThat(ingestRepository.claimForProcessing(-1L)).isZero();
    }

    @Test
    @DisplayName("PROCESSING인_행을_PENDING으로_되돌리면_1을_반환한다")
    void PROCESSING인_행을_PENDING으로_되돌리면_1을_반환한다() {
        // given — 클레임에 성공했지만 파일이 아직 NAS 에 도착하지 않았다(실패가 아니라 미처리)
        String clipId = clip("REVERT-OK");
        seedMinimalIngest(clipId, LocalDateTime.now(), LsDataIngest.PRCS_STTS_PENDING);
        Long rcptnSn = ingestRepository.findByVmsClipId(clipId).orElseThrow().getRcptnSn();
        assertThat(ingestRepository.claimForProcessing(rcptnSn)).isEqualTo(1);

        // when — 미처리 복귀
        LocalDateTime observedAt = LocalDateTime.now();
        LocalDateTime nextRetryAt = observedAt.plusMinutes(1);
        int reverted = ingestRepository.revertToPendingForRetry(rcptnSn, observedAt, nextRetryAt);

        // then — 영향 행 수 1 = 복귀 성공
        assertThat(reverted).as("복귀 성공").isEqualTo(1);

        // then — DB 상태가 실제로 되돌아갔고, 복귀는 실패가 아니므로 사유를 남기지 않는다.
        //   PRCS_DT 는 이제 <최초 미도착 관측 시각(대기 예산 앵커)>이라 채워진다(설계 §6-0-1-a ㉠).
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT prcs_stts_cd, prcs_dt, nxtm_rtry_dt, err_msg, raw_sn"
                        + " FROM ls_data_ingest WHERE vms_clip_id = ?",
                clipId);
        assertThat(row.get("prcs_stts_cd")).isEqualTo(LsDataIngest.PRCS_STTS_PENDING);
        assertThat(((Timestamp) row.get("prcs_dt")).toLocalDateTime())
                .as("대기 예산 앵커 = 최초 미도착 관측 시각").isEqualTo(observedAt);
        assertThat(((Timestamp) row.get("nxtm_rtry_dt")).toLocalDateTime())
                .as("backoff — 다음 재시도 예정").isEqualTo(nextRetryAt);
        assertThat(row.get("err_msg")).as("복귀는 실패가 아니다").isNull();
        assertThat(row.get("raw_sn")).isNull();
    }

    @Test
    @DisplayName("두번째_미도착_복귀는_예산앵커를_움직이지_않는다")
    void 두번째_미도착_복귀는_예산앵커를_움직이지_않는다() {
        // given — 미도착으로 한 번 복귀해 앵커가 찍힌 행
        String clipId = clip("ANCHOR-KEEP");
        seedMinimalIngest(clipId, LocalDateTime.now(), LsDataIngest.PRCS_STTS_PENDING);
        Long rcptnSn = ingestRepository.findByVmsClipId(clipId).orElseThrow().getRcptnSn();
        LocalDateTime firstObserved = LocalDateTime.now().minusHours(2);
        assertThat(ingestRepository.claimForProcessing(rcptnSn)).isEqualTo(1);
        assertThat(ingestRepository.revertToPendingForRetry(
                rcptnSn, firstObserved, firstObserved.plusMinutes(1))).isEqualTo(1);

        // when — 다음 tick 이 다시 집었으나 여전히 미도착
        LocalDateTime secondObserved = LocalDateTime.now();
        assertThat(ingestRepository.claimForProcessing(rcptnSn)).isEqualTo(1);
        assertThat(ingestRepository.revertToPendingForRetry(
                rcptnSn, secondObserved, secondObserved.plusMinutes(5))).isEqualTo(1);

        // then — ★앵커는 최초 관측 시각 그대로다. 갱신되면 경과가 매번 0 으로 리셋돼 대기 상한이
        //   영원히 오지 않는다(= ①(상한)이 무의미해지고 고착 행이 큐에 영원히 남는다).
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT prcs_dt, nxtm_rtry_dt FROM ls_data_ingest WHERE vms_clip_id = ?", clipId);
        assertThat(((Timestamp) row.get("prcs_dt")).toLocalDateTime())
                .as("앵커 불변").isEqualTo(firstObserved);
        // 반면 다음 재시도 예정은 매 관측마다 갱신된다(backoff 는 점점 늘어난다).
        assertThat(((Timestamp) row.get("nxtm_rtry_dt")).toLocalDateTime())
                .isEqualTo(secondObserved.plusMinutes(5));
    }

    @Test
    @DisplayName("재시도_예정시각_전인_행은_폴링_후보에서_빠진다")
    void 재시도_예정시각_전인_행은_폴링_후보에서_빠진다() {
        // given — 미도착 backoff 로 다음 시도가 뒤로 밀린 행(가장 오래된 수신일시 = FIFO 앞자리)
        String deferred = clip("BACKOFF-DEFERRED");
        String ready = clip("BACKOFF-READY");
        LocalDateTime base = LocalDateTime.now().minusHours(2);
        seedMinimalIngest(deferred, base, LsDataIngest.PRCS_STTS_PENDING);
        seedMinimalIngest(ready, base.plusMinutes(10), LsDataIngest.PRCS_STTS_PENDING);
        Long deferredSn = ingestRepository.findByVmsClipId(deferred).orElseThrow().getRcptnSn();
        assertThat(ingestRepository.claimForProcessing(deferredSn)).isEqualTo(1);
        assertThat(ingestRepository.revertToPendingForRetry(
                deferredSn, LocalDateTime.now(), LocalDateTime.now().plusMinutes(30))).isEqualTo(1);

        // when — 지금 시각 기준 폴링
        List<String> now = pendingClipIds();

        // then — ★고착 행이 FIFO 앞자리를 잠식하지 않는다(head-of-line blocking 차단).
        assertThat(now).doesNotContain(deferred);
        assertThat(now).contains(ready);

        // then — 예정 시각이 지나면 다시 후보가 된다(보류이지 종결이 아니다)
        List<String> later = ingestRepository
                .findPendingReadyForPolling(LocalDateTime.now().plusHours(1), PageRequest.of(0, 500)).stream()
                .map(LsDataIngest::getVmsClipId)
                .filter(id -> id.startsWith(CLIP_PREFIX))
                .toList();
        assertThat(later).contains(deferred);
    }

    @Test
    @DisplayName("PENDING이나_DONE인_행은_되돌릴_수_없고_0을_반환한다")
    void PENDING이나_DONE인_행은_되돌릴_수_없고_0을_반환한다() {
        // given — 아직 아무도 클레임하지 않은 행 / 이미 종결된 행 2종
        String pending = clip("REVERT-PENDING");
        String done = clip("REVERT-DONE");
        String failed = clip("REVERT-FAILED");
        seedMinimalIngest(pending, LocalDateTime.now(), LsDataIngest.PRCS_STTS_PENDING);
        seedMinimalIngest(done, LocalDateTime.now(), LsDataIngest.PRCS_STTS_DONE);
        seedMinimalIngest(failed, LocalDateTime.now(), LsDataIngest.PRCS_STTS_FAILED);

        // when / then — 술어(PROCESSING)가 맞지 않으면 0행이다.
        //   특히 DONE/FAILED 를 되살리면 <이미 적재된 영상이 다시 적재>되므로 술어가 방어선이다.
        assertThat(ingestRepository.revertToPendingForRetry(
                ingestRepository.findByVmsClipId(pending).orElseThrow().getRcptnSn(),
                LocalDateTime.now(), LocalDateTime.now()))
                .as("이미 PENDING — 중복 복귀 아님").isZero();
        assertThat(ingestRepository.revertToPendingForRetry(
                ingestRepository.findByVmsClipId(done).orElseThrow().getRcptnSn(),
                LocalDateTime.now(), LocalDateTime.now()))
                .as("종결(DONE) 되살리기 금지").isZero();
        assertThat(ingestRepository.revertToPendingForRetry(
                ingestRepository.findByVmsClipId(failed).orElseThrow().getRcptnSn(),
                LocalDateTime.now(), LocalDateTime.now()))
                .as("종결(FAILED) 되살리기 금지").isZero();

        // then — 종결 상태가 PENDING 으로 훼손되지 않았다
        assertThat(jdbc.queryForObject(
                "SELECT prcs_stts_cd FROM ls_data_ingest WHERE vms_clip_id = ?", String.class, done))
                .isEqualTo(LsDataIngest.PRCS_STTS_DONE);
        assertThat(jdbc.queryForObject(
                "SELECT prcs_stts_cd FROM ls_data_ingest WHERE vms_clip_id = ?", String.class, failed))
                .isEqualTo(LsDataIngest.PRCS_STTS_FAILED);

        // when / then — 존재하지 않는 PK 도 0행(예외가 아니라 복귀 실패로 다룬다)
        assertThat(ingestRepository.revertToPendingForRetry(-1L, LocalDateTime.now(), LocalDateTime.now())).isZero();
    }

    @Test
    @DisplayName("되돌린_행은_다음_PENDING_폴링에_다시_조회된다")
    void 되돌린_행은_다음_PENDING_폴링에_다시_조회된다() {
        // given — 클레임으로 폴링 후보에서 빠진 행(이 상태로 방치하면 영구 좀비다)
        String clipId = clip("REVERT-REPOLL");
        seedMinimalIngest(clipId, LocalDateTime.now().minusMinutes(5), LsDataIngest.PRCS_STTS_PENDING);
        Long rcptnSn = ingestRepository.findByVmsClipId(clipId).orElseThrow().getRcptnSn();
        assertThat(ingestRepository.claimForProcessing(rcptnSn)).isEqualTo(1);
        assertThat(pendingClipIds()).as("클레임 직후엔 후보에서 빠진다").doesNotContain(clipId);

        // when — 파일 미도착으로 미처리 복귀
        assertThat(ingestRepository.revertToPendingForRetry(rcptnSn, LocalDateTime.now(), LocalDateTime.now())).isEqualTo(1);

        // then — 다음 주기 폴링이 다시 집는다(R4: 실패가 아니라 미처리로 두고 재시도)
        assertThat(pendingClipIds()).as("다음 tick 재조회").contains(clipId);

        // then — 다시 클레임할 수 있다(복귀가 착수 통로를 되살린다)
        assertThat(ingestRepository.claimForProcessing(rcptnSn)).isEqualTo(1);
    }

    @Test
    @DisplayName("되돌리기는_재시도횟수를_증가시키지_않는다")
    void 되돌리기는_재시도횟수를_증가시키지_않는다() {
        // given — 재시도 카운터는 markFailed 가 누적하는 "실패 시도 이력"이다
        String clipId = clip("REVERT-NO-RTY");
        seedMinimalIngest(clipId, LocalDateTime.now(), LsDataIngest.PRCS_STTS_PENDING);
        Long rcptnSn = ingestRepository.findByVmsClipId(clipId).orElseThrow().getRcptnSn();
        assertThat(rtyCntOf(clipId)).isZero();

        // when — 클레임 → 복귀를 3회 반복(파일이 계속 도착하지 않는 상황)
        for (int i = 0; i < 3; i++) {
            assertThat(ingestRepository.claimForProcessing(rcptnSn)).isEqualTo(1);
            assertThat(ingestRepository.revertToPendingForRetry(rcptnSn, LocalDateTime.now(), LocalDateTime.now())).isEqualTo(1);
        }

        // then — 복귀는 실패가 아니라 미처리 유지이므로 실패 이력이 부풀지 않는다
        assertThat(rtyCntOf(clipId)).as("복귀는 RTY_CNT 를 건드리지 않는다").isZero();
    }

    @Test
    @DisplayName("FAILED_행을_재큐하면_PENDING으로_돌아오고_1을_반환한다")
    void FAILED_행을_재큐하면_PENDING으로_돌아오고_1을_반환한다() {
        // given — 경로 오설정 등으로 종결된 행. UK(VMS_CLIP_ID) 때문에 관제 재INSERT 가 불가하고
        //   인입 행 삭제도 금지라, 이 통로가 없으면 이 클립은 영원히 적재되지 않는다(설계 §6-0-1 ②).
        String clipId = clip("REQUEUE-OK");
        seedMinimalIngest(clipId, LocalDateTime.now(), LsDataIngest.PRCS_STTS_PENDING);
        Long rcptnSn = ingestRepository.findByVmsClipId(clipId).orElseThrow().getRcptnSn();
        assertThat(ingestRepository.claimForProcessing(rcptnSn)).isEqualTo(1);
        LsDataIngest claimed = ingestRepository.findById(rcptnSn).orElseThrow();
        claimed.markFailed("허용 저장 루트 밖 경로");
        em.flush();
        em.clear();
        assertThat(rtyCntOf(clipId)).isEqualTo(1);

        // when — 오설정을 고친 뒤 재큐
        int requeued = ingestRepository.requeueFailedForRetry(rcptnSn);

        // then — 영향 행 수 1 + 상태 복귀 + 사유 제거(무효가 된 종결 사유를 남기지 않는다)
        assertThat(requeued).as("재큐 성공").isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT prcs_stts_cd, err_msg, rty_cnt, prcs_dt, nxtm_rtry_dt"
                        + " FROM ls_data_ingest WHERE vms_clip_id = ?", clipId);
        assertThat(row.get("prcs_stts_cd")).isEqualTo(LsDataIngest.PRCS_STTS_PENDING);
        assertThat(row.get("err_msg")).as("무효가 된 종결 사유는 비운다").isNull();
        // ★대기 예산 리셋(설계 §6-0-1-a ㉡) — 앵커가 남으면 상한 초과로 종결된 행을 재큐해도
        //   다음 tick(≤60s)에 즉시 재종결된다(재시도 창 0초 = 재큐 통로가 무의미해진다).
        assertThat(row.get("prcs_dt")).as("예산 앵커 리셋").isNull();
        assertThat(row.get("nxtm_rtry_dt")).as("재시도 예정 리셋 — 즉시 후보").isNull();
        // 재큐는 <실패 이력의 연장>이다 — 미도착 복귀(RTY_CNT 불변)와 반대 축이다.
        assertThat(((Number) row.get("rty_cnt")).intValue()).as("실패 이력은 누적").isEqualTo(2);

        // then — 다음 주기 폴링이 다시 집고 착수도 가능하다
        assertThat(pendingClipIds()).contains(clipId);
        assertThat(ingestRepository.claimForProcessing(rcptnSn)).isEqualTo(1);
    }

    @Test
    @DisplayName("FAILED가_아닌_행은_재큐되지_않고_0을_반환한다")
    void FAILED가_아닌_행은_재큐되지_않고_0을_반환한다() {
        // given — 처리 중(PROCESSING)인 행을 뺏거나 성공 종결(DONE)을 되살리면 중복 적재가 된다.
        String pending = clip("REQUEUE-PENDING");
        String processing = clip("REQUEUE-PROCESSING");
        String done = clip("REQUEUE-DONE");
        seedMinimalIngest(pending, LocalDateTime.now(), LsDataIngest.PRCS_STTS_PENDING);
        seedMinimalIngest(processing, LocalDateTime.now(), LsDataIngest.PRCS_STTS_PROCESSING);
        seedMinimalIngest(done, LocalDateTime.now(), LsDataIngest.PRCS_STTS_DONE);

        // when / then — 술어(FAILED)가 맞지 않으면 0행이다
        assertThat(ingestRepository.requeueFailedForRetry(
                ingestRepository.findByVmsClipId(pending).orElseThrow().getRcptnSn()))
                .as("이미 PENDING").isZero();
        assertThat(ingestRepository.requeueFailedForRetry(
                ingestRepository.findByVmsClipId(processing).orElseThrow().getRcptnSn()))
                .as("처리 중인 행 탈취 금지").isZero();
        assertThat(ingestRepository.requeueFailedForRetry(
                ingestRepository.findByVmsClipId(done).orElseThrow().getRcptnSn()))
                .as("성공 종결 되살리기 금지").isZero();

        // then — 상태가 훼손되지 않았다
        assertThat(jdbc.queryForObject(
                "SELECT prcs_stts_cd FROM ls_data_ingest WHERE vms_clip_id = ?", String.class, processing))
                .isEqualTo(LsDataIngest.PRCS_STTS_PROCESSING);
        assertThat(jdbc.queryForObject(
                "SELECT prcs_stts_cd FROM ls_data_ingest WHERE vms_clip_id = ?", String.class, done))
                .isEqualTo(LsDataIngest.PRCS_STTS_DONE);

        // when / then — 존재하지 않는 PK 도 0행(예외가 아니라 재큐 실패로 다룬다)
        assertThat(ingestRepository.requeueFailedForRetry(-1L)).isZero();
    }

    @Test
    @DisplayName("동시에_같은_행을_재큐하면_하나만_성공한다")
    void 동시에_같은_행을_재큐하면_하나만_성공한다() {
        // given — 재큐는 조건부 UPDATE 다. 두 번 성공하면 RTY_CNT 가 이중 증가하고 폴링에 중복 노출된다.
        String clipId = clip("REQUEUE-RACE");
        seedMinimalIngest(clipId, LocalDateTime.now(), LsDataIngest.PRCS_STTS_FAILED);
        Long rcptnSn = ingestRepository.findByVmsClipId(clipId).orElseThrow().getRcptnSn();

        // when
        int first = ingestRepository.requeueFailedForRetry(rcptnSn);
        int second = ingestRepository.requeueFailedForRetry(rcptnSn);

        // then — 술어 재평가로 한쪽만 1행(CWE-362)
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(rtyCntOf(clipId)).isEqualTo(1);
    }

    @Test
    @DisplayName("일괄_재큐는_상한을_넘겨_되살리지_않는다")
    void 일괄_재큐는_상한을_넘겨_되살리지_않는다() {
        // given — 대량 오설정(마운트 루트 불일치)으로 종결된 행 5건.
        //   회수 순서가 <오래된 수신일시 순>이라, 다른 테스트가 커밋해 둔 잔여 FAILED 행이 있어도
        //   이 5건이 먼저 선택되도록 충분히 과거의 수신일시를 쓴다(테스트 격리).
        LocalDateTime base = LocalDateTime.now().minusYears(5);
        for (int i = 0; i < 5; i++) {
            seedMinimalIngest(clip("BULK-" + i), base.plusMinutes(i), LsDataIngest.PRCS_STTS_FAILED);
        }

        // when — 상한 2건으로 일괄 재큐
        int requeued = ingestRepository.requeueFailedBatch(2);

        // then — ★무제한 갱신 금지(CWE-770). 상한만큼만 되살린다.
        assertThat(requeued).as("상한 준수").isEqualTo(2);

        // then — 오래된 수신일시 순으로 회수된다(결정적 순서 — 굶는 행이 없다)
        assertThat(pendingClipIds()).containsExactly(clip("BULK-0"), clip("BULK-1"));

        // then — 남은 3건은 그대로 FAILED 다(재호출로 이어서 회수)
        for (int i = 2; i < 5; i++) {
            assertThat(jdbc.queryForObject(
                    "SELECT prcs_stts_cd FROM ls_data_ingest WHERE vms_clip_id = ?",
                    String.class, clip("BULK-" + i)))
                    .isEqualTo(LsDataIngest.PRCS_STTS_FAILED);
        }
    }

    @Test
    @DisplayName("일괄_재큐는_FAILED가_아닌_행을_건드리지_않는다")
    void 일괄_재큐는_FAILED가_아닌_행을_건드리지_않는다() {
        // given — 처리 중 행을 뺏거나 성공 종결을 되살리면 중복 적재가 된다.
        String processing = clip("BULK-PROCESSING");
        String done = clip("BULK-DONE");
        seedMinimalIngest(processing, LocalDateTime.now(), LsDataIngest.PRCS_STTS_PROCESSING);
        seedMinimalIngest(done, LocalDateTime.now(), LsDataIngest.PRCS_STTS_DONE);

        // when — 상한을 넉넉히 줘도 대상은 FAILED 뿐이다
        ingestRepository.requeueFailedBatch(500);

        // then
        assertThat(jdbc.queryForObject(
                "SELECT prcs_stts_cd FROM ls_data_ingest WHERE vms_clip_id = ?", String.class, processing))
                .isEqualTo(LsDataIngest.PRCS_STTS_PROCESSING);
        assertThat(jdbc.queryForObject(
                "SELECT prcs_stts_cd FROM ls_data_ingest WHERE vms_clip_id = ?", String.class, done))
                .isEqualTo(LsDataIngest.PRCS_STTS_DONE);
    }

    /** 현재 PENDING 폴링 후보 중 이 클래스가 시드한 클립 ID 목록. */
    private List<String> pendingClipIds() {
        return ingestRepository.findPendingReadyForPolling(LocalDateTime.now(), PageRequest.of(0, 500)).stream()
                .map(LsDataIngest::getVmsClipId)
                .filter(id -> id.startsWith(CLIP_PREFIX))
                .toList();
    }

    private int rtyCntOf(String clipId) {
        Integer rtyCnt = jdbc.queryForObject(
                "SELECT rty_cnt FROM ls_data_ingest WHERE vms_clip_id = ?", Integer.class, clipId);
        return rtyCnt == null ? -1 : rtyCnt;
    }

    @Test
    @DisplayName("클레임_성공후_markDone하면_종결상태가_DB에_남는다")
    void 클레임_성공후_markDone하면_종결상태가_DB에_남는다() {
        // given — 클레임은 영속성 컨텍스트를 우회하므로 로드된 엔티티의 상태값은 PENDING 인 채 남는다
        String clipId = clip("CLAIM-THEN-DONE");
        seedMinimalIngest(clipId, LocalDateTime.now(), LsDataIngest.PRCS_STTS_PENDING);
        LsDataIngest ingest = ingestRepository.findByVmsClipId(clipId).orElseThrow();
        assertThat(ingestRepository.claimForProcessing(ingest.getRcptnSn())).isEqualTo(1);
        assertThat(ingest.getPrcsSttsCd())
                .as("착수 판정은 반환값으로만 — 엔티티 상태값은 stale 이다")
                .isEqualTo(LsDataIngest.PRCS_STTS_PENDING);

        // when — 클레임을 얻은 실행이 그대로 종결시킨다
        ingest.markDone(555_666L);
        em.flush();

        // then — stale 스냅샷이 있어도 종결은 절대값으로 덮어써 정상 반영된다
        //   (clearAutomatically 를 켰다면 엔티티가 detach 돼 이 flush 가 조용히 유실된다)
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT prcs_stts_cd, raw_sn FROM ls_data_ingest WHERE vms_clip_id = ?", clipId);
        assertThat(row.get("prcs_stts_cd")).isEqualTo(LsDataIngest.PRCS_STTS_DONE);
        assertThat(((Number) row.get("raw_sn")).longValue()).isEqualTo(555_666L);
    }

    @Test
    @DisplayName("markDone_호출시_상태와_RAW_SN과_처리일시가_DB에_반영된다")
    void markDone_호출시_상태와_RAW_SN과_처리일시가_DB에_반영된다() {
        // given
        String clipId = clip("DONE-TX");
        seedMinimalIngest(clipId, LocalDateTime.now(), LsDataIngest.PRCS_STTS_PENDING);
        LsDataIngest ingest = ingestRepository.findByVmsClipId(clipId).orElseThrow();

        // when — 적재 성공 종결
        ingest.markDone(987_654L);
        em.flush();

        // then — 폴링 대상에서 빠지고 적재 결과가 역추적 가능하게 남는다
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT prcs_stts_cd, raw_sn, prcs_dt FROM ls_data_ingest WHERE vms_clip_id = ?", clipId);
        assertThat(row.get("prcs_stts_cd")).isEqualTo(LsDataIngest.PRCS_STTS_DONE);
        assertThat(((Number) row.get("raw_sn")).longValue()).isEqualTo(987_654L);
        assertThat(row.get("prcs_dt")).isNotNull();
    }

    @Test
    @DisplayName("markFailed_호출시_재시도횟수가_증가하고_에러메시지가_정제되어_DB에_저장된다")
    void markFailed_호출시_재시도횟수가_증가하고_에러메시지가_정제되어_DB에_저장된다() {
        // given
        String clipId = clip("FAIL-TX");
        seedMinimalIngest(clipId, LocalDateTime.now(), LsDataIngest.PRCS_STTS_PENDING);
        LsDataIngest ingest = ingestRepository.findByVmsClipId(clipId).orElseThrow();

        // when — 개행이 섞인 사유로 실패 종결 (CWE-117)
        ingest.markFailed("적재 실패" + (char) 0x0D + (char) 0x0A + "INFO 위조 라인");
        em.flush();

        // then — DB 에 저장된 값 자체에 개행이 없다
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT prcs_stts_cd, rty_cnt, err_msg FROM ls_data_ingest WHERE vms_clip_id = ?", clipId);
        assertThat(row.get("prcs_stts_cd")).isEqualTo(LsDataIngest.PRCS_STTS_FAILED);
        assertThat(((Number) row.get("rty_cnt")).intValue()).isEqualTo(1);
        assertThat((String) row.get("err_msg")).isEqualTo("적재 실패INFO 위조 라인");
    }

    @Test
    @DisplayName("상태전이_flush가_관제_수신값을_stale값으로_덮지_않는다")
    void 상태전이_flush가_관제_수신값을_stale값으로_덮지_않는다() {
        // given — 우리가 인입 행을 로드한 뒤(스냅샷 확보)
        String clipId = clip("NO-CLOBBER");
        seedFullIngest(clipId, LocalDateTime.of(2026, 7, 31, 13, 45, 12));
        LsDataIngest ingest = ingestRepository.findByVmsClipId(clipId).orElseThrow();
        assertThat(ingest.getCctvNm()).isEqualTo("유성구 어은동 사거리");

        // and — 관제가 같은 행의 수신 컬럼을 갱신했다(영속성 컨텍스트 우회 벌크 UPDATE)
        em.createQuery("UPDATE LsDataIngest i SET i.cctvNm = :nm WHERE i.vmsClipId = :clip")
                .setParameter("nm", "교체된 CCTV명")
                .setParameter("clip", clipId)
                .executeUpdate();

        // when — 우리는 운영 컬럼만 전이하고 flush
        ingest.markDone(123L);
        em.flush();

        // then — @DynamicUpdate 로 SET 절에 관제 컬럼이 포함되지 않아 관제 값이 보존된다
        //         (정적 UPDATE 면 stale 스냅샷으로 되돌아가 RED)
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT cctv_nm, prcs_stts_cd FROM ls_data_ingest WHERE vms_clip_id = ?", clipId);
        assertThat(row.get("cctv_nm")).isEqualTo("교체된 CCTV명");
        assertThat(row.get("prcs_stts_cd")).isEqualTo(LsDataIngest.PRCS_STTS_DONE);
    }

    // ======================== 좀비 회수 (DEV_FIX 2차 [B]) ========================

    @Test
    @DisplayName("오래_PROCESSING에_머문_행은_회수되어_PENDING으로_돌아온다 — 클레임_노드가_죽어도_끝이_있다")
    void 오래_PROCESSING에_머문_행은_회수되어_PENDING으로_돌아온다() {
        // given — 클레임까지는 됐는데 종결을 찍기 전에 그 노드가 죽었다(롤링 재기동·OOM).
        //   폴링 술어는 PENDING 이라 다시 집지 않고, 재큐는 FAILED 전용이라 손이 닿지 않는다.
        String clipId = clip("ZOMBIE");
        LocalDateTime old = LocalDateTime.now().minusHours(6);
        seedMinimalIngest(clipId, old, LsDataIngest.PRCS_STTS_PENDING);
        Long rcptnSn = ingestRepository.findByVmsClipId(clipId).orElseThrow().getRcptnSn();
        // 대기 예산 앵커와 실패 이력을 미리 만들어 둔다 — 회수가 이 값들을 훼손하면 안 된다.
        jdbc.update("UPDATE ls_data_ingest SET prcs_dt = ?, rty_cnt = 2, err_msg = '이전 사유'"
                + " WHERE rcptn_sn = ?", Timestamp.valueOf(old), rcptnSn);
        assertThat(ingestRepository.claimForProcessing(rcptnSn)).isEqualTo(1);

        // when — 임계값 경과 후 회수
        int reclaimed = ingestRepository.reclaimStaleProcessing(
                LocalDateTime.now().minusHours(2), 100);

        // then — 되돌아왔다
        assertThat(reclaimed).as("회수된 행 수").isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT prcs_stts_cd, prcs_dt, rty_cnt, err_msg, nxtm_rtry_dt, vms_cctv_id"
                        + " FROM ls_data_ingest WHERE rcptn_sn = ?", rcptnSn);
        assertThat(row.get("prcs_stts_cd")).isEqualTo(LsDataIngest.PRCS_STTS_PENDING);
        // then — ★대기 예산 앵커·실패 이력·관제 수신값을 건드리지 않는다(회수가 상한을 무력화하지 않는다)
        assertThat(((Timestamp) row.get("prcs_dt")).toLocalDateTime()).isEqualTo(old);
        assertThat(((Number) row.get("rty_cnt")).intValue()).as("실패 이력 불변").isEqualTo(2);
        assertThat(row.get("err_msg")).isEqualTo("이전 사유");
        assertThat(row.get("vms_cctv_id")).isEqualTo("CCTV-INGEST-01");

        // then — 회수된 행은 곧바로 폴링 후보다(NXTM_RTRY_DT 가 비어 있거나 과거다)
        assertThat(ingestRepository.findPendingReadyForPolling(
                LocalDateTime.now(), PageRequest.of(0, 500)).stream()
                .map(LsDataIngest::getVmsClipId))
                .contains(clipId);
    }

    @Test
    @DisplayName("방금_클레임한_행은_회수되지_않는다 — 살아_있는_처리를_뺏지_않는다")
    void 방금_클레임한_행은_회수되지_않는다() {
        // given — 지금 막 수신되어 지금 클레임된 행(정상 처리 중)
        String clipId = clip("ALIVE");
        seedMinimalIngest(clipId, LocalDateTime.now(), LsDataIngest.PRCS_STTS_PENDING);
        Long rcptnSn = ingestRepository.findByVmsClipId(clipId).orElseThrow().getRcptnSn();
        assertThat(ingestRepository.claimForProcessing(rcptnSn)).isEqualTo(1);

        // when — 임계값(2시간) 기준 회수 시도
        int reclaimed = ingestRepository.reclaimStaleProcessing(
                LocalDateTime.now().minusHours(2), 100);

        // then — 대상이 아니다(경과 미달)
        assertThat(reclaimed).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT prcs_stts_cd FROM ls_data_ingest WHERE rcptn_sn = ?", String.class, rcptnSn))
                .isEqualTo(LsDataIngest.PRCS_STTS_PROCESSING);
    }

    @Test
    @DisplayName("최근_재시도예정이_찍힌_행은_수신일시가_오래돼도_회수되지_않는다 — 앵커_우선순위")
    void 최근_재시도예정이_찍힌_행은_회수되지_않는다() {
        // given — 수신일시는 오래됐지만(관제가 과거 시각으로 INSERT 하는 형상 포함) 방금 미도착
        //   복귀로 NXTM_RTRY_DT 가 찍혔고 곧바로 다시 클레임된 행
        String clipId = clip("ANCHOR-FRESH");
        seedMinimalIngest(clipId, LocalDateTime.now().minusDays(3), LsDataIngest.PRCS_STTS_PENDING);
        Long rcptnSn = ingestRepository.findByVmsClipId(clipId).orElseThrow().getRcptnSn();
        jdbc.update("UPDATE ls_data_ingest SET nxtm_rtry_dt = ? WHERE rcptn_sn = ?",
                Timestamp.valueOf(LocalDateTime.now()), rcptnSn);
        assertThat(ingestRepository.claimForProcessing(rcptnSn)).isEqualTo(1);

        // when / then — 최신 앵커(NXTM_RTRY_DT)가 우선하므로 회수 대상이 아니다
        assertThat(ingestRepository.reclaimStaleProcessing(
                LocalDateTime.now().minusHours(2), 100)).isZero();
    }

    @Test
    @DisplayName("PROCESSING이_아닌_행은_회수되지_않는다 — 종결된_행을_되살리지_않는다")
    void PROCESSING이_아닌_행은_회수되지_않는다() {
        // given — 오래된 PENDING / DONE / FAILED
        LocalDateTime old = LocalDateTime.now().minusDays(2);
        String pending = clip("KEEP-PENDING");
        String done = clip("KEEP-DONE");
        String failed = clip("KEEP-FAILED");
        seedMinimalIngest(pending, old, LsDataIngest.PRCS_STTS_PENDING);
        seedMinimalIngest(done, old, LsDataIngest.PRCS_STTS_DONE);
        seedMinimalIngest(failed, old, LsDataIngest.PRCS_STTS_FAILED);

        // when
        int reclaimed = ingestRepository.reclaimStaleProcessing(LocalDateTime.now(), 100);

        // then — 하나도 건드리지 않는다
        assertThat(reclaimed).isZero();
        assertThat(jdbc.queryForObject("SELECT prcs_stts_cd FROM ls_data_ingest WHERE vms_clip_id = ?",
                String.class, done)).isEqualTo(LsDataIngest.PRCS_STTS_DONE);
        assertThat(jdbc.queryForObject("SELECT prcs_stts_cd FROM ls_data_ingest WHERE vms_clip_id = ?",
                String.class, failed)).isEqualTo(LsDataIngest.PRCS_STTS_FAILED);
    }

    @Test
    @DisplayName("회수는_상한을_넘겨_되살리지_않는다 — 무제한_갱신_금지")
    void 회수는_상한을_넘겨_되살리지_않는다() {
        // given — 좀비 3건
        LocalDateTime old = LocalDateTime.now().minusDays(1);
        for (int i = 1; i <= 3; i++) {
            String clipId = clip("ZOMBIE-LIMIT-" + i);
            seedMinimalIngest(clipId, old.plusMinutes(i), LsDataIngest.PRCS_STTS_PENDING);
            Long sn = ingestRepository.findByVmsClipId(clipId).orElseThrow().getRcptnSn();
            assertThat(ingestRepository.claimForProcessing(sn)).isEqualTo(1);
        }

        // when — 상한 2
        int reclaimed = ingestRepository.reclaimStaleProcessing(LocalDateTime.now(), 2);

        // then — 상한만큼만. 잔여는 다음 tick 이 이어서 회수한다
        assertThat(reclaimed).isEqualTo(2);
        assertThat(ingestRepository.reclaimStaleProcessing(LocalDateTime.now(), 2)).isEqualTo(1);
    }

    private String clip(String suffix) {
        return CLIP_PREFIX + suffix + "-" + runId;
    }

    /** 관제가 넣는 최소 형상 — NOT NULL 수신 5컬럼 + 폴링 축(수신일시·처리상태). */
    private void seedMinimalIngest(String clipId, LocalDateTime rcptnDt, String prcsSttsCd) {
        jdbc.update("""
                INSERT INTO ls_data_ingest
                    (vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, src_type,
                     rcptn_dt, prcs_stts_cd)
                VALUES (?, 'CCTV-INGEST-01', 'clip.mp4', '/nas-storage/raw/clip.mp4', 'RELAY', ?, ?)
                """, clipId, Timestamp.valueOf(rcptnDt), prcsSttsCd);
    }

    /** 관제 수신 컬럼을 전부 채운 형상 — 컬럼별 매핑(타입·길이) 왕복 검증용. */
    private void seedFullIngest(String clipId, LocalDateTime shtDt) {
        jdbc.update("""
                INSERT INTO ls_data_ingest
                    (vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, src_type, sht_dt,
                     file_fmt, vdo_cdc, file_sz, lclgv_nm, vdo_len_sec, fps, frme_cnt, asprt_rt,
                     wdth, vrtc, resl, bit, pxl, wgs84_lat, wgs84_lot, cctv_nm, cctv_hgt,
                     main_surv_pan_ang, evnt_id, evnt_nm, mntr_cn, lclgv_cd, evnt_type_cd,
                     anony_incl_yn, psdo_incl_yn, prvc_incl_yn)
                VALUES (?, 'CCTV-INGEST-01', 'clip.mp4', '/nas-storage/raw/clip.mp4', 'RELAY', ?,
                        'mp4', 'h264', ?, ?, 30, '30', 900, '16:9',
                        1920, 1080, 'FHD', '2050627', '4K', ?, ?, ?, ?,
                        135, 'ABA_0001', ?, ?, '3020000000', 'INTRUSION',
                        'N', 'N', 'Y')
                """,
                clipId, Timestamp.valueOf(shtDt), 4_915_200L, "대전광역시 유성구",
                new BigDecimal("36.3504119"), new BigDecimal("127.3845475"),
                "유성구 어은동 사거리", new BigDecimal("4.5"), "배회", "관제일지 내용");
    }
}
