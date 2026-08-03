package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.video.dto.InternalUploadIngestCommand;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;

/**
 * 내부 업로드(REVIEWER TUS) 전용 {@code LS_DATA_INGEST} INSERT — <b>비-관제 유일 통로</b>.
 *
 * <h3>왜 이 클래스가 존재하는가 (예외적 INSERT 경로)</h3>
 * <p>인입 행을 만드는 주체는 원칙적으로 <b>관제</b>다. 저작도구는 읽고 자기 운영 컬럼의 상태만 바꾸며,
 * 그래서 {@link LsDataIngest} 엔티티에는 INSERT 팩토리도 setter 도 없다(생성 통로를 열면 그 자체가
 * 관제 소유 29컬럼의 일반 쓰기 경로가 된다 — CWE-915). 그럼에도 <b>내부 관리 화면 업로드 1개
 * 흐름</b>은 저작도구가 유일하게 정당한 origin 이므로, 그 하나만을 위한 좁은 통로로 이 클래스를 둔다.
 *
 * <h3>범용 writer 가 되지 않게 하는 장치</h3>
 * <ul>
 *   <li><b>고정 컬럼 리스트 + {@code ?} 플레이스홀더만</b> — 컬럼명을 인자로 받는 map/varargs API 를
 *       두지 않는다. 그런 API 는 임의 컬럼 쓰기 경로가 되어 위 계약을 무너뜨린다(SQL 조립 표면도
 *       생기지 않는다 — CWE-89).</li>
 *   <li><b>저작도구 운영 8컬럼은 SQL 에 아예 없다</b>({@code RCPTN_SN}·{@code RCPTN_DT}·{@code RAW_SN}·
 *       {@code RTY_CNT}·{@code PRCS_DT}·{@code NEXT_RTRY_DT}·{@code ERR_MSG}). 인입 폴링 상태머신의
 *       소유값이므로 DB DEFAULT 에 맡긴다. 유일한 예외가 {@code PROC_STTS_CD} 이고, 그것도 고정값
 *       {@code 'PENDING'} 이라 호출자가 상태를 고를 수 없다.</li>
 *   <li><b>입력은 {@link InternalUploadIngestCommand} 하나</b> — 커맨드 자체가 관제 수신 29컬럼만
 *       보유해 나머지는 구조적으로 도달 불가다.</li>
 * </ul>
 *
 * <h3>트랜잭션 — {@link Propagation#REQUIRES_NEW}</h3>
 * <p>UK({@code VMS_CLIP_ID}) 위반이 <b>호출자 트랜잭션(PATCH)을 오염시키지 않게</b> 독립 트랜잭션으로
 * 실행한다. PostgreSQL 은 제약 위반 시 트랜잭션 <b>전체</b>를 abort 하므로, 같은 트랜잭션 안에서
 * {@code DataIntegrityViolationException} 을 catch 해도 이후 어떤 쿼리도 실행할 수 없다
 * ({@code TrainingVideoIngestTx#ingestOne} 과 동일한 이유·패턴). 별도 빈으로 분리해 자기호출로
 * 프록시를 우회하지 않는다.
 */
@Slf4j
@Component
public class InternalUploadIngestWriter {

    /**
     * 고정 컬럼 INSERT — 관제 수신 29컬럼 + 처리상태 1컬럼(고정 {@code 'PENDING'}).
     *
     * <p>{@code BIT} 은 PostgreSQL 에서 컬럼명으로는 무인용 사용이 가능하다(V147 실증). 인용하면
     * 대문자 식별자가 고정돼 나머지 컬럼(무인용→소문자 폴딩)과 규칙이 갈리므로 그대로 둔다.
     */
    private static final String INSERT_SQL = """
            INSERT INTO LS_DATA_INGEST (
                PROC_STTS_CD,
                VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, SRC_TYPE, SHT_DT,
                FILE_FMT, VDO_CDC, FILE_SZ, RGN_NM, VDO_LEN_SEC, FPS, FRM_CNT, ASPRT_RT,
                WDTH, VRTC, RESL, BIT, PXL, WGS84_LAT, WGS84_LOT, OG_CD, CCTV_NM, CCTV_HGT,
                MAIN_SURV_PAN_ANG, EVNT_ID, EVNT_NM, MNTR_CN, LCLGV_CD)
            VALUES (?,
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?)
            """;

