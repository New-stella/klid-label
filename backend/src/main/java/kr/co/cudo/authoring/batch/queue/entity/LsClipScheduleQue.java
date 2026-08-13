package kr.co.cudo.authoring.batch.queue.entity;

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
 * 배치 작업 큐. 영상 수신(LS_DATA_RAW upsert) 직후 1건 INSERT 되어 라벨링 배치 파이프라인 입구로 사용.
 * - JOB_TYPE_CD = "LABELING_BATCH" 만 본 Phase 에서 적재.
 * - STTS_CD: PENDING -> IN_PROGRESS -> DONE / FAILED.
 *
 * <p><b>소유권</b>: 저작도구 자체 소유 테이블이다(V2 에서 직접 CREATE). 구 이름 {@code MNG_CLIP_SCHEDULE_QUE}
 * 는 접두 때문에 관제서버 공유 스키마로 오인돼 V146(LS_DATA_RAW 자식 FK 전수 보강)에서 제외됐고, 그 결과
 * {@code RAW_SN} 이 참조무결성 없이 방치됐다. V162 에서 {@code LS_} 로 개명하고
 * {@code RAW_SN → LS_DATA_RAW (ON DELETE CASCADE)} FK 를 보강했다 — 영상 원본이 삭제되면 큐 행도 함께
 * 사라지므로 고아가 구조적으로 불가능하다.
 *
 * <p><b>표준용어 개명(V5)</b>: 컬럼 7종이 영문 서술형이라 형제 큐(LS_BAT_RTY_WTNG ·
 * LS_CONTROL_NOTIFY_FALLBACK)와 같은 개념에 다른 이름을 쓰고 있었다. 자바 필드명은 물리명의
 * camelCase 미러라는 이 저장소 관례에 따라 함께 개명했다. 근거·롤백 절차는 마이그레이션 헤더에 있다.
 * <b>값 상수({@code JOB_LABELING_BATCH} · {@code STATUS_*})의 이름과 값은 바뀌지 않았다</b> —
 * 그건 컬럼이 아니라 값의 의미를 가리킨다.
 *
 * @req R2
 */
@Entity
@Table(name = "LS_CLIP_SCHEDULE_QUE")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsClipScheduleQue {

    public static final String JOB_LABELING_BATCH = "LABELING_BATCH";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "QUE_SN")
    private Long queSn;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "JOB_TYPE_CD", nullable = false, length = 20)
    private String jobTypeCd;

    @Column(name = "STTS_CD", nullable = false, length = 16)
    private String sttsCd;

    @Column(name = "RTRY_NMTM", nullable = false)
    private Integer rtryNmtm;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "BGNG_DT")
    private LocalDateTime bgngDt;

    @Column(name = "CMPTN_DT")
    private LocalDateTime cmptnDt;

    @Column(name = "LAST_ERR_MSG_CN", length = 2000)
    private String lastErrMsgCn;

    @Builder
    private LsClipScheduleQue(Long rawSn, String jobTypeCd) {
        this.rawSn = rawSn;
        this.jobTypeCd = jobTypeCd;
        this.sttsCd = STATUS_PENDING;
        this.rtryNmtm = 0;
        this.regDt = LocalDateTime.now();
    }

    public static LsClipScheduleQue enqueueLabelingBatch(Long rawSn) {
        return LsClipScheduleQue.builder()
                .rawSn(rawSn)
                .jobTypeCd(JOB_LABELING_BATCH)
                .build();
    }

    /** 큐 워커가 작업 시작 시 호출. 외부에서 직접 상태를 set 하지 않도록 메서드로 노출. */
    public void markInProgress() {
        this.sttsCd = STATUS_IN_PROGRESS;
        this.bgngDt = LocalDateTime.now();
    }
}
