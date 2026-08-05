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
 *       {@code RTY_CNT}·{@code PRCS_DT}·{@code NXTM_RTY_DT}·{@code ERR_MSG}). 인입 폴링 상태머신의
 *       소유값이므로 DB DEFAULT 에 맡긴다. 유일한 예외가 {@code PRCS_STTS_CD} 이고, 그것도 고정값
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
                PRCS_STTS_CD,
                VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, SRC_TYPE, SHT_DT,
                FILE_FMT, VDO_CDC, FILE_SZ, LCLGV_NM, VDO_LEN_SEC, FPS, FRME_CNT, ASPRT_RT,
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

    /**
     * <b>업로드 인입 행 되살리기</b> — 종결된 <b>우리 행</b>을 새 메타로 갱신해 {@code PENDING} 으로
     * 되돌린다 (DEV_FIX M1).
     *
     * <h3>왜 필요한가 — 취소한 클립 ID 를 회수할 수단이 없었다</h3>
     * <p>인입 행은 <b>삭제 금지</b>(감사 추적)이고 {@code VMS_CLIP_ID} 는 UK 다. 그래서 세션을
     * 취소·만료로 종결하면 그 클립 ID 는 <b>어떤 API 로도</b> 다시 쓸 수 없었다
     * ({@code requeueFailedForRetry} 는 상태만 되돌릴 뿐 UK 를 풀지 못하고, 그 행에는 세션도 파일도
     * 없어 재큐해 봐야 대기 상한까지 갔다가 재실패한다). 반복 취소만으로 인입 테이블이 무제한
     * 증식하고, 관제가 쓸 클립 ID 를 미리 등록해 두면 <b>관제 INSERT 가 UK 위반으로 실패</b>한다.
     *
     * <p>해법은 <b>지우지 않고 재사용</b>이다 — 같은 PK 를 새 메타로 갱신하므로 UK 위반도 없고 감사
     * 추적(같은 행에 누적된 {@code RTY_CNT})도 남는다.
     *
     * <h3>되살릴 수 있는 행의 조건 (술어로 강제 — fail-closed)</h3>
     * <ul>
     *   <li>{@code PRCS_STTS_CD = 'FAILED'} — 종결된 행만. 처리 중·대기 중인 행을 뺏지 않는다.</li>
     *   <li>{@code RAW_SN IS NULL} — <b>한 번도 적재된 적 없는</b> 행만. 적재된 영상의 인입 근거를
     *       다른 업로드가 덮어쓰면 역추적이 끊긴다.</li>
     * </ul>
     * <p>"우리가 만든 행인가"(경로가 인입 영역 하위인가)는 호출 측
     * ({@code TusUploadService} + {@code InternalUploadPathResolver#isUploadAreaPath})이 판정한다 —
     * 그 기준은 저장 경로 규약이라 SQL 술어로 옮기면 배포 형상(base 경로)을 SQL 에 굳히게 된다.
     *
     * <h3>무엇을 갱신하는가</h3>
     * <ul>
     *   <li>관제 수신 <b>28컬럼</b>({@code VMS_CLIP_ID} 제외 — UK 이자 조회 키라 그대로 둔다).</li>
     *   <li>{@code RCPTN_DT} — 새 업로드의 수신 시각. 갱신하지 않으면 되살린 행이 <b>FIFO 앞자리</b>를
     *       옛 시각으로 계속 점유한다.</li>
     *   <li>{@code ERR_MSG}·{@code PRCS_DT}·{@code NXTM_RTY_DT} 를 비운다 — 이전 종결 사유와 대기
     *       예산 앵커가 남으면 되살린 행이 <b>다음 tick 에 즉시 재종결</b>된다
     *       ({@code requeueFailedForRetry} 와 동일한 이유).</li>
     *   <li>{@code RTY_CNT} 는 건드리지 않는다 — 그 클립이 몇 번 실패했는지의 이력이다.</li>
     * </ul>
     *
     * @return 되살린 행 수 — <b>1 = 성공</b> / <b>0 = 그 사이 상태가 바뀌었다</b>(호출 측 409)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, transactionManager = "controlTransactionManager")
    public int reviveForUpload(Long rcptnSn, InternalUploadIngestCommand command) {
        return jdbcTemplate.update(connection ->
                bindRevive(connection.prepareStatement(REVIVE_SQL), command, rcptnSn));
    }

    /**
     * 되살리기 UPDATE — SET 절은 관제 수신 28컬럼 + 운영 리셋 4컬럼이고 그 외 컬럼은 존재하지 않는다.
     *
     * <p>{@code RAW_SN} 은 SET 에 없다(술어가 이미 null 을 요구한다) — 있으면 적재 결과를 지우는
     * 통로가 열린다.
     */
    private static final String REVIVE_SQL = """
            UPDATE LS_DATA_INGEST
               SET PRCS_STTS_CD = 'PENDING',
                   RCPTN_DT = CURRENT_TIMESTAMP,
                   ERR_MSG = NULL,
                   PRCS_DT = NULL,
                   NXTM_RTY_DT = NULL,
                   VMS_CCTV_ID = ?, VDO_FILE_NM = ?, RAW_FILE_PATH_NM = ?, SRC_TYPE = ?, SHT_DT = ?,
                   FILE_FMT = ?, VDO_CDC = ?, FILE_SZ = ?, LCLGV_NM = ?, VDO_LEN_SEC = ?, FPS = ?,
                   FRME_CNT = ?, ASPRT_RT = ?, WDTH = ?, VRTC = ?, RESL = ?, BIT = ?, PXL = ?,
                   WGS84_LAT = ?, WGS84_LOT = ?, OG_CD = ?, CCTV_NM = ?, CCTV_HGT = ?,
                   MAIN_SURV_PAN_ANG = ?, EVNT_ID = ?, EVNT_NM = ?, MNTR_CN = ?, LCLGV_CD = ?
             WHERE RCPTN_SN = ?
               AND PRCS_STTS_CD = 'FAILED'
               AND RAW_SN IS NULL
            """;

    /** 되살리기 바인딩 — {@code VMS_CLIP_ID} 를 제외한 28컬럼 + 대상 PK. */
    private static PreparedStatement bindRevive(PreparedStatement ps, InternalUploadIngestCommand c,
                                                Long rcptnSn) throws SQLException {
        int i = bindReceivedColumns(ps, 1, c);
        ps.setLong(i, rcptnSn);
        return ps;
    }

    /** 고정 순서 바인딩 — SQL 의 컬럼 순서와 1:1 이며 그 외 컬럼은 존재하지 않는다. */
    private static PreparedStatement bind(PreparedStatement ps, InternalUploadIngestCommand c)
            throws SQLException {
        ps.setString(1, LsDataIngest.PRCS_STTS_PENDING);
        ps.setString(2, c.vmsClipId());
        bindReceivedColumns(ps, 3, c);
        return ps;
    }

    /**
     * 관제 수신 <b>28컬럼</b>({@code VMS_CLIP_ID} 제외) 공통 바인딩 — INSERT·되살리기가 공유한다.
     *
     * <p>두 SQL 이 각자 바인딩을 들면 컬럼을 하나 추가할 때 한쪽만 고쳐 <b>같은 타입 값들이 조용히
     * 뒤바뀐다</b>(String 20여 개). 순서 정의를 한 곳에 둔다.
     *
     * @return 다음 바인딩 인덱스
     */
    private static int bindReceivedColumns(PreparedStatement ps, int start,
                                           InternalUploadIngestCommand c) throws SQLException {
        int i = start;
        ps.setString(i++, c.vmsCctvId());
        ps.setString(i++, c.vdoFileNm());
        ps.setString(i++, c.rawFilePathNm());
        ps.setString(i++, c.srcType());
        setTimestamp(ps, i++, c.shtDt());
        setString(ps, i++, c.fileFmt());
        setString(ps, i++, c.vdoCdc());
        setLong(ps, i++, c.fileSz());
        setString(ps, i++, c.lclgvNm());
        setDecimal(ps, i++, c.vdoLenSec());
        setString(ps, i++, c.fps());
        setDecimal(ps, i++, c.frmeCnt());
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
        setString(ps, i++, c.lclgvCd());
        return i;
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
