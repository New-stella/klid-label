package kr.co.cudo.authoring.controlnotify.debounce;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 관제 수정 통지 <b>디바운스 누적</b> 영속 엔티티 ({@code LS_MON_NOTI_ACML}, Phase 9-C).
 *
 * <p>영상(rawSn) 1건의 수정을 윈도우로 모아 1회만 flush 하기 위한 행이다. 구 구현은 이 윈도우를
 * {@code ConcurrentHashMap} 으로 JVM 안에 두어 2노드 Active-Active 에서 ①같은 영상의 수정이 양쪽에
 * 나뉘어 축적되면 export 재생성·통지가 2회 나가고 ②노드가 flush 전에 죽으면 축적분이 통째로
 * 유실됐다. 영속화로 두 결함을 함께 닫는다.
 *
 * <h3>상태 전이</h3>
 * <pre>
 *   (없음) ──(첫 수정 축적)──▶ PENDING          // 열린 윈도우. 영상당 최대 1개(부분 유니크)
 *   PENDING ──(만료 후 노드 클레임)──▶ FLUSHING  // 조건부 UPDATE 로 한 노드만 승리
 *   FLUSHING ──(발송/재산출 위임 성공)──▶ (행 삭제)
 *   FLUSHING ──(클레임 노드 사망 → 임차 만료)──▶ FLUSHING (다른 노드가 재클레임)
 * </pre>
 *
 * <p>{@link #regDt} 는 <b>윈도우가 열린 시각</b>(디바운스 만료 기준)이고 {@link #mdfcnDt} 는 마지막 갱신
 * 시각인데, {@code FLUSHING} 행에서는 <b>클레임 임차 시작</b>을 뜻한다 — 임차가 만료되면 크래시 잔재로
 * 보고 다른 노드가 재클레임한다(축적분 유실 방지).
 *
 * <p>물리 컬럼명은 표준용어(관제=MON, 알림=NOTI, 누적=ACML, 변경상세내용=CHG_DTL_CN, 재처리=RPRCS)를
 * 따른다. 선존 {@code LS_CONTROL_NOTIFY_FALLBACK} 의 CONTROL/NOTIFY 드리프트는 미러하지 않는다.
 */
@Entity
@Table(name = "LS_MON_NOTI_ACML")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsMonNotiAcml {

    /** 축적 중인 열린 윈도우 — 새 수정이 여기에 머지된다. 영상당 최대 1행(부분 유니크). */
    public static final String STATUS_PENDING = "PENDING";
    /** 한 노드가 flush 를 클레임한 상태 — 발송/재산출 위임 중. 완료 시 행이 삭제된다. */
    public static final String STATUS_FLUSHING = "FLUSHING";

    public static final String YES = "Y";
    public static final String NO = "N";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "NOTI_ACML_SN")
    private Long notiAcmlSn;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "STTS_CD", length = 20, nullable = false)
    private String sttsCd;

    /** export 폴더 전량 재생성 동반 여부(윈도우 내 OR 누적). 'Y' 면 재생성 후 통지, 'N' 이면 즉시 통지. */
    @Column(name = "EXPORT_RPRCS_YN", length = 1, nullable = false)
    private String exportRprcsYn;

    /** 누적 변경 상세 JSON — 라벨/메타 본문·PII 는 담지 않는다(식별자 + 변경 종류만). */
    @Column(name = "CHG_DTL_CN", columnDefinition = "TEXT", nullable = false)
    private String chgDtlCn;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;

    /** 축적 결과(머지된 JSON + 재생성 동반 여부)를 반영한다. 재생성 플래그는 OR 누적(한 번 Y 면 Y 유지). */
    public void applyAccumulation(String mergedChangesJson, boolean exportRegenerated) {
        this.chgDtlCn = mergedChangesJson;
        if (exportRegenerated) {
            this.exportRprcsYn = YES;
        }
        this.mdfcnDt = LocalDateTime.now();
    }

    public boolean isExportRegenerated() {
        return YES.equals(exportRprcsYn);
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.regDt == null) this.regDt = now;
        if (this.mdfcnDt == null) this.mdfcnDt = now;
        if (this.sttsCd == null) this.sttsCd = STATUS_PENDING;
        if (this.exportRprcsYn == null) this.exportRprcsYn = NO;
        if (this.chgDtlCn == null) this.chgDtlCn = "{}";
    }

    @PreUpdate
    void preUpdate() {
        this.mdfcnDt = LocalDateTime.now();
    }
}
