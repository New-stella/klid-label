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
 *  - filePath: 원본 프레임 이미지 경로
 *  - srcBkupFilePath: 비식별 영상에서 추출한 동일 프레임 경로.
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

    @Column(name = "FILE_PATH", nullable = false, length = 500)
    private String filePath;

    @Column(name = "SRC_BKUP_FILE_PATH", length = 1000)
    private String srcBkupFilePath;

    @Column(name = "CAPTURED_AT")
    private LocalDateTime capturedAt;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "UPD_DT")
    private LocalDateTime updDt;

    @Builder
    private LsDataSrc(Long rawSn, Integer frameNo, String filePath, LocalDateTime capturedAt) {
        this.rawSn = rawSn;
        this.frameNo = frameNo;
        this.filePath = filePath;
        this.capturedAt = capturedAt;
        this.regDt = LocalDateTime.now();
    }

    /** 원본(RAW) 프레임 row 생성. */
    public static LsDataSrc create(Long rawSn, int frameNo, String filePath, LocalDateTime capturedAt) {
        return LsDataSrc.builder()
                .rawSn(rawSn)
                .frameNo(frameNo)
                .filePath(filePath)
                .capturedAt(capturedAt)
                .build();
    }

    /**
     * 동일 row 의 SRC_BKUP_FILE_PATH 컬럼에 비식별 프레임 경로를 연결한다.
     */
    public void attachDeidPath(String deidFilePath) {
        this.srcBkupFilePath = deidFilePath;
        this.updDt = LocalDateTime.now();
    }

    public String getDeidFilePath() {
        return srcBkupFilePath;
    }
}
