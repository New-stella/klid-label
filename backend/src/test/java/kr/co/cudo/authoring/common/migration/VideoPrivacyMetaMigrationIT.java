package kr.co.cudo.authoring.common.migration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

/**
 * V163 — 영상 단위 개인정보(익명·가명·개인정보 포함여부) 수동입력 컬럼 신설 실동작 검증
 * (Testcontainers PostgreSQL, Flyway migrate 후 부팅).
 *
 * <p><b>왜 정보 스키마를 직접 대조하나</b>: 이 프로젝트의 {@code ddl-auto=validate} 는 실제로 동작하지
 * 않는다({@code JpaBuilderConfig} 가 {@code spring.jpa.hibernate.*} 를 EMF 에 넘기지 않아 없는 컬럼을
 * 매핑해도 기동이 성공한다). 따라서 "기동 성공"을 엔티티↔DDL 정합의 증거로 삼을 수 없으므로 신규
 * 컬럼의 물리명·타입·크기·NULL 허용을 {@code information_schema} 로 1:1 대조하고, 값 왕복까지 확인한다.
 *
 * <p>검증 축: 표준도메인 정합(여부 = CHAR(1)) · NULL 허용(미입력/프리필 구분) · CHAR(1) 값 왕복 ·
 * 도메인 메서드({@link LsDataRaw#changePrivacyMeta}) 저장 반영.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class VideoPrivacyMetaMigrationIT {

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @PersistenceContext
    private EntityManager em;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    /** V163 신규 컬럼 표준 확정값(정보 스키마 소문자). 여부(YN) 도메인 = 공통표준도메인 여부C1 = CHAR(1). */
    private static final List<String> NEW_COLUMNS =
            List.of("anony_incl_yn", "psdo_incl_yn", "prvc_incl_yn");

    @Test
    @DisplayName("V163_신규3컬럼이_CHAR1_NULL허용으로_생성된다")
    void V163_신규3컬럼이_CHAR1_NULL허용으로_생성된다() {
        for (String column : NEW_COLUMNS) {
            Map<String, Object> meta = jdbc().queryForMap(
                    "SELECT data_type, character_maximum_length, is_nullable, column_default "
                            + "FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                    "ls_data_raw", column);
            assertThat(meta.get("data_type")).as("ls_data_raw.%s 타입", column).isEqualTo("character");
            assertThat(((Number) meta.get("character_maximum_length")).intValue())
                    .as("ls_data_raw.%s 길이", column).isEqualTo(1);
            // NULL = 미입력. DEFAULT 가 있으면 '미입력'과 '사람의 판정'을 구분할 수 없다.
            assertThat(meta.get("is_nullable")).as("ls_data_raw.%s NULL 허용", column).isEqualTo("YES");
            assertThat(meta.get("column_default")).as("ls_data_raw.%s DEFAULT 없음", column).isNull();
        }
    }

    @Test
    @DisplayName("영상_개인정보_3컬럼은_적재기본값으로_시작하고_저장후_값이_유지된다 (구 기대 'null 기본' 폐기 — 2026-08-04)")
    void 영상_개인정보_3컬럼은_미입력시_null이고_저장후_값이_유지된다() {
        // given — 영상 적재(신규 개인정보 컬럼은 미입력 상태)
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-VIDEO-PRIVACY", "CCTV-PRV", "EVT_FALL", "LGV01", "PRVC",
                "/nas/raw/video-privacy.mp4", LocalDateTime.now(), 30);
        videoRepository.saveAndFlush(raw);
        Long rawSn = raw.getRawSn();

        // then — ★ 구 기대("미입력 기본은 null") 폐기: 비식별 축 3필드는 <적재 시점>에 Y/N/N 이 실제로
        //   들어간다(2026-08-04 사용자 확정, LsDataRaw @Builder 생성자). 컬럼은 여전히 nullable 이고
        //   레거시 행·신고 리셋… 이 아니라 <레거시 행>에만 null 이 남는다(신고는 더 이상 리셋하지 않는다).
        assertThat(raw.getAnonyInclYn()).isEqualTo("Y");
        assertThat(raw.getPsdoInclYn()).isEqualTo("N");
        assertThat(raw.getPrvcInclYn()).isEqualTo("N");

        // when — 도메인 메서드로 수동 판정 저장(CHAR(1) 왕복)
        raw.changePrivacyMeta("N", "Y", "Y");
        videoRepository.flush();
        em.clear();

        // then — 공백 패딩/트림 없이 값 유지
        LsDataRaw reloaded = videoRepository.findById(rawSn).orElseThrow();
        assertThat(reloaded.getAnonyInclYn()).isEqualTo("N");
        assertThat(reloaded.getPsdoInclYn()).isEqualTo("Y");
        assertThat(reloaded.getPrvcInclYn()).isEqualTo("Y");

        // when — 전체 교체로 수동값 삭제(null)
        reloaded.changePrivacyMeta(null, null, null);
        videoRepository.flush();
        em.clear();

        // then — 미입력 상태로 복귀
        LsDataRaw cleared = videoRepository.findById(rawSn).orElseThrow();
        assertThat(cleared.getAnonyInclYn()).isNull();
        assertThat(cleared.getPsdoInclYn()).isNull();
        assertThat(cleared.getPrvcInclYn()).isNull();
    }
}
