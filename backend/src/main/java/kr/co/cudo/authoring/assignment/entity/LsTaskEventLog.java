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
 * </ul>
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
}
