package kr.co.cudo.authoring.dataset.export.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 학습데이터 파일 산출(export) 추적 원장 — {@code LS_DATASET_EXPORT}.
 *
 * <p>검수 승인 시 라벨링 산출물을 {@code {labeling_root}/{RAW_SN}/v{n}/{orgnl|deid}/} 로 저장하는
 * 기능(Phase 1 인프라)의 추적 엔티티. 영상(dataRawSn) 단위로 export 를 누적 기록하며,
 * 그 건수(+1)로 다음 산출 버전을 도출한다.
 *
 * <p>상태(OUTPUT_STTS_CD)는 {@code PENDING → SUCCEEDED|FAILED|PARTIAL} 로 전이한다.
 * 상태 변경은 {@code @Setter} 가 아닌 의미 있는 비즈니스 메서드로만 수행한다.
 *
 * <h3>V173 — 물리명은 표준용어로, 자바 필드명은 그대로 (@req R3)</h3>
 * <p>{@code EXPORT_*} 는 표준 미등록 약어라 산출물({@code OUTPUT})·프레임({@code FRME}) 표준단어로
 * 정정했다. 다만 <b>바꾼 것은 {@code @Column(name)} 값뿐</b>이고 자바 필드명({@code exportSn} 등)은
 * 유지한다 — 필드명을 바꾸면 파생 쿼리 메서드 3종({@code existsByDataRawSnAndExportVerNo} ·
 * {@code findFirstByDataRawSnOrderByExportVerNoDesc} ·
 * {@code findFirstByDataRawSnAndExportSttsCdInOrderByExportVerNoDesc})과 그 호출부·테스트가 연쇄로
 * 바뀌는데, 표준용어 규칙의 대상은 <b>DB 물리명</b>이지 자바 식별자가 아니다.
 */
