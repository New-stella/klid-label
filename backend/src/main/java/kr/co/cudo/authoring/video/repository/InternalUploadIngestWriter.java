package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.video.dto.InternalUploadIngestCommand;
import kr.co.cudo.authoring.video.dto.ResolvedIngestMeta;
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
 * 관제 소유 수신 컬럼의 일반 쓰기 경로가 된다 — CWE-915). 그럼에도 <b>내부 관리 화면 업로드 1개
 * 흐름</b>은 저작도구가 유일하게 정당한 origin 이므로, 그 하나만을 위한 좁은 통로로 이 클래스를 둔다.
 *
 * <h3>범용 writer 가 되지 않게 하는 장치</h3>
 * <ul>
 *   <li><b>고정 컬럼 리스트 + {@code ?} 플레이스홀더만</b> — 컬럼명을 인자로 받는 map/varargs API 를
 *       두지 않는다. 그런 API 는 임의 컬럼 쓰기 경로가 되어 위 계약을 무너뜨린다(SQL 조립 표면도
 *       생기지 않는다 — CWE-89).</li>
 *   <li><b>저작도구 운영 8컬럼은 SQL 에 아예 없다</b>({@code RCPTN_SN}·{@code RCPTN_DT}·{@code RAW_SN}·
 *       {@code RTY_CNT}·{@code PRCS_DT}·{@code NXTM_RTRY_DT}·{@code ERR_MSG}). 인입 폴링 상태머신의
 *       소유값이므로 DB DEFAULT 에 맡긴다. 유일한 예외가 {@code PRCS_STTS_CD} 이고, 그것도 고정값
 *       {@code 'PENDING'} 이라 호출자가 상태를 고를 수 없다.</li>
 *   <li><b>입력은 {@link InternalUploadIngestCommand} 하나</b> — 커맨드 자체가 관제 수신 30컬럼만
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
     * 고정 컬럼 INSERT — 관제 수신 30컬럼 + 처리상태 1컬럼(고정 {@code 'PENDING'}).
     *
     * <p>{@code OG_CD}(기관코드)는 V185 에서 제거됐다가 V16 에서 복원됐다(2026-08-24 관제 재확인
     * "실보유"). {@code LCLGV_CD}·{@code LCLGV_NM} 과 <b>서로 다른 값</b>이다. 다만 이 INSERT 목록에는
     * 넣지 않는다 — dev 내부 업로드는 그 값을 갖지 않아 nullable 컬럼으로 비워 둔다(V16 신설
     * {@code THMB_FILE_PATH_NM} 도 같은 이유로 제외).
     *
     * <p>{@code BIT} 은 PostgreSQL 에서 컬럼명으로는 무인용 사용이 가능하다(V147 실증). 인용하면
     * 대문자 식별자가 고정돼 나머지 컬럼(무인용→소문자 폴딩)과 규칙이 갈리므로 그대로 둔다.
     *
     * <p>★ 컬럼을 추가할 때는 <b>컬럼 리스트 · {@code ?} 개수 · {@link #bindReceivedColumns} 순서</b>
     * 셋을 함께 고친다. 하나만 어긋나면 같은 타입(String 20여 개)끼리 값이 <b>조용히 뒤바뀐다</b>
     * (예외가 나지 않는다). 신규 컬럼은 항상 <b>끝에</b> 붙여 기존 순서를 흔들지 않는다.
     */
    private static final String INSERT_SQL = """
            INSERT INTO LS_DATA_INGEST (
                PRCS_STTS_CD,
                VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, SRC_TYPE, SHT_DT,
                FILE_FMT, VDO_CDC, FILE_SZ, LCLGV_NM, VDO_LEN_SEC, FPS, FRME_CNT, ASPRT_RT,
                WDTH, VRTC, RESL, BIT, PXL, WGS84_LAT, WGS84_LOT, CCTV_NM, CCTV_HGT,
                MAIN_SURV_PAN_ANG, EVNT_ID, EVNT_NM, MNTR_CN, LCLGV_CD, VRFC_EVNT_TYPE_CD,
                EVNT_TYPE_CD)
            VALUES (?,
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?)
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
     *   <li>관제 수신 <b>29컬럼</b>({@code VMS_CLIP_ID} 제외 — UK 이자 조회 키라 그대로 둔다).</li>
     *   <li>{@code RCPTN_DT} — 새 업로드의 수신 시각. 갱신하지 않으면 되살린 행이 <b>FIFO 앞자리</b>를
     *       옛 시각으로 계속 점유한다.</li>
     *   <li>{@code ERR_MSG}·{@code PRCS_DT}·{@code NXTM_RTRY_DT} 를 비운다 — 이전 종결 사유와 대기
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
     * 되살리기 UPDATE — SET 절은 관제 수신 29컬럼 + 운영 리셋 4컬럼이고 그 외 컬럼은 존재하지 않는다.
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
                   NXTM_RTRY_DT = NULL,
                   VMS_CCTV_ID = ?, VDO_FILE_NM = ?, RAW_FILE_PATH_NM = ?, SRC_TYPE = ?, SHT_DT = ?,
                   FILE_FMT = ?, VDO_CDC = ?, FILE_SZ = ?, LCLGV_NM = ?, VDO_LEN_SEC = ?, FPS = ?,
                   FRME_CNT = ?, ASPRT_RT = ?, WDTH = ?, VRTC = ?, RESL = ?, BIT = ?, PXL = ?,
                   WGS84_LAT = ?, WGS84_LOT = ?, CCTV_NM = ?, CCTV_HGT = ?,
                   MAIN_SURV_PAN_ANG = ?, EVNT_ID = ?, EVNT_NM = ?, MNTR_CN = ?, LCLGV_CD = ?,
                   VRFC_EVNT_TYPE_CD = ?, EVNT_TYPE_CD = ?
             WHERE RCPTN_SN = ?
               AND PRCS_STTS_CD = 'FAILED'
               AND RAW_SN IS NULL
            """;

    /**
     * <b>측정 기술메타 back-fill</b> — 업로드 완료 시점 ffprobe 측정값으로 <b>비어 있는</b> 기술메타
     * 8컬럼만 채운다 (Phase 2).
     *
     * <h3>사용자가 입력한 값은 덮지 않는다 — 판정은 DB 가 한다 (R4)</h3>
     * <p>"비었는가"를 앱이 먼저 조회해서 판단하면 read-then-write 라, 조회와 UPDATE 사이에 다른
     * 주체가 값을 넣으면 그것을 덮는다(CWE-362). 그래서 {@code COALESCE} 로 <b>단일 문장 안에서</b>
     * 원자적으로 판정한다 — 기존 값이 있으면 그 값이 그대로 남고, {@code null} 이면 인자가 들어간다.
     * 인자 쪽이 {@code null}(측정 미채택)이면 양쪽 다 {@code null} 이라 컬럼은 변하지 않는다.
     *
     * <p>VARCHAR 4종({@code FPS}·{@code VDO_CDC}·{@code RESL}·{@code ASPRT_RT})은
     * {@code NULLIF(BTRIM(...), '')} 를 씌워 <b>공백문자열도 미입력</b>으로 본다 — 폼이 빈 문자열을
     * 보내면 세션 생성 INSERT 가 그것을 그대로 실었기 때문에, 공백을 입력으로 인정하면 그 행은
     * 영원히 비어 있는 것으로 남는다.
     *
     * <h3>★{@code BTRIM} 의 제거 문자셋을 명시한다 — 기본값은 스페이스만 지운다 (DEV_FIX 2차)</h3>
     * <p>{@code BTRIM(col)} 은 <b>ASCII 스페이스만</b> 제거한다. 그래서 <b>탭·개행·NBSP 만 든 값</b>은
     * "사용자 입력"으로 판정돼 그 컬럼이 영원히 채워지지 않았다(실 PostgreSQL 실증 — {@code fps="\t"} 가
     * 코드포인트 9 그대로 남고 측정값 {@code 25} 가 미적용. 같은 UPDATE 의 {@code RESL} 은 정상
     * 반영돼 <b>문은 돌았고 판정만 어긋난</b> 형태였다). 위 문단의 취지가 스페이스에서만 성립했던 것이다.
     *
     * <p>그래서 제거 문자셋을 {@code ' ' || CHR(9) || CHR(10) || CHR(13) || CHR(160)}
     * (스페이스·탭·LF·CR·NBSP)로 <b>명시</b>한다. 표기를 {@code CHR()} 연결로 고른 이유:
     * <ul>
     *   <li>{@code E'...'} escape string 은 <b>{@code E} 접두 하나가 빠지면 조용히 무력화</b>된다 —
     *       일반 문자열 {@code ' \t\r\n'} 은 백슬래시+문자 4쌍이라 탭을 지우지 못한다(실측 확인).</li>
     *   <li>Java 텍스트블록도 {@code \t} 를 <b>실제 제어문자</b>로 바꾸므로, 소스에 보이는 표기와 DB 가
     *       받는 문자열이 눈으로 구분되지 않는다({@code \\t} 로 이스케이프해야 의도대로 나간다).</li>
     *   <li>{@code CHR()} 은 두 층 모두에서 이스케이프가 없어 <b>보이는 그대로</b>가 나간다.</li>
     * </ul>
     * <p>NBSP({@code CHR(160)})는 실 PostgreSQL(UTF8)에서 코드포인트 160·2바이트로 확인했다
     * ({@code ascii(chr(160))=160}). 프로젝트 DB 인코딩이 UTF-8 이므로 유효하다.
     *
     * <h3>패딩된 입력은 <b>트림된 형태로 정규화 저장</b>된다 (인지 사항)</h3>
     * <p>{@code COALESCE(NULLIF(BTRIM(col, ...), ''), ?)} 는 값이 있을 때 <b>{@code BTRIM} 의 결과</b>를
     * 돌려주므로, 사용자가 {@code "  1920x1080  "} 을 넣으면 back-fill 이후 {@code "1920x1080"} 으로
     * 재기록된다. 즉 미입력 판정에 쓴 트림이 저장값에도 적용된다 — <b>값 자체는 보존</b>되며 측정값이
     * 덮지 않는다는 R4 는 그대로다. 무해한 정규화라 판단해 유지하며, 회귀 테스트가 이 동작을 고정한다.
     *
     * <h3>{@code PXL}·{@code BIT} 는 SET 절에 <b>존재하지 않는다</b> (R3)</h3>
     * <p>화소·색심도는 표기 규약이 정의돼 있지 않아 무엇을 넣든 지어낸 값이 된다. 그 의지를 SQL 에
     * 못 박은 것이며, {@link ResolvedIngestMeta} 에도 그 필드가 없어 인자로 넘길 수단조차 없다.
     * 구조 가드({@code LsDataIngestWriteGuardTest})가 두 컬럼의 부재와 {@code COALESCE} 래핑을
     * 각각 단언한다.
     *
     * <h3>술어 — 우리 행이자 아직 적재 전인 미처리 행만</h3>
     * <ul>
     *   <li>{@code PRCS_STTS_CD = 'PENDING'} — 폴링이 클레임({@code PROCESSING})했거나 종결
     *       ({@code FAILED})·적재 완료({@code DONE})된 행은 건드리지 않는다.</li>
     *   <li>{@code RAW_SN IS NULL} — 이미 적재된 영상의 인입 근거를 사후 변조하지 않는다.</li>
     * </ul>
     * <p>"우리가 만든 행인가"(경로가 인입 영역 하위인가)는 SQL 술어로 판정할 수 없으므로 호출 측
     * ({@code TusUploadService} + {@code InternalUploadPathResolver#isUploadAreaPath})이 판정한다 —
     * {@code reviveForUpload} 와 <b>같은</b> 신뢰 경계이며, 가드 테스트가 호출부 단일성을 고정한다.
     *
     * <h3>0행은 정상이다 — 예외도 재시도도 아니다</h3>
     * <p>①폴링이 그 순간 클레임 중 ②미도착 상한 초과로 이미 종결 ③{@code RAW_SN} 이 이미 있음.
     * 셋 다 "이번엔 채우지 않는다"가 옳은 결과이고 업로드 완료를 실패시킬 이유가 없다.
     *
     * <h3>알려진 한계 (구현하지 않는다 — 인지 사항)</h3>
     * <ul>
     *   <li><b>{@link #reviveForUpload} 는 {@code COALESCE} 없이 무조건 덮어쓴다.</b> 되살리기가
     *       일어나면 back-fill 로 채운 8컬럼이 새 폼 입력(대개 {@code null})으로 재초기화된다.
     *       신규 결함이 아니며, 재업로드 완료 시 back-fill 이 다시 돌아 채워지므로 무해하다.</li>
     *   <li><b>FE 가 미입력 숫자 필드를 {@code 0} 으로 보내면</b> {@code COALESCE} 가 "입력됨"으로
     *       보아 그 컬럼은 영구히 채워지지 않는다. {@code 0} 이 유효값인 경우와 구분할 수단이 없어
     *       (도메인 결정 필요) 이번 범위에서는 그대로 둔다.</li>
     * </ul>
     *
     * <h3>트랜잭션 — {@link Propagation#REQUIRES_NEW} (필수)</h3>
     * <p>호출자(업로드 완료)의 트랜잭션 안에서 돌리면, 이 UPDATE 하나가 실패하는 순간 PostgreSQL 이
     * <b>트랜잭션 전체를 abort</b> 시킨다. 호출부가 예외를 삼켜도 커넥션은 이미 오염돼 뒤따르는
     * {@code markCompletedIfInProgress}·{@code markUploadArrived} 가 모조리 실패하고, <b>정상적으로 다
     * 올라온 업로드가 500 으로 완료 실패</b>한다. 부가 기능이 본 흐름을 죽이지 않도록 격리한다
     * ({@link #reviveForUpload} 와 같은 이유·패턴).
     *
     * @param rcptnSn 대상 인입 행 PK
     * @param meta    채택된 측정값(미채택 필드는 {@code null} — 그 컬럼은 변하지 않는다)
     * @return 갱신된 행 수 — <b>1 = 채움 시도 완료</b> / <b>0 = 술어 불일치</b>(정상 skip)
     * @req R1, R2, R4
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, transactionManager = "controlTransactionManager")
    public int backfillMeasuredMeta(Long rcptnSn, ResolvedIngestMeta meta) {
        return jdbcTemplate.update(connection ->
                bindBackfill(connection.prepareStatement(BACKFILL_SQL), meta, rcptnSn));
    }

    /**
     * back-fill UPDATE — SET 절은 기술메타 8컬럼뿐이고 모두 {@code COALESCE} 로 감싸여 있다.
     *
     * <p>{@code PXL}·{@code BIT} 는 여기에 <b>없다</b>(R3). 구조 가드가 이 부재를 단언하므로
     * 추가하면 테스트가 죽는다.
     *
     * <p>VARCHAR 4종의 {@code BTRIM} 은 제거 문자셋을 <b>명시</b>한다(스페이스·탭·LF·CR·NBSP) —
     * 기본 {@code BTRIM(col)} 은 스페이스만 지워 탭·개행·NBSP 만 든 값이 "입력"으로 잠긴다. 근거와
     * 표기 선택 이유는 {@link #backfillMeasuredMeta} Javadoc 참조. 이 인자를 지우면 회귀 테스트가 죽는다.
     */
    private static final String BACKFILL_SQL = """
            UPDATE LS_DATA_INGEST
               SET VDO_LEN_SEC = COALESCE(VDO_LEN_SEC, ?),
                   FPS         = COALESCE(NULLIF(BTRIM(FPS,
                                     ' ' || CHR(9) || CHR(10) || CHR(13) || CHR(160)), ''), ?),
                   VDO_CDC     = COALESCE(NULLIF(BTRIM(VDO_CDC,
                                     ' ' || CHR(9) || CHR(10) || CHR(13) || CHR(160)), ''), ?),
                   WDTH        = COALESCE(WDTH, ?),
                   VRTC        = COALESCE(VRTC, ?),
                   RESL        = COALESCE(NULLIF(BTRIM(RESL,
                                     ' ' || CHR(9) || CHR(10) || CHR(13) || CHR(160)), ''), ?),
                   FRME_CNT    = COALESCE(FRME_CNT, ?),
                   ASPRT_RT    = COALESCE(NULLIF(BTRIM(ASPRT_RT,
                                     ' ' || CHR(9) || CHR(10) || CHR(13) || CHR(160)), ''), ?)
             WHERE RCPTN_SN = ?
               AND PRCS_STTS_CD = 'PENDING'
               AND RAW_SN IS NULL
            """;

    /** back-fill 바인딩 — SQL 의 SET 순서와 1:1 + 대상 PK. */
    private static PreparedStatement bindBackfill(PreparedStatement ps, ResolvedIngestMeta m,
                                                  Long rcptnSn) throws SQLException {
        int i = 1;
        setDecimal(ps, i++, m.vdoLenSec());
        setString(ps, i++, m.fps());
        setString(ps, i++, m.vdoCdc());
        setDecimal(ps, i++, m.wdth());
        setDecimal(ps, i++, m.vrtc());
        setString(ps, i++, m.resl());
        setDecimal(ps, i++, m.frmeCnt());
        setString(ps, i++, m.asprtRt());
        ps.setLong(i, rcptnSn);
        return ps;
    }

    /** 되살리기 바인딩 — {@code VMS_CLIP_ID} 를 제외한 29컬럼 + 대상 PK. */
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
     * 관제 수신 <b>29컬럼</b>({@code VMS_CLIP_ID} 제외) 공통 바인딩 — INSERT·되살리기가 공유한다.
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
        setString(ps, i++, c.cctvNm());
        setDecimal(ps, i++, c.cctvHgt());
        setInt(ps, i++, c.mainSurvPanAng());
        setString(ps, i++, c.evntId());
        setString(ps, i++, c.evntNm());
        setString(ps, i++, c.mntrCn());
        setString(ps, i++, c.lclgvCd());
        // V176 — 검증이벤트유형(외부 VLM verify 의 event_type). 값은 호출 측이 정규화·allowlist
        //   검증을 마친 뒤 넘긴다(@req R5). 미지정이면 null 로 남는다.
        setString(ps, i++, c.vrfcEvntTypeCd());
        // V166 컬럼 — 이벤트유형코드. 관제가 인입 평면값으로 싣는 값이며 적재가 이것을 단독
        //   조달원으로 LS_DATA_RAW 에 복사한다(마킹 프리컨디션의 입력). 미지정이면 null.
        //   ⚠ 위 주석대로 <b>끝에</b> 붙인다 — 중간에 끼우면 같은 String 타입끼리 조용히 뒤바뀐다.
        setString(ps, i++, c.evntTypeCd());
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
