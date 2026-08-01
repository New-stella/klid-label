package kr.co.cudo.authoring.dev.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * [개발/검수 전용] 관제 공유 클립 테이블에 dev 업로드 행을 INSERT 하는 <b>유일한</b> 쓰기 지점 (Phase 3).
 *
 * <h3>왜 JPA 가 아니라 네이티브 JdbcTemplate 인가 (중요 — 되돌리지 말 것)</h3>
 * <p>{@code MngClipMaster}/{@code MngClipEvntLst} 엔티티는 <b>{@code @Immutable} READ 전용</b>이다.
 * MNG_* 는 관제팀 소유 스키마이고 저작도구는 운영에서 단 한 글자도 쓰지 않는다는 것이 프로젝트 구속
 * 정책이라, 그 불변성을 깨서 dev 편의를 얻으면 <b>운영 코드에도 쓰기 경로가 열린다</b>. 그래서
 * ①엔티티/리포지토리는 그대로 READ 전용으로 두고 ②dev 전용 쓰기는 이 클래스의 네이티브 INSERT
 * <b>한 곳</b>에만 둔다. 이 클래스는 아래 3중 게이팅으로만 존재한다:
 * <ul>
 *   <li>{@code @Profile("!prd")} — 운영 프로파일에서는 빈 자체가 없다(관제 테이블 쓰기 원천 차단).</li>
 *   <li>{@code @ConditionalOnProperty authoring.dev.upload.enabled} — 기본 false(fail-closed).</li>
 *   <li>호출부({@code DevAutolabelTestController})의 {@code @PreAuthorize("hasRole('REVIEWER')")}.</li>
 * </ul>
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>CWE-89</b> — 모든 값은 {@code ?} 파라미터 바인딩. SQL 문자열 결합 0건(정적 SQL 상수만).</li>
 *   <li><b>CWE-117</b> — 사용자 입력 원문을 로그로 출력하지 않는다(식별자 해시만).</li>
 * </ul>
 *
 * <h3>트랜잭션 경계</h3>
 * <p>본 메서드는 {@code controlTransactionManager} 의 <b>독립 쓰기 트랜잭션</b>이며, 반환과 동시에
 * 커밋된다. 픽업 스캔({@code TrainingVideoIngestService.scanAndIngest})은 이 커밋 <b>이후</b>에
 * 호출해야 한다 — 스캔의 후보 조회는 별도 READ 트랜잭션이고 적재는 {@code REQUIRES_NEW}(별도 커넥션)라
 * 미커밋 행을 보지 못해 0건 픽업된다. 그래서 호출부는 이 빈을 트랜잭션 밖에서 호출한다.
 *
 * <p>PostgreSQL 은 제약 위반 시 트랜잭션 전체를 abort 하므로, 위반을 <b>같은 트랜잭션 안에서 이어
 * 처리하지 않고</b> 즉시 {@link CustomException}(409) 으로 던져 롤백시킨다. 두 INSERT 를 각각 감싸
 * <b>어느 테이블의 PK 가 충돌했는지 구분된 메시지</b>를 준다.
 */
@Slf4j
@Component
@Profile("!prd")
@ConditionalOnProperty(prefix = "authoring.dev.upload", name = "enabled", havingValue = "true")
public class DevControlClipWriter {