@Entity
@Table(name = "LS_DATASET_EXPORT")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDatasetExport {

    /** 산출 대기(레코드 생성 직후 초기 상태). */
    public static final String STATUS_PENDING = "PENDING";
    /** 산출 완료. */
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    /** 산출 실패. */
    public static final String STATUS_FAILED = "FAILED";
    /** 일부 산출(원본/비식별 중 일부만 성공 등). */
    public static final String STATUS_PARTIAL = "PARTIAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "OUTPUT_SN")
    private Long exportSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    @Column(name = "OUTPUT_VER_NO", nullable = false)
    private int exportVerNo;

    @Column(name = "OUTPUT_PATH_NM", length = 500)
    private String exportPathNm;

    @Column(name = "OUTPUT_STTS_CD", nullable = false, length = 20)
    private String exportSttsCd;

    /**
     * 산출 프레임 수 — <b>실제 프레임 수</b>이며 원본벌+비식별벌 <b>합계가 아니다</b>.
     *
     * <p>관제 {@code datasets.img_nocs} · {@code dataset_versions.data_etbl_nocs} 에 그대로 적재되고
     * 그 정의는 "추출·라벨링 프레임 수"다. 2벌 쓰기 건수를 합산하면 2배로 부풀고 1벌만 산출하는
     * 파생영상(증강·해상도)과 값의 축이 갈린다. 산정 규칙은 {@code DatasetExportService} 한 곳에
     * 있으므로 이 값을 소비·재계산하는 쪽에서 다시 유도하지 않는다.
     */
    @Column(name = "FRME_CNT")
    private Integer frameCnt;

    /**
     * 데이터구축용량(V173, @req R4) — 이 버전의 산출 폴더({@code {영상루트}/v{n}}) 총 바이트.
     * 관제 {@code dataset_versions.data_etbl_cpct} 에 공급한다.
     *
     * <p>{@code null} 은 <b>미산출</b>이다(용량 계산 실패 또는 이 컬럼 신설 이전 행). 용량은 산출물의
     * 부수 정보이므로 계산이 실패해도 export 자체는 성공으로 종결한다 — 용량 때문에 학습데이터
     * 산출이 실패하면 안 된다.
     */
    @Column(name = "DATA_ETBL_CPCT")
    private Long dataEtblCpct;

    /**
     * 산출 시점 라벨 상태의 콘텐츠 해시(SHA-256 hex). 무수정 재승인 멱등 판정 키 —
     * 직전 SUCCEEDED/PARTIAL(멱등 baseline) export 의 해시와 같으면 Phase 4 오케스트레이션이 재산출을 skip 한다.
     * Phase 1/3 경로(3-arg create)에서는 null 로 남을 수 있다(하위호환).
     */
    @Column(name = "CONTENT_HASH", length = 64)
    private String contentHash;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    /**
     * 재시도 횟수 (V136, DEV_FIX H7①) — 실패 회수 잡이 <b>이 행을 기준으로</b> 재산출을 트리거한 누적 횟수.
     *
     * <p>구 구현은 "마지막 성공 이후 쌓인 FAILED 행 수"를 시도 횟수로 삼았는데, 재시도가 항상 FAILED 행을
     * 만들지는 않아(NO_INPUT early return · 버전 채번 소진 · @Async 예외 삼킴) 그 유형에 걸린 영상은
     * 카운트가 고정된 채 <b>무한 재시도</b>됐다. 이제 산출 결과와 무관하게 <b>클레임 시점에</b> 증가하므로
     * max-attempts 가 실제로 걸린다. 값 변경은 원자 UPDATE(claimForRetry) 로만 한다.
     */
    @Column(name = "RTY_NMTM", nullable = false)
    private int rtyNmtm;

    /**
     * 재시도 일시 (V136, DEV_FIX H7③) — 회수 잡이 마지막으로 재산출을 트리거(클레임)한 시각.
     *
     * <p>2노드 Active-Active 에서 Quartz 클러스터링({@code isClustered})이 꺼져 있어도 같은 영상이
     * 동시에 두 번 재산출되지 않도록, 조건부 UPDATE 의 클레임 키로 사용한다(자세한 규칙은
     * {@code LsDatasetExportRepository#claimForRetry}). null = 아직 재시도된 적 없음.
     */
    @Column(name = "RTY_DT")
    private LocalDateTime rtyDt;

    /**
     * 산출 레코드 생성 — 상태는 {@code PENDING} 으로 시작한다(파일 쓰기 전 예약).
     *
     * @param rawSn   대상 영상 ID(LS_DATA_RAW.RAW_SN)
     * @param verNo   산출 버전 순번(≥1, 기존 export 건수 + 1로 도출)
     * @param path    산출 루트 경로({@code {labeling_root}/{RAW_SN}/v{n}})
     */
    public static LsDatasetExport create(Long rawSn, int verNo, String path) {
        return create(rawSn, verNo, path, null);
    }

    /**
     * 산출 레코드 생성(콘텐츠 해시 포함) — Phase 4 승인 오케스트레이션 전용.
     *
     * @param contentHash 산출 시점 라벨 상태 해시(무수정 재승인 멱등 판정 키)
     */
    public static LsDatasetExport create(Long rawSn, int verNo, String path, String contentHash) {
        LsDatasetExport export = new LsDatasetExport();
        export.dataRawSn = rawSn;
        export.exportVerNo = verNo;
        export.exportPathNm = path;
        export.exportSttsCd = STATUS_PENDING;
        export.contentHash = contentHash;
        export.regDt = LocalDateTime.now();
        return export;
    }

    /** 산출 성공 전이 — 상태를 {@code SUCCEEDED} 로 바꾸고 산출 프레임 수를 반영한다(용량 미기록). */
    public void markSucceeded(int frameCnt) {
        markSucceeded(frameCnt, null);
    }

    /**
     * 산출 성공 전이 + 데이터구축용량 반영 (V173, @req R4).
     *
     * @param dataEtblCpct 산출 폴더 총 바이트. {@code null} 이면 <b>기존 값을 지우지 않고</b> 그대로 둔다 —
     *                     용량 계산 실패가 이전에 기록된 값을 되돌리지 않게 한다.
     */
    public void markSucceeded(int frameCnt, Long dataEtblCpct) {
        this.exportSttsCd = STATUS_SUCCEEDED;
        this.frameCnt = frameCnt;
        applyDataEtblCpct(dataEtblCpct);
    }

    /** 일부 산출 전이 — 상태를 {@code PARTIAL} 로 바꾸고 정상 기록된 프레임 수를 반영한다(용량 미기록). */
    public void markPartial(int frameCnt) {
        markPartial(frameCnt, null);
    }

    /** 일부 산출 전이 + 데이터구축용량 반영 (V173, @req R4). {@code null} 규약은 {@link #markSucceeded(int, Long)} 과 동일. */
    public void markPartial(int frameCnt, Long dataEtblCpct) {
        this.exportSttsCd = STATUS_PARTIAL;
        this.frameCnt = frameCnt;
        applyDataEtblCpct(dataEtblCpct);
    }

    /** 용량은 산출된 경우에만 덮어쓴다 — 미산출(null)이 기존 값을 지우지 않는다. */
    private void applyDataEtblCpct(Long dataEtblCpct) {
        if (dataEtblCpct != null) {
            this.dataEtblCpct = dataEtblCpct;
        }
    }

    /** 산출 실패 전이 — 상태를 {@code FAILED} 로 바꾼다. */
    public void markFailed() {
        this.exportSttsCd = STATUS_FAILED;
    }
}
