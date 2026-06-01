package kr.co.cudo.authoring.batch.entity;

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
 * LS_DATA_SRC: 영상에서 추출한 키프레임 (FRAME_EXTRACT 단계 산출).
 *  - rawSn: LS_DATA_RAW FK (객체 참조 대신 ID 참조)
 *  - frameNo: 프레임 인덱스 (0-base)
 *  - srcFilePathNm: 원본 프레임 이미지 경로
 *  - deIdntfSrcFilePathNm: 비식별 영상에서 추출한 동일 프레임 경로.
 *
 * 기존 학습데이터 테이블에는 프레임 타입 컬럼을 추가하지 않는다. 원본과 비식별 프레임을
 * 같은 row 의 파일 경로/백업 파일 경로로 관리한다.
 */
@Entity
@Table(name = "LS_DATA_SRC")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataSrc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "SRC_SN")
    private Long srcSn;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "FRAME_NO", nullable = false)
    private Integer frameNo;

    @Column(name = "SRC_FILE_PATH_NM", nullable = false, length = 500)
    private String srcFilePathNm;

    @Column(name = "DE_IDNTF_SRC_FILE_PATH_NM", length = 1000)
    private String deIdntfSrcFilePathNm;

    @Column(name = "SHT_DT")
    private LocalDateTime shtDt;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "UPD_DT")
    private LocalDateTime updDt;

    @Builder
    private LsDataSrc(Long rawSn, Integer frameNo, String srcFilePathNm, LocalDateTime shtDt) {
        this.rawSn = rawSn;
        this.frameNo = frameNo;
        this.srcFilePathNm = srcFilePathNm;
        this.shtDt = shtDt;
        this.regDt = LocalDateTime.now();
    }

    /** 원본(RAW) 프레임 row 생성. */
    public static LsDataSrc create(Long rawSn, int frameNo, String srcFilePathNm, LocalDateTime shtDt) {
        return LsDataSrc.builder()
                .rawSn(rawSn)
                .frameNo(frameNo)
                .srcFilePathNm(srcFilePathNm)
                .shtDt(shtDt)
                .build();
    }

    /**
     * 동일 row 의 DE_IDNTF_SRC_FILE_PATH_NM 컬럼에 비식별 프레임 경로를 연결한다.
     */
    public void attachDeidPath(String deidFilePath) {
        this.deIdntfSrcFilePathNm = deidFilePath;
        this.updDt = LocalDateTime.now();
    }

    public String getDeidFilePath() {
        return deIdntfSrcFilePathNm;
    }
}
