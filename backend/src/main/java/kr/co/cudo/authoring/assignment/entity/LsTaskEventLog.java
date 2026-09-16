package kr.co.cudo.authoring.assignment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cudo.authoring.common.security.Role;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * 작업(영상) 단위 이벤트 누적 로그 — SCR-TASK-003 작업 이력 화면용.
 *
 * <p>배정/재배정/검수 제출/승인/반려를 단일 테이블에 시간순 누적하여 통합 타임라인을 제공한다.
 * 도메인적으로는 별개 비즈니스 이벤트지만 화면 표시 관점에서는 동일 영상의 라이프사이클이므로
 * 단일 테이블에 모아 N+1 조회 회피와 정렬 단순화를 달성한다.
 *
 * <p>각 이벤트의 의미:
 * <ul>
 *   <li>{@link #EVENT_ASSIGN}     : REVIEWER 가 WORKER 에게 최초 배정 — subject=배정된 작업자</li>
 *   <li>{@link #EVENT_REASSIGN}   : REVIEWER 가 다른 WORKER 로 재배정 — subject=새 작업자, prev=이전 작업자</li>
 *   <li>{@link #EVENT_START_REVIEW} : 검수 시작 — actor=검수를 시작한 사람. <b>그 영상의 점유를 세운다</b></li>
 *   <li>{@link #EVENT_SUBMIT}     : WORKER 가 라벨링 완료 후 검수 제출 — actor=subject=작업자 본인</li>
 *   <li>{@link #EVENT_CANCEL_SUBMIT} : WORKER 가 검수 시작 전 제출을 취소 — actor=subject=작업자 본인</li>
 *   <li>{@link #EVENT_APPROVE}    : REVIEWER 승인 — actor=검수자</li>
 *   <li>{@link #EVENT_REJECT}     : REVIEWER 반려 — actor=검수자, rsn(사유) 필수</li>
 *   <li>{@link #EVENT_PRIVACY_META_UPDATE} : 영상 개인정보 선언 변경 — actor=저장자, rsn=변경 여부 문구</li>
 *   <li>{@link #EVENT_PRIVACY_META_RESET}  : (구) 비식별 신고로 선언 리셋 — actor=신고자, rsn=신고 PK.
 *       <b>2026-08-04 리셋 폐기로 신규 발생 없음 — 과거 행 판독용 존치</b></li>
 *   <li>{@link #EVENT_FRAME_DISCARD} : 프레임을 산출물에서 제외(폐기) — actor=수행자, rsn=프레임 PK</li>
 *   <li>{@link #EVENT_FRAME_RESTORE} : 폐기 프레임을 산출 대상으로 복원 — actor=수행자, rsn=프레임 PK</li>
 * </ul>
 *
 * <p><b>개인정보 선언 2종은 배정/검수 이벤트가 아니라 감사(OWASP A09) 이벤트</b>다. 같은 테이블을 쓰는
 * 이유는 이 축이 <b>영상(rawSn) 스코프 + actor + 사유</b>를 이미 갖춘 유일한 이력이기 때문이며
 * (라벨 이력 {@code LS_DATA_LBL_HSTRY} 는 {@code SRC_SN NOT NULL} 인 프레임 스코프라 영상 축 행을 담을
 * 수 없다), 화면 타임라인에는 알 수 없는 코드가 아니라 전용 문구로 표시된다({@code HistoryDrawer}).
 *
 * <p><b>검수 점유도 이 원장이 표현한다</b> — 전용 컬럼이나 별도 표를 두지 않고 {@link #EVENT_START_REVIEW}
 * 한 종류로 「지금 누가 그 영상을 보고 있는가」를 담는다. 판정 규칙과 유예의 소유자는
 * {@code assignment.domain.ReviewClaim} 이며 이 엔티티는 사실만 적재한다.
 *
 * @design ADR-067
 * @design ERD-014
 */
@Entity
@Table(name = "LS_TASK_EVNT_LOG")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsTaskEventLog {

    public static final String EVENT_ASSIGN = "ASSIGN";
    public static final String EVENT_REASSIGN = "REASSIGN";
    /**
     * <b>검수 시작</b> — 그 영상의 점유를 세운다 (12자).
     *
     * <p>점유를 위해 컬럼이나 표를 새로 만들지 않는다. 이 원장이 이미 영상·행위자·발생일시를 갖고
     * 있고 승인·반려도 같은 원장에 쌓이므로, 「그 뒤에 종결 이벤트가 있으면 점유가 아니다」가 자연히
     * 성립한다. 값 제약({@code CHECK})이 없는 컬럼이라 이 종류를 더하는 데 마이그레이션이 필요 없으며,
     * 표준도메인 폭 {@code VARCHAR(20)} 안에 들어간다.
     *
     * <p>판정 규칙(뒤에 승인·반려가 없을 것 + 유예 안일 것)은 {@code ReviewClaim} 이 단독으로 소유한다 —
     * 여기에 옮겨 적지 않는다.
     *
     * @design ADR-067
     */
    public static final String EVENT_START_REVIEW = "START_REVIEW";
    public static final String EVENT_SUBMIT = "SUBMIT";
    public static final String EVENT_CANCEL_SUBMIT = "CANCEL_SUBMIT";
    public static final String EVENT_APPROVE = "APPROVE";
    public static final String EVENT_REJECT = "REJECT";
    /**
     * 영상 단위 개인정보 선언(익명/가명/개인정보 포함여부) 변경 — DEV_FIX 2차.
     * 코드값 길이는 표준도메인 {@code VARCHAR(20)}(V107) 이내여야 한다(19자).
     */
    public static final String EVENT_PRIVACY_META_UPDATE = "PRIVACY_META_UPDATE";
    /**
     * (구) 비식별 누락 신고로 영상 단위 개인정보 선언이 리셋됨 — DEV_FIX 2차 (18자).
     * <b>2026-08-04 리셋 폐기 — 신규 발생 없음. 과거 행 판독을 위해 상수를 존치한다(삭제 금지).</b>
     */
    public static final String EVENT_PRIVACY_META_RESET = "PRIVACY_META_RESET";
    /**
     * 프레임을 학습데이터 산출물에서 제외함(폐기) — V179 {@code LS_DATA_SRC.DSCD_YN} 축 (14자).
     * 코드값 길이는 표준도메인 {@code VARCHAR(20)} 이내여야 한다.
     */
    public static final String EVENT_FRAME_DISCARD = "FRAME_DISCARD";
    /** 폐기했던 프레임을 다시 사용 상태로 되돌림(복원) — {@link #EVENT_FRAME_DISCARD} 의 역방향 (14자). */
    public static final String EVENT_FRAME_RESTORE = "FRAME_RESTORE";
    /**
     * 영상 단위 「시작 버전 선택」 적용 — 어느 산출 회차 상태에서 작업을 다시 시작하기로 했는가 (19자).
     * 코드값 길이는 표준도메인 {@code VARCHAR(20)} 이내여야 한다.
     */
    public static final String EVENT_START_VERSION_APPLY = "START_VERSION_APPLY";
    /**
     * <b>영상 제외</b> — 그 영상을 저작도구 화면 목록에서 뺀다 (13자). [@design ADR-069] [@design ERD-014]
     *
     * <p>이 종류를 더하는 데 마이그레이션이 필요 없다 — {@code EVNT_TYPE_CD} 에 값 제약({@code CHECK})이
     * 없고 표준도메인 폭 {@code VARCHAR(20)} 안에 들어간다. 누가·그 시점 역할·언제·왜를 담을 칸도 이미
     * 다 있다.
     *
     * <p><b>사유({@code RSN})가 필수</b>이며 <b>자유 문구</b>다 — 프레임 폐기 계열
     * ({@link #EVENT_FRAME_DISCARD})이 식별자 한 토큰만 싣고 자유 문구를 금지하는 것과 <b>다르다.</b>
     * 감추는 행위라 「왜 뺐는가」가 확정 요구이고, 그것이 없으면 나중에 되돌릴지 판단할 근거가 없다.
     * 두 관례를 통일하지 말 것.
     */
    public static final String EVENT_VIDEO_EXCLUDE = "VIDEO_EXCLUDE";
    /**
     * <b>영상 복원</b> — 제외했던 영상을 다시 보이게 한다 (14자). {@link #EVENT_VIDEO_EXCLUDE} 의 역방향.
     *
     * <p><b>사유를 받지 않는다</b> — 감추는 쪽만 사유를 남긴다. 되돌리는 쪽은 사유가 없어도 사실이
     * 왜곡되지 않는다(감춰졌던 것이 제자리로 돌아올 뿐이다). 사유 칸을 다시 붙이지 말 것.
     *
     * @design ADR-069
     */
    public static final String EVENT_VIDEO_RESTORE = "VIDEO_RESTORE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "EVNT_ID")
    private Long eventSeq;

    @Column(name = "RAW_DATA_ID", nullable = false)
    private Long rawDataId;

    @Column(name = "EVNT_TYPE_CD", nullable = false, length = 20)
    private String eventTypeCd;

    @Column(name = "ACTOR_USER_NO", nullable = false)
    private Long actorUserNo;

    @Column(name = "SUBJECT_USER_NO")
    private Long subjectUserNo;

    @Column(name = "PREV_USER_NO")
    private Long prevUserNo;

    @Column(name = "RSN", length = 500)
    private String rsn;

    @Column(name = "OCRN_DT", nullable = false)
    private LocalDateTime ocrnDt;

    /**
     * <b>그 행위를 한 시점의 행위자 역할</b> (V38 신설, nullable).
     *
     * <p>조회 시점에 그 사람의 <b>현재</b> 역할을 다시 읽는 방식을 쓰지 않는다 — 그러면 역할이 바뀌는
     * 순간 과거 행위의 역할까지 따라 바뀐다. 같은 저장소의 {@code LsIssueComment.AUTHOR_ROLE_CD} 가
     * 같은 이유로 작성 시점 역할을 박아 두는 선례다.
     *
     * <p><b>계층으로 승격된 값이 아니라 행위자의 실제 역할</b>이다 — 관리자가 승인하면
     * {@link Role#ADMIN} 이 남아야 한다. {@link Role#REVIEWER} 로 내려 적으면 「관리자가 승인한 건」을
     * 사후에 가려낼 수 없어 이 컬럼을 둔 이유가 통째로 사라진다.
     *
     * <p><b>비어 있을 수 있다.</b> ①이 컬럼이 생기기 전에 쌓인 이력 ②역할을 남기지 않는 종류(배정·
     * 재배정·프레임 폐기 등 — 이번 범위가 아니다). 비었다고 결함이 아니며 <b>소급해 채우지 않는다</b>.
     *
     * @design ERD-014
     * @design API-116
     */
    @Column(name = "ACTOR_ROLE_CD", length = 20)
    private String actorRoleCd;

    /**
     * 이 컬럼이 담을 수 있는 역할 — {@code V38} 의 {@code CHECK} 제약과 <b>같은 집합</b>이다.
     *
     * <p>셋인 이유: 이 원장은 승인·반려(검수자·관리자)뿐 아니라 <b>제출</b>(작업자)도 담는다.
     * 「검수 이력이니 검수 역할만」으로 좁히면 제출 축이 제약 위반으로 실패한다. 반대로
     * {@link Role#PORTAL_USER} 는 채널이 달라 이 원장에 들어오지 않는다.
     */
    private static final Set<Role> AUDITABLE_ROLES = EnumSet.of(Role.ADMIN, Role.REVIEWER, Role.WORKER);

    /**
     * 행위자 역할 → 저장값. 값역 밖(또는 미배정)이면 <b>비운다</b>.
     *
     * <p>DB {@code CHECK} 를 그대로 맞으면 감사 기록 하나 때문에 승인·반려 본체가 500 으로 실패한다.
     * 이 컬럼은 부가 정보이므로 비우고 넘어간다 — 값역 밖 역할({@link Role#PORTAL_USER})이 이 원장에
     * 도달하는 경로는 현재 없고, 도달하더라도 본체를 깨뜨리는 것보다 비는 편이 낫다.
     */
    private static String roleCodeOf(Role actorRole) {
        return (actorRole != null && AUDITABLE_ROLES.contains(actorRole)) ? actorRole.name() : null;
    }

    @Builder(access = AccessLevel.PRIVATE)
    private LsTaskEventLog(Long rawDataId, String eventTypeCd, Long actorUserNo,
                           Long subjectUserNo, Long prevUserNo, String rsn,
                           LocalDateTime ocrnDt, String actorRoleCd) {
        this.rawDataId = rawDataId;
        this.eventTypeCd = eventTypeCd;
        this.actorUserNo = actorUserNo;
        this.subjectUserNo = subjectUserNo;
        this.prevUserNo = prevUserNo;
        this.rsn = rsn;
        this.ocrnDt = ocrnDt;
        this.actorRoleCd = actorRoleCd;
    }

    public static LsTaskEventLog assign(Long rawDataId, Long actorUserNo, Long subjectWorkerNo) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(EVENT_ASSIGN)
                .actorUserNo(actorUserNo)
                .subjectUserNo(subjectWorkerNo)
                .ocrnDt(LocalDateTime.now())
                .build();
    }

    public static LsTaskEventLog reassign(Long rawDataId, Long actorUserNo,
                                          Long newWorkerNo, Long prevWorkerNo) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(EVENT_REASSIGN)
                .actorUserNo(actorUserNo)
                .subjectUserNo(newWorkerNo)
                .prevUserNo(prevWorkerNo)
                .ocrnDt(LocalDateTime.now())
                .build();
    }

    public static LsTaskEventLog submit(Long rawDataId, Long workerUserNo) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(EVENT_SUBMIT)
                .actorUserNo(workerUserNo)
                .subjectUserNo(workerUserNo)
                .ocrnDt(LocalDateTime.now())
                .build();
    }

    public static LsTaskEventLog cancelSubmit(Long rawDataId, Long workerUserNo) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(EVENT_CANCEL_SUBMIT)
                .actorUserNo(workerUserNo)
                .subjectUserNo(workerUserNo)
                .ocrnDt(LocalDateTime.now())
                .build();
    }

    /**
     * <b>검수 시작</b> — 그 영상의 점유를 세운다.
     *
     * <p>같은 사람이 다시 열면 이 이벤트를 <b>다시 남겨</b> 점유 시각을 갱신한다(보던 중에 만료돼
     * 남에게 넘어가는 것을 막는다). 최초 시작 시각은 앞선 행에 그대로 남으므로 잃지 않는다.
     *
     * <p>점유를 <b>푸는</b> 이벤트는 만들지 않는다 — 승인·반려가 이미 자기 이벤트를 남기고, 자리를
     * 뜬 경우는 유예가 지나면 저절로 풀린다({@code ReviewClaim}).
     *
     * @param actorRole 행위 시점의 <b>실제</b> 역할. 관리자가 시작했으면 {@link Role#ADMIN} 이다
     * @design ADR-067
     */
    public static LsTaskEventLog startReview(Long rawDataId, Long actorUserNo, Role actorRole) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(EVENT_START_REVIEW)
                .actorUserNo(actorUserNo)
                .actorRoleCd(roleCodeOf(actorRole))
                .ocrnDt(LocalDateTime.now())
                .build();
    }

    /**
     * 검수 승인.
     *
     * @param actorRole 행위 시점의 <b>실제</b> 역할 — 관리자가 승인하면 {@link Role#ADMIN} 이 남는다.
     *                  계층으로 승격된 값({@link Role#REVIEWER})을 적지 말 것
     * @design ERD-014
     */
    public static LsTaskEventLog approve(Long rawDataId, Long reviewerUserNo, Role actorRole) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(EVENT_APPROVE)
                .actorUserNo(reviewerUserNo)
                .actorRoleCd(roleCodeOf(actorRole))
                .ocrnDt(LocalDateTime.now())
                .build();
    }

    /**
     * DEV_FIX(H6) — <b>라벨 0건(negative sample) 영상</b>을 검수자가 "라벨 없음" 확인 후 승인한 경우.
     *
     * <p>이벤트 종류는 기존 {@link #EVENT_APPROVE} 그대로 두고(타임라인·집계 계약 불변) 사유({@code RSN})에
     * 확인 사실을 남겨 <b>누가 언제 어떤 영상을</b> 라벨 없이 승인했는지 감사할 수 있게 한다. 신규 테이블·신규
     * 이벤트 코드를 만들지 않고 기존 메커니즘을 재사용한다(사유 컬럼은 반려가 이미 사용 중).
     * PII/토큰은 담지 않는다(고정 문구 + 행위자 번호만 — CWE-359).
     */
    public static LsTaskEventLog approveWithoutLabel(Long rawDataId, Long reviewerUserNo, Role actorRole) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(EVENT_APPROVE)
                .actorUserNo(reviewerUserNo)
                .actorRoleCd(roleCodeOf(actorRole))
                .rsn(RSN_NO_LABEL_CONFIRMED)
                .ocrnDt(LocalDateTime.now())
                .build();
    }

    /** 라벨 0건 승인 감사 사유 고정 문구(검색·집계 키로 쓰이므로 변경 시 조회 쿼리 동반 수정). */
    public static final String RSN_NO_LABEL_CONFIRMED = "라벨 없음 확인 승인(negative sample)";

    /**
     * 검수 반려.
     *
     * @param actorRole 행위 시점의 <b>실제</b> 역할 — 관리자가 반려하면 {@link Role#ADMIN} 이 남는다
     * @design ERD-014
     */
    public static LsTaskEventLog reject(Long rawDataId, Long reviewerUserNo, String rsn, Role actorRole) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(EVENT_REJECT)
                .actorUserNo(reviewerUserNo)
                .actorRoleCd(roleCodeOf(actorRole))
                .rsn(rsn)
                .ocrnDt(LocalDateTime.now())
                .build();
    }

    /**
     * DEV_FIX 2차 — <b>영상 단위 개인정보 선언(익명/가명/개인정보 포함여부) 변경</b> 감사 (OWASP A09).
     *
     * <p>이 선언은 학습데이터 export 의 {@code video} 블록으로 그대로 나가는 <b>사람의 판정</b>이므로,
     * 누가 언제 어느 영상의 판정을 바꿨는지 행 단위로 남는다. 영상(rawSn) 스코프 + actor 를 이미 가진
     * 이 테이블이 유일하게 맞는 축이다(라벨 이력 {@code LS_DATA_LBL_HSTRY} 는 {@code SRC_SN NOT NULL}
     * 인 프레임 스코프라 담을 수 없다).
     *
     * <p><b>판단값(Y/N)은 담지 않는다</b>(CWE-359 — 개인정보 유무 자체가 민감 신호이고 이 이력은
     * 작업 이력 화면에 노출된다). 값이 실제로 달라졌는지 여부만 고정 문구로 구분해 "열어보고 그대로 저장"
     * 과 "판정을 뒤집음"을 사후에 가려낼 수 있게 한다.
     *
     * @param changed 저장 전후 3필드 중 하나라도 값이 달라졌는지
     */
    public static LsTaskEventLog privacyMetaUpdated(Long rawDataId, Long actorUserNo, boolean changed) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(EVENT_PRIVACY_META_UPDATE)
                .actorUserNo(actorUserNo)
                .rsn(changed ? RSN_PRIVACY_META_CHANGED : RSN_PRIVACY_META_UNCHANGED)
                .ocrnDt(LocalDateTime.now())
                .build();
    }

    /**
     * DEV_FIX 2차 — <b>비식별 누락 신고에 의한 영상 축 개인정보 선언 리셋</b> 감사 (OWASP A09).
     *
     * <p>★ <b>신규 발생 없음 — 과거 행 판독용으로 존치한다 (2026-08-04)</b>: 신고 시 개인정보 3필드를
     * 되돌리던 동작이 폐기됐으므로({@code DeidentReportService} 5-1 주석) 이 팩토리를 호출하는 프로덕션
     * 경로는 없다. 이미 적재된 {@code PRIVACY_META_RESET} 이력 행을 화면·필터가 읽어야 하므로 이벤트
     * 타입 상수와 이 팩토리를 <b>그대로 유지</b>한다.
     *
     * <p>구 동작(폐기, 근거 보존): 신고가 그 영상의 개인정보 판정을 "재판정 대상"으로 되돌렸고
     * (수동값 → NULL), PII 표기를 되돌리는 행위이므로 프레임 축({@code LS_DATA_LBL_HSTRY} 행 단위 이력)과
     * <b>같은 기준</b>으로 감사했다. 리셋할 값이 애초에 없었으면 호출하지 않았다.
     *
     * @param reporterUserNo 신고자(=리셋을 유발한 행위자)
     * @param rprtSn         신고 PK — 이력에서 어떤 신고로 리셋됐는지 역추적용
     */
    public static LsTaskEventLog privacyMetaReset(Long rawDataId, Long reporterUserNo, Long rprtSn) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(EVENT_PRIVACY_META_RESET)
                .actorUserNo(reporterUserNo)
                .rsn(RSN_PRIVACY_META_RESET + " rprtSn=" + rprtSn)
                .ocrnDt(LocalDateTime.now())
                .build();
    }

    /**
     * <b>프레임 폐기</b> 감사 (OWASP A09) — 어느 프레임을 학습데이터 산출물에서 뺐는가.
     *
     * <p>이 판정은 산출물의 구성 자체를 바꾸므로 누가·언제·어느 프레임을 어느 방향으로 바꿨는지
     * 행 단위로 남는다. 영상({@code rawSn}) 스코프 + actor 를 이미 가진 이 테이블이 맞는 축이다
     * (라벨 이력 {@code LS_DATA_LBL_HSTRY} 는 프레임 스코프지만 행위자·사유 축이 없다).
     *
     * <h3>담지 않는 것 (Critical)</h3>
     * <ul>
     *   <li><b>사유를 받지 않는다</b> — 폐기 사유를 입력받지 않는 것이 확정 설계다. 파라미터를 두면
     *       호출부가 언젠가 자유 문구를 채우고, 그 문구는 작업 이력 화면에 그대로 노출된다.</li>
     *   <li><b>프레임 이미지 경로·PII 를 남기지 않는다</b>(CWE-359) — {@code RSN} 에는 식별자
     *       {@code srcSn=N} 한 토큰만 싣는다({@link #privacyMetaReset} 의 {@code rprtSn=} 선례).</li>
     * </ul>
     *
     * <p>방향(폐기/복원)은 <b>이벤트 타입 코드</b>가 구분한다 — 사유 문구로 구분하지 않는다.
     *
     * @param rawDataId   프레임이 속한 영상 (NOT NULL 컬럼)
     * @param srcSn       폐기된 프레임 PK
     * @param actorUserNo 폐기를 수행한 사용자
     * @design D1
     * @req R4
     */
    public static LsTaskEventLog frameDiscarded(Long rawDataId, Long srcSn, Long actorUserNo) {
        return frameDiscardEvent(EVENT_FRAME_DISCARD, rawDataId, srcSn, actorUserNo);
    }

    /**
     * <b>프레임 복원</b> 감사 (OWASP A09) — 폐기했던 프레임을 다시 산출 대상으로 되돌림.
     * 담는 것·담지 않는 것은 {@link #frameDiscarded} 와 동일하다.
     *
     * @design D1
     * @req R5
     */
    public static LsTaskEventLog frameRestored(Long rawDataId, Long srcSn, Long actorUserNo) {
        return frameDiscardEvent(EVENT_FRAME_RESTORE, rawDataId, srcSn, actorUserNo);
    }

    /**
     * <b>영상 제외</b> 감사 (OWASP A09) — 그 영상을 저작도구 화면 목록에서 뺐다.
     * [@design ADR-069] [@design ERD-014] [@design API-260]
     *
     * <p>담는 것: 영상 · 행위자 · <b>행위 시점의 실제 역할</b> · 발생일시 · <b>사유</b>.
     * 담지 않는 것: 대상/이전 사용자(제외는 사람을 대상으로 하지 않는다).
     *
     * <p>★사유는 <b>자유 문구</b>이고 <b>필수</b>다 — {@link #frameDiscarded} 계열이 식별자 한 토큰만
     * 싣는 것과 다르다. 호출부가 개행·제어문자를 제거하고 {@code RSN} 칸 폭 안으로 길이를 제한한 값을
     * 넘긴다(CWE-117 · DB 오류 차단). 개인정보를 적지 않도록 화면이 입력 칸에서 안내한다.
     *
     * @param actorRole 행위 시점의 <b>실제</b> 역할. 관리자가 제외했으면 {@link Role#ADMIN} 이다
     * @param rsn       정규화·절단이 끝난 제외 사유
     */
    public static LsTaskEventLog videoExcluded(Long rawDataId, Long actorUserNo, Role actorRole, String rsn) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(EVENT_VIDEO_EXCLUDE)
                .actorUserNo(actorUserNo)
                .rsn(rsn)
                .actorRoleCd(roleCodeOf(actorRole))
                .ocrnDt(LocalDateTime.now())
                .build();
    }

    /**
     * <b>영상 복원</b> 감사 (OWASP A09) — 제외했던 영상을 다시 보이게 했다.
     * [@design ADR-069] [@design ERD-014] [@design API-261]
     *
     * <p>{@link #videoExcluded} 와 같은 것을 담되 <b>사유는 담지 않는다</b> — 감추는 쪽만 사유를 남긴다.
     * 사유 인자를 다시 붙이지 말 것.
     *
     * @param actorRole 행위 시점의 <b>실제</b> 역할
     */
    public static LsTaskEventLog videoRestored(Long rawDataId, Long actorUserNo, Role actorRole) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(EVENT_VIDEO_RESTORE)
                .actorUserNo(actorUserNo)
                .actorRoleCd(roleCodeOf(actorRole))
                .ocrnDt(LocalDateTime.now())
                .build();
    }

    /** 폐기/복원 공통 조립 — 두 방향이 같은 형식을 갖도록 한 곳에서만 만든다. */
    private static LsTaskEventLog frameDiscardEvent(String eventTypeCd, Long rawDataId,
                                                    Long srcSn, Long actorUserNo) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(eventTypeCd)
                .actorUserNo(actorUserNo)
                .rsn(RSN_FRAME_PREFIX + srcSn)
                .ocrnDt(LocalDateTime.now())
                .build();
    }

    /**
     * <b>영상 단위 시작 버전 적용</b> 감사 (OWASP A09 / CWE-778) — 누가 어느 산출 회차로 되돌렸는가.
     *
     * <h3>왜 프레임별 이력만으로는 부족한가</h3>
     * 이 조작은 영상 전 프레임의 라벨 본문과 폐기 상태를 <b>비가역적으로</b> 교체하는데, 프레임별
     * 이력({@code LS_DATA_LBL_HSTRY} 롤백 이벤트 · 폐기/복원 감사)만 두면
     * ①전 프레임이 멱등 no-op 이면 DB 에 흔적이 <b>하나도</b> 남지 않고
     * ②"어느 회차를 골랐는가" 가 어디에도 없다(스냅샷 해시에서 역산해야 한다).
     * 영상({@code rawSn}) 스코프 + actor 를 가진 이 테이블이 그 축을 담을 유일한 이력이다.
     *
     * <h3>담지 않는 것</h3>
     * 라벨 본문·좌표·프레임 경로·PII 를 남기지 않는다(CWE-359). {@code RSN} 에는 식별자
     * {@code versionNo=N} 한 토큰만 싣는다({@link #RSN_FRAME_PREFIX} 선례).
     *
     * @param rawDataId   대상 영상
     * @param actorUserNo 시작 버전을 고른 사용자
     * @param versionNo   적용한 산출 회차 번호
     * @design D5
     * @req R6
     */
    public static LsTaskEventLog startVersionApplied(Long rawDataId, Long actorUserNo, Integer versionNo) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(EVENT_START_VERSION_APPLY)
                .actorUserNo(actorUserNo)
                .rsn(RSN_START_VERSION_PREFIX + versionNo)
                .ocrnDt(LocalDateTime.now())
                .build();
    }

    /**
     * 시작 버전 적용 감사의 {@code RSN} 접두 — 뒤에 산출 회차 번호만 붙는다({@code versionNo=3}).
     * 자유 문구·본문을 넣지 않는다(CWE-359). 조회 키이므로 변경 시 판독 쿼리 동반 수정.
     */
    public static final String RSN_START_VERSION_PREFIX = "versionNo=";

    /**
     * 프레임 폐기·복원 감사의 {@code RSN} 접두 — 뒤에 프레임 PK 만 붙는다({@code srcSn=123}).
     * 자유 문구·파일 경로를 넣지 않는다(CWE-359). 조회 키이므로 변경 시 판독 쿼리 동반 수정.
     */
    public static final String RSN_FRAME_PREFIX = "srcSn=";

    /** 개인정보 선언 변경 감사 사유 — 값이 실제로 달라진 경우. */
    public static final String RSN_PRIVACY_META_CHANGED = "영상 개인정보 선언 변경";
    /** 개인정보 선언 변경 감사 사유 — 저장은 했으나 값 변화가 없는 경우. */
    public static final String RSN_PRIVACY_META_UNCHANGED = "영상 개인정보 선언 저장(변경 없음)";
    /** 개인정보 선언 리셋 감사 사유 접두 — 뒤에 {@code rprtSn=N} 이 붙는다. */
    public static final String RSN_PRIVACY_META_RESET = "비식별 신고로 영상 개인정보 선언 리셋";
}