    /** IDENTITY 로 발급된 PK 를 회수할 컬럼(무인용 = 소문자 폴딩된 실제 컬럼명). */
    private static final String[] GENERATED_KEY_COLUMNS = {"rcptn_sn"};

    private final JdbcTemplate jdbcTemplate;

    public InternalUploadIngestWriter(@Qualifier("controlDataSource") DataSource controlDataSource) {
        this.jdbcTemplate = new JdbcTemplate(controlDataSource);
    }

    /**
     * 인입 행 1건을 미처리({@code PENDING})로 INSERT 한다 — 이후는 인입 폴링이 관제 인입과 동일하게 처리한다.
     *
     * @return 생성된 인입 행 PK({@code RCPTN_SN})
     * @throws org.springframework.dao.DataIntegrityViolationException UK({@code VMS_CLIP_ID}) 위반 등
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, transactionManager = "controlTransactionManager")
    public Long insertPending(InternalUploadIngestCommand command) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> bind(connection.prepareStatement(
                INSERT_SQL, GENERATED_KEY_COLUMNS), command), keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            // 도달 불가(IDENTITY 컬럼) — 조용히 null 을 흘리면 호출부 로그가 의미를 잃는다.
            throw new IllegalStateException("LS_DATA_INGEST INSERT 후 RCPTN_SN 을 회수하지 못했습니다.");
        }
        return key.longValue();
    }

    /** 고정 순서 바인딩 — SQL 의 컬럼 순서와 1:1 이며 그 외 컬럼은 존재하지 않는다. */
    private static PreparedStatement bind(PreparedStatement ps, InternalUploadIngestCommand c)
            throws SQLException {
        int i = 1;
        ps.setString(i++, LsDataIngest.PROC_STTS_PENDING);
        ps.setString(i++, c.vmsClipId());
        ps.setString(i++, c.vmsCctvId());
        ps.setString(i++, c.vdoFileNm());
        ps.setString(i++, c.rawFilePathNm());
        ps.setString(i++, c.srcType());
        setTimestamp(ps, i++, c.shtDt());
        setString(ps, i++, c.fileFmt());
        setString(ps, i++, c.vdoCdc());
        setLong(ps, i++, c.fileSz());
        setString(ps, i++, c.rgnNm());
        setDecimal(ps, i++, c.vdoLenSec());
        setString(ps, i++, c.fps());
        setDecimal(ps, i++, c.frmCnt());
        setString(ps, i++, c.asprtRt());
        setDecimal(ps, i++, c.wdth());
        setDecimal(ps, i++, c.vrtc());
        setString(ps, i++, c.resl());
        setString(ps, i++, c.bit());
        setString(ps, i++, c.pxl());
        setDecimal(ps, i++, c.wgs84Lat());
        setDecimal(ps, i++, c.wgs84Lot());
        setString(ps, i++, c.ogCd());
        setString(ps, i++, c.cctvNm());
        setDecimal(ps, i++, c.cctvHgt());
        setInt(ps, i++, c.mainSurvPanAng());
        setString(ps, i++, c.evntId());
        setString(ps, i++, c.evntNm());
        setString(ps, i++, c.mntrCn());
        setString(ps, i, c.lclgvCd());
        return ps;
    }

    private static void setString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static void setDecimal(PreparedStatement ps, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NUMERIC);
        } else {
            ps.setBigDecimal(index, value);
        }
    }

    private static void setTimestamp(PreparedStatement ps, int index, LocalDateTime value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, Timestamp.valueOf(value));
        }
    }
}
