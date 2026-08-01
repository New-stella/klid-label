package kr.co.cudo.authoring.video.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * 관제서버 클립 영상 마스터 (klid_system.MNG_CLIP_MASTER). 저작도구는 읽기 전용 (@Immutable).
 *
 * <p>실제 관제 스키마(DB 직접 조회로 확정)와 정합한다. 복합 PK = (EVNT_ID, CLIP_TYPE_CD).
 * LogiCraft <b>ERD-024</b>(관제 공유 클립 ERD)에 기록된 16컬럼을 전부 매핑한다(V147 — THMB_FILE_PATH,
 * USER_ID 등 그 밖의 실 테이블 컬럼은 여전히 미매핑이며 {@code ddl-auto=validate} 는 매핑 컬럼만
 * 검사한다). 컬럼 물리명·타입·길이는 관제가 소유한 실제 스키마 그대로이며 저작도구가 임의로 정하지 않는다.
 * MNG_* 는 관제팀 소유이므로 어떤 쓰기도 하지 않는다({@code @Immutable} — 제거 금지).
 */
@Entity
@Table(name = "MNG_CLIP_MASTER")
@IdClass(MngClipMasterId.class)
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MngClipMaster {

    /** 이벤트 식별자 (복합 PK). */
    @Id
    @Column(name = "EVNT_ID", length = 50)
    private String evntId;

    /** 클립 구분 형식 (복합 PK). */
    @Id
    @Column(name = "CLIP_TYPE_CD", length = 20)
    private String clipTypeCd;

    /** 클립 ID (UUID) — LS_DATA_RAW.VMS_CLIP_ID 멱등키로 사용. */
    @Column(name = "CLIP_ID", length = 50)
    private String clipId;

    /** 지자체 코드. */
    @Column(name = "LCLGV_CD", length = 20)
    private String lclgvCd;

    /** 파일 이름. */
    @Column(name = "FILE_NM", length = 256)
    private String fileNm;

    /** 파일 경로 (마운트 NAS 절대경로). */
    @Column(name = "FILE_PATH", length = 1000)
    private String filePath;

    /** 파일 형식 (mp4 등). */
    @Column(name = "FILE_FMT", length = 10)
    private String fileFmt;

    /**
     * 영상 길이. 관제 컬럼명/코멘트는 '초'이나 실측값은 밀리초(ms) 다(DB 직접 조회 확정).
     * 적재 시 ms → 초 변환한다({@code TrainingVideoIngestTx} 참조).
     */
    @Column(name = "VDO_LEN_SEC")
    private Integer vdoLenSec;

    /** 클립 상태 (mediainfo_complete / thumbnail_completed 등). */
    @Column(name = "CLIP_STTS_CD", length = 20)
    private String clipSttsCd;

    /** 생성 일자. */
    @Column(name = "CRT_DT")
    private LocalDateTime crtDt;

    /** 업로드 완료 일시. */
    @Column(name = "ULD_CMPT_DT")
    private LocalDateTime uldCmptDt;

    /** 작업 요청 (Y/N) — 학습용 지정 플래그. */
    @Column(name = "JOB_DMND_YN", length = 1)
    private String jobDmndYn;

    /** VMS CCTV 번호. */
    @Column(name = "VMS_CCTV_ID", length = 30)
    private String vmsCctvId;

    /** 파일 크기 (byte). */
    @Column(name = "FILE_SZ")
    private Long fileSz;

    /** 작업 요청 예정 (Y/N) — 학습용 지정 예약 플래그. */
    @Column(name = "JOB_DMND_PRNMNT_YN", length = 1)
    private String jobDmndPrnmntYn;

    /** 생성 타입 — 0=중계서버 생성, 1=수동 생성. */
    @Column(name = "CRT_TYPE")
    private Integer crtType;
}