    /** MNG_CLIP_MASTER 16컬럼 INSERT — ERD-024 전체 컬럼(V147 정합). 정적 SQL + 파라미터 바인딩만. */
    private static final String INSERT_CLIP_MASTER = """
            INSERT INTO MNG_CLIP_MASTER (
                EVNT_ID, CLIP_TYPE_CD, CLIP_ID, LCLGV_CD, FILE_NM, FILE_PATH, FILE_FMT,
                VDO_LEN_SEC, CLIP_STTS_CD, CRT_DT, ULD_CMPT_DT, JOB_DMND_YN, VMS_CCTV_ID,
                FILE_SZ, JOB_DMND_PRNMNT_YN, CRT_TYPE
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** MNG_CLIP_EVNT_LST 12컬럼 INSERT. WTHR_CD 는 항상 null — 날씨는 관제에서 받지 않는다(2026-07-31). */
    private static final String INSERT_CLIP_EVNT_LST = """
            INSERT INTO MNG_CLIP_EVNT_LST (
                EVNT_ID, EVNT_TYPE_CD, SHT_DT, EVNT_NM, LCLGV_CD, SESN_CD, WTHR_CD,
                HR_TYPE_CD, PRVC_TYPE_CD, IDNTF_YN, CLCT_PATH, CLCT_SRC
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public DevControlClipWriter(@Qualifier("controlDataSource") DataSource controlDataSource) {
        this.jdbcTemplate = new JdbcTemplate(controlDataSource);
    }

    /** 테스트용 — JdbcTemplate 직접 주입(DataSource 없이 SQL/바인딩 검증). */
    DevControlClipWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 관제 두 테이블에 dev 업로드 행을 INSERT 한다 (하나의 트랜잭션 — 부분 적재 없음).
     *
     * @param row 관제 컬럼 값 묶음
     * @throws CustomException 409 — 복합 PK 충돌(어느 테이블인지 메시지로 구분)
     */
    @Transactional("controlTransactionManager")
    public void insertClip(ControlClipRow row) {
        try {
            jdbcTemplate.update(INSERT_CLIP_MASTER,
                    row.evntId(), row.clipTypeCd(), row.clipId(), row.lclgvCd(),
                    row.fileNm(), row.filePath(), row.fileFmt(),
                    row.vdoLenMs(), row.clipSttsCd(), toTimestamp(row.crtDt()),
                    toTimestamp(row.uldCmptDt()), row.jobDmndYn(), row.vmsCctvId(),
                    row.fileSz(), row.jobDmndPrnmntYn(), row.crtType());
        } catch (DataIntegrityViolationException e) {
            log.warn("[DevControlClipWriter] MNG_CLIP_MASTER PK conflict evntIdHash={} clipTypeCd={}",
                    hash(row.evntId()), hash(row.clipTypeCd()));
            throw new CustomException(ErrorCode.CONFLICT,
                    "동일한 evntId + clipTypeCd 의 관제 클립이 이미 존재합니다 (MNG_CLIP_MASTER PK 충돌). "
                            + "evntId 를 바꾸거나 비워서 서버 생성값을 사용하세요.");
        }
        try {
            jdbcTemplate.update(INSERT_CLIP_EVNT_LST,
                    row.evntId(), row.evntTypeCd(), toTimestamp(row.shtDt()), row.evntNm(),
                    row.lclgvCd(), row.sesnCd(), null, row.hrTypeCd(),
                    row.prvcTypeCd(), row.idntfYn(), row.clctPath(), row.clctSrc());
        } catch (DataIntegrityViolationException e) {
            log.warn("[DevControlClipWriter] MNG_CLIP_EVNT_LST PK conflict evntIdHash={}",
                    hash(row.evntId()));
            throw new CustomException(ErrorCode.CONFLICT,
                    "동일한 evntId + eventTypeCd 의 관제 이벤트가 이미 존재합니다 (MNG_CLIP_EVNT_LST PK 충돌). "
                            + "evntId 를 바꾸거나 비워서 서버 생성값을 사용하세요.");
        }
    }

    private static Timestamp toTimestamp(LocalDateTime value) {
        return value != null ? Timestamp.valueOf(value) : null;
    }

    /** CWE-117 — 사용자 입력 원문 대신 해시만 로그로 남긴다. */
    private static String hash(String value) {
        return Integer.toHexString(value == null ? 0 : value.hashCode());
    }

    /**
     * 관제 두 테이블에 INSERT 할 값 묶음 (MNG_CLIP_MASTER 16 + MNG_CLIP_EVNT_LST 12, 공통 컬럼 제외).
     *
     * <p>{@code vdoLenMs} 는 <b>밀리초</b>다 — 관제 {@code VDO_LEN_SEC} 의 실측 단위가 ms 이고
     * 적재({@code TrainingVideoIngestTx})가 ÷1000 으로 초를 복원하기 때문이다. 초를 그대로 넣으면
     * 적재 시 1초 미만으로 판정돼 길이가 null 로 떨어진다.
     */
    public record ControlClipRow(
            // --- MNG_CLIP_MASTER ---
            String evntId,
            String clipTypeCd,
            String clipId,
            String lclgvCd,
            String fileNm,
            String filePath,
            String fileFmt,
            Integer vdoLenMs,
            String clipSttsCd,
            LocalDateTime crtDt,
            LocalDateTime uldCmptDt,
            String jobDmndYn,
            String vmsCctvId,
            Long fileSz,
            String jobDmndPrnmntYn,
            Integer crtType,
            // --- MNG_CLIP_EVNT_LST ---
            String evntTypeCd,
            LocalDateTime shtDt,
            String evntNm,
            String sesnCd,
            String hrTypeCd,
            String prvcTypeCd,
            String idntfYn,
            String clctPath,
            String clctSrc
    ) {
    }
}
