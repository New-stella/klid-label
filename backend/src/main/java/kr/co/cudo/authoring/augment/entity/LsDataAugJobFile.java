package kr.co.cudo.authoring.augment.entity;

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
 * 증강 위탁 파일 매핑 (LS_DATA_AUG_JOB_FILE, V141) — Phase 7-D.
 *
 * <p>위탁 job 1건이 실어 보낸 {@code input_files[]} 항목 하나를 나타낸다. 위탁 <b>전</b>에
 * {@code (FILE_SEQ, SRC_SN)} 을 못박고, SUCCEEDED 콜백에서 {@code results[]} 의 산출 경로를
 * 같은 행에 되붙인다.
 *
 * <h3>왜 위탁 시점에 못박는가 (데이터 오염 차단)</h3>
 * <p>계약상 {@code results[]} 항목에는 {@code source_file_id} 가 없어 <b>순서</b>로만 입력과
 * 대응시킬 수 있다. 그런데 결과 수신 시점에 프레임을 다시 정렬해 순서를 재계산하면,
 * 위탁~콜백 사이에 프레임이 추가·삭제됐을 때 <b>조용히</b> 어긋나 다른 프레임에 남의 증강본이
 * 붙는다. 위탁 시점 대응을 행으로 남겨 그 재계산 자체를 없앤다.
 */
@Entity
@Table(name = "LS_DATA_AUG_JOB_FILE")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataAugJobFile {

    /** 결과 경로 컬럼 길이(명V500) — 외부 계약 {@code output_file_path} 상한과 동일. */
    private static final int RSLT_PATH_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AUG_JOB_FILE_SN")
    private Long augJobFileSn;

    @Column(name = "AUG_JOB_SN", nullable = false)
    private Long augJobSn;

    /** 위탁 입력 순서({@code input_files[].sequence}) — 증강 1건 전체에서 1부터 증가. */
    @Column(name = "FILE_SEQ", nullable = false)
    private Integer fileSeq;

    /** 위탁한 비식별 프레임(LS_DATA_SRC). 결과를 되붙일 대상. */
    @Column(name = "SRC_SN", nullable = false)
    private Long srcSn;

    /** 외부가 반환한 증강 산출 이미지 경로. 수신 전 null. */
    @Column(name = "RSLT_FILE_PATH_NM", length = RSLT_PATH_MAX)
    private String resultFilePathNm;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;

    private LsDataAugJobFile(Long augJobSn, int fileSeq, Long srcSn) {
        LocalDateTime now = LocalDateTime.now();
        this.augJobSn = augJobSn;
        this.fileSeq = fileSeq;
        this.srcSn = srcSn;
        this.regDt = now;
        this.mdfcnDt = now;
    }

    /** 위탁 <b>직전</b> 선기록 — 순서↔프레임 대응을 못박는다(결과 경로는 콜백에서 채운다). */
    public static LsDataAugJobFile issued(Long augJobSn, int fileSeq, Long srcSn) {
        return new LsDataAugJobFile(augJobSn, fileSeq, srcSn);
    }

    /**
     * SUCCEEDED 콜백의 산출 경로 적재. 길이 상한을 넘는 값은 <b>절단하지 않고 거부</b>한다 —
     * 절단된 경로는 존재하지 않는 파일을 가리켜 조용한 실패가 되기 때문이다.
     */
    public void applyResultPath(String resultFilePathNm) {
        if (resultFilePathNm == null || resultFilePathNm.isBlank()
                || resultFilePathNm.length() > RSLT_PATH_MAX) {
            throw new IllegalArgumentException("증강 산출 경로가 유효하지 않습니다.");
        }
        this.resultFilePathNm = resultFilePathNm;
        this.mdfcnDt = LocalDateTime.now();
    }
}
