package kr.co.cudo.authoring.assignment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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
 */
@Entity
@Table(name = "LS_TASK_EVENT_LOG")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsTaskEventLog {

    public static final String EVENT_ASSIGN = "ASSIGN";
    public static final String EVENT_REASSIGN = "REASSIGN";
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

    @Builder(access = AccessLevel.PRIVATE)
    private LsTaskEventLog(Long rawDataId, String eventTypeCd, Long actorUserNo,
                           Long subjectUserNo, Long prevUserNo, String rsn,
                           LocalDateTime ocrnDt) {
        this.rawDataId = rawDataId;
        this.eventTypeCd = eventTypeCd;
        this.actorUserNo = actorUserNo;
        this.subjectUserNo = subjectUserNo;
        this.prevUserNo = prevUserNo;
        this.rsn = rsn;
        this.ocrnDt = ocrnDt;
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

    public static LsTaskEventLog approve(Long rawDataId, Long reviewerUserNo) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(EVENT_APPROVE)
                .actorUserNo(reviewerUserNo)
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
    public static LsTaskEventLog approveWithoutLabel(Long rawDataId, Long reviewerUserNo) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(EVENT_APPROVE)
                .actorUserNo(reviewerUserNo)
                .rsn(RSN_NO_LABEL_CONFIRMED)
                .ocrnDt(LocalDateTime.now())
                .build();
    }

    /** 라벨 0건 승인 감사 사유 고정 문구(검색·집계 키로 쓰이므로 변경 시 조회 쿼리 동반 수정). */
    public static final String RSN_NO_LABEL_CONFIRMED = "라벨 없음 확인 승인(negative sample)";

    public static LsTaskEventLog reject(Long rawDataId, Long reviewerUserNo, String rsn) {
        return LsTaskEventLog.builder()
                .rawDataId(rawDataId)
                .eventTypeCd(EVENT_REJECT)
                .actorUserNo(reviewerUserNo)
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
