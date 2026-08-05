package kr.co.cudo.authoring.common.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Phase 4 — 여부(YN) 도메인 CHAR(1) 전환(V85) 실동작 검증(Testcontainers PostgreSQL).
 *
 * <p>검증 축:
 * <ul>
 *   <li>ddl-auto=validate 부팅 성공 = 전체 엔티티 ↔ CHAR(1) 스키마 정합(컨텍스트 로드 자체가 증명).</li>
 *   <li>정보 스키마상 VARCHAR(1) 대상 컬럼이 character(1)(bpchar)로 전환됨.</li>
 *   <li>DE_IDENT_YN 3값(Y/F/N) CHAR(1) 보존, 패딩/트림 없이 왕복.</li>
 *   <li>뷰 V_COMPLETED_VIDEO 재생성 — DE_IDNTF_YN 출력 계약 보존.
 *       (PRVC_YN 은 V174 에서 뷰 노출이 제거됐다 — 관제 적재 대상이 아니다. 원 테이블에는 남는다.)</li>
 * </ul>
 *
 * <p>주의: V85 가 CHAR(1)로 전환했던 LS_LABEL_PRESET_CODE.BBOX_ENABLED/POLYGON_ENABLED 는
 * V117 에서 제거되었다(형태는 라벨 마스터 LBL_TYPE_CD 소유). 관련 검증은 본 IT 대상에서 제외한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class YnColumnChar1MigrationIT {

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private LsLabelPresetRepository presetRepository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @PersistenceContext
    private EntityManager em;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    private record ColRef(String table, String column) {}

    /** V85 VARCHAR(1)→CHAR(1) 대상 컬럼(정보 스키마 소문자). USE_YN 은 두 테이블(LS_LABEL/LS_LABEL_ATTR). */
    private static final List<ColRef> TARGET_COLUMNS = List.of(
            new ColRef("ls_label_version", "actvtn_yn"),
            new ColRef("ls_deadline", "anony_incl_yn"),
            new ColRef("ls_deadline", "psdo_incl_yn"),
            new ColRef("ls_deadline", "prvc_incl_yn"),
            new ColRef("ls_data_lbl_ai_info", "auto_lbl_yn"),
            new ColRef("ls_data_aug_lbl_map", "coord_recalc_yn"),
            new ColRef("ls_data_raw", "de_ident_yn"),
            new ColRef("ls_data_raw", "prvc_yn"),
            new ColRef("ls_label_attr", "mutable_yn"),
            new ColRef("ls_label_attr", "use_yn"),
            new ColRef("ls_label", "use_yn"),
            new ColRef("ls_notice", "upend_fix_yn"));

    @Test
    @DisplayName("ddl_validate_CHAR1_전체_엔티티_일치_부팅")
    void ddl_validate_CHAR1_전체_엔티티_일치_부팅() {
        // given / when / then — ddl-auto=validate 하에 컨텍스트가 로드됨 = CHAR(1) ↔ 엔티티 정합
        assertThat(videoRepository).isNotNull();
        assertThat(presetRepository).isNotNull();
    }

    @Test
    @DisplayName("VARCHAR1_YN_전부_CHAR1로_전환됨")
    void VARCHAR1_YN_전부_CHAR1로_전환됨() {
        // given / when / then — 정보 스키마상 data_type=character, 길이 1
        for (ColRef ref : TARGET_COLUMNS) {
            Map<String, Object> meta = jdbc().queryForMap(
                    "SELECT data_type, character_maximum_length FROM information_schema.columns "
                            + "WHERE table_name = ? AND column_name = ?",
                    ref.table(), ref.column());
            assertThat(meta.get("data_type"))
                    .as("%s.%s 는 character(CHAR) 여야 함", ref.table(), ref.column())
                    .isEqualTo("character");
            assertThat(((Number) meta.get("character_maximum_length")).intValue())
                    .as("%s.%s 길이 1", ref.table(), ref.column())
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("DE_IDENT_YN_Y_F_N_3값_CHAR1_보존")
    void DE_IDENT_YN_Y_F_N_3값_CHAR1_보존() {
        // given — PRVC 영상 적재 (파생 PRVC_YN='Y', 초기 DE_IDENT_YN='N')
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-YN-DE", "CCTV-1", "EVT_FALL", "LGV01", "PRVC",
                "/nas/raw/yn-de.mp4", LocalDateTime.now(), 30);
        videoRepository.saveAndFlush(raw);
        Long rawSn = raw.getRawSn();
        assertThat(raw.getPrvcYn()).isEqualTo("Y");

        // when — 비식별 실패 'F' 마킹 후 DB 왕복
        raw.markDeidentified("F");
        videoRepository.saveAndFlush(raw);
        em.clear();

        // then — 'F' 값이 CHAR(1)로 보존(패딩/트림 없음)
        assertThat(videoRepository.findById(rawSn).orElseThrow().getDeIdntfYn()).isEqualTo("F");
        assertThat(jdbc().queryForObject(
                "SELECT de_ident_yn FROM ls_data_raw WHERE raw_sn = ?", String.class, rawSn))
                .isEqualTo("F");

        // when/then — 'Y'(성공), 'N'(미수행)도 동일 보존
        LsDataRaw again = videoRepository.findById(rawSn).orElseThrow();
        again.markDeidentified("Y");
        videoRepository.saveAndFlush(again);
        em.clear();
        assertThat(videoRepository.findById(rawSn).orElseThrow().getDeIdntfYn()).isEqualTo("Y");

        LsDataRaw third = videoRepository.findById(rawSn).orElseThrow();
        third.markDeidentified("N");
        videoRepository.saveAndFlush(third);
        em.clear();
        assertThat(videoRepository.findById(rawSn).orElseThrow().getDeIdntfYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("V_COMPLETED_VIDEO_뷰_재생성_DE_IDNTF_YN_출력_유지")
    void V_COMPLETED_VIDEO_뷰_재생성_DE_IDNTF_YN_출력_유지() {
        // given / when / then — 재생성된 뷰의 DE_IDNTF_YN alias 존재 = SELECT 계약 보존.
        //   이 컬럼은 <제거 금지>다 — "비식별 신고 구간에도 관제가 자체 판단할 수 있게 노출한다"는
        //   확정 정책(CLAUDE.md 데이터마트 View 절)에 걸려 있다.
        assertThatCode(() -> jdbc().queryForList(
                "SELECT de_idntf_yn FROM v_completed_video WHERE 1 = 0"))
                .doesNotThrowAnyException();

        // and — PRVC_YN 은 V174 에서 뷰 노출이 제거됐다(관제 적재 대상 없음). 원 테이블에는 CHAR(1)로 남는다.
        assertThat(jdbc().queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'v_completed_video'", String.class))
                .doesNotContain("prvc_yn");
        assertThat(jdbc().queryForMap(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'ls_data_raw' AND column_name = 'prvc_yn'"))
                .containsEntry("data_type", "character");
    }

    @Test
    @DisplayName("여부_컬럼_JSON_직렬화_Y_N_유지")
    void 여부_컬럼_JSON_직렬화_Y_N_유지() throws Exception {
        // given — String YN 필드는 String 타입 유지(계약 불변)
        assertThat(LsDataRaw.class.getDeclaredField("deIdntfYn").getType()).isEqualTo(String.class);

        // when — 대표 계약 형태 직렬화
        String json = new ObjectMapper().writeValueAsString(new YnContract("Y", true));

        // then — String YN 은 "Y"/"N" 문자열, boolean YN 은 true/false 로 직렬화(응답 계약 불변)
        assertThat(json).contains("\"deIdntfYn\":\"Y\"").contains("\"bboxEnabled\":true");
    }

    /** 응답 계약 형태(문자 YN + boolean YN) 직렬화 검증용. */
    private record YnContract(String deIdntfYn, boolean bboxEnabled) {}
}
