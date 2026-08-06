package kr.co.cudo.authoring.common.migration;

import jakarta.persistence.Column;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * V176 — {@code LS_DATA_INGEST.VRFC_EVNT_TYPE_CD}(검증이벤트유형코드) 신설 실동작 검증
 * (Testcontainers PostgreSQL, Flyway migrate 후 부팅).
 *
 * <h3>왜 정보 스키마를 직접 대조하나</h3>
 * <p>이 프로젝트의 {@code ddl-auto=validate} 는 실제로 동작하지 않는다({@code JpaBuilderConfig} 가
 * {@code spring.jpa.hibernate.*} 를 EMF 에 넘기지 않아 <b>없는 컬럼을 매핑해도 기동이 성공</b>한다).
 * 따라서 "기동 성공"을 엔티티↔DDL 정합의 증거로 삼을 수 없다({@code IngestReceiveColumnsMigrationIT}
 * 와 동일 골격).
 *
 * <h3>검증 축</h3>
 * <ol>
 *   <li><b>물리명·타입·크기</b> — {@code VRFC_EVNT_TYPE_CD VARCHAR(20)}(코드V20). 검증=행안부
 *       공통표준단어 {@code VRFC}.</li>
 *   <li><b>엔티티 매핑 정합</b> — {@code @Column(length)} 이 DDL 과 어긋나면 {@code ddl-auto=validate}
 *       가 되살아나는 순간 기동이 깨진다.</li>
 *   <li><b>DB CHECK 제약 없음</b> — 관제는 우리 코드를 거치지 않고 직접 INSERT 하므로 CHECK 를 걸면
 *       관제 INSERT 가 실패한다. 값 검증은 우리 쓰기 통로(TUS 400) + 소비 시점 fail-closed 2단이다.</li>
 *   <li><b>기존 행은 전부 NULL</b> — 백필하지 않는다(관제 송신분부터 채워진다).</li>
 * </ol>
 *
 * @req R5
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class V176VrfcEvntTypeMigrationIT {

    /** 신설 컬럼 물리명(정보 스키마는 소문자로 돌려준다). */
    private static final String COLUMN = "vrfc_evnt_type_cd";

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    @Test
    @DisplayName("V176_검증이벤트유형코드가_코드표준_VARCHAR20_NULL허용으로_생성된다")
    void 검증이벤트유형코드_컬럼이_생성된다() {
        // when
        Map<String, Object> meta = jdbc().queryForMap(
                "SELECT data_type, character_maximum_length, is_nullable, column_default"
                        + " FROM information_schema.columns"
                        + " WHERE table_name = 'ls_data_ingest' AND column_name = ?", COLUMN);

        // then — 코드값 표준도메인(코드V20 = VARCHAR(20)). 최장값 car_accident(12자)를 수용한다.
        assertThat(meta.get("data_type")).isEqualTo("character varying");
        assertThat(((Number) meta.get("character_maximum_length")).intValue()).isEqualTo(20);
        // 관제가 채우기 전까지 null 이다 — 소비 시점(Phase 2)이 null 을 SKIPPED 로 다룬다.
        assertThat(meta.get("is_nullable")).isEqualTo("YES");
        // DEFAULT 를 두면 "관제가 안 보냈다"와 "관제가 그 값을 보냈다"가 영영 구분되지 않는다.
        assertThat(meta.get("column_default")).isNull();
    }

    @Test
    @DisplayName("엔티티_컬럼_길이가_마이그레이션과_일치한다")
    void 엔티티_컬럼_길이가_마이그레이션과_일치한다() {
        // given — 엔티티 매핑
        Field field = Arrays.stream(LsDataIngest.class.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(Column.class))
                .filter(f -> "VRFC_EVNT_TYPE_CD".equals(f.getAnnotation(Column.class).name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("LsDataIngest 에 VRFC_EVNT_TYPE_CD 매핑이 없다"));

        // then — 타입·길이가 DDL 과 1:1 (어긋나면 validate 가 되살아나는 순간 기동 실패)
        assertThat(field.getType()).isEqualTo(String.class);
        assertThat(field.getAnnotation(Column.class).length()).isEqualTo(20);

        Integer actualLength = jdbc().queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns"
                        + " WHERE table_name = 'ls_data_ingest' AND column_name = ?",
                Integer.class, COLUMN);
        assertThat(actualLength).isEqualTo(field.getAnnotation(Column.class).length());
    }

    @Test
    @DisplayName("DB_CHECK_제약을_걸지_않는다 — 관제_직접_INSERT를_막으면_안_된다")
    void CHECK_제약이_없어_관제_직접_INSERT를_막지_않는다() {
        // given — 관제는 우리 코드를 거치지 않고 이 테이블에 직접 INSERT 한다. CHECK 를 걸면
        //   우리가 모르는 값 하나에 관제 인입 전체가 실패한다(값 검증은 우리 쓰기 통로 + 소비 시점).
        String clipId = "CLIP-VRFC-" + System.nanoTime();

        // when / then — allowlist 밖 값이어도 DB 는 거부하지 않는다
        assertThatCode(() -> jdbc().update("""
                INSERT INTO ls_data_ingest
                    (vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, src_type,
                     rcptn_dt, prcs_stts_cd, vrfc_evnt_type_cd)
                VALUES (?, 'CCTV-VRFC', 'v.mp4', '/nas/raw/v.mp4', 'RELAY', now(), 'PENDING',
                        'unknown_type')
                """, clipId)).doesNotThrowAnyException();

        assertThat(jdbc().queryForObject(
                "SELECT vrfc_evnt_type_cd FROM ls_data_ingest WHERE vms_clip_id = ?",
                String.class, clipId)).isEqualTo("unknown_type");
    }

    @Test
    @DisplayName("관제가_안_보내면_NULL로_남는다 — 서버가_보정하지_않는다")
    void 미송신이면_NULL로_남는다() {
        // given / when — 관제가 이 컬럼을 지정하지 않고 INSERT
        String clipId = "CLIP-VRFC-NULL-" + System.nanoTime();
        jdbc().update("""
                INSERT INTO ls_data_ingest
                    (vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, src_type,
                     rcptn_dt, prcs_stts_cd)
                VALUES (?, 'CCTV-VRFC', 'n.mp4', '/nas/raw/n.mp4', 'RELAY', now(), 'PENDING')
                """, clipId);

        // then — null 이 정상 상태다(기존 행 백필도 하지 않는다)
        assertThat(jdbc().queryForObject(
                "SELECT vrfc_evnt_type_cd FROM ls_data_ingest WHERE vms_clip_id = ?",
                String.class, clipId)).isNull();
    }

    @Test
    @DisplayName("허용_6종은_전부_컬럼_길이_안에_들어간다")
    void 허용_6종은_컬럼_길이_안에_들어간다() {
        // 벤더 enum 최장값이 컬럼 길이를 넘으면 적재 시점에 값 초과로 터진다(세션 생성은 통과한 뒤).
        assertThat(LsDataIngest.VRFC_EVNT_TYPES).isNotEmpty();
        assertThat(LsDataIngest.VRFC_EVNT_TYPES)
                .allSatisfy(type -> assertThat(type.length()).isLessThanOrEqualTo(20));
    }
}
