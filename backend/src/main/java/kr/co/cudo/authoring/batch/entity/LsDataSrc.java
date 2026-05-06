package kr.co.cudo.authoring.batch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * LS_DATA_SRC: 영상에서 추출한 키프레임 (FRAME_EXTRACT 단계 산출).
 *  - rawSn: LS_DATA_RAW FK (객체 참조 대신 ID 참조)
 *  - frameNo: 프레임 인덱스 (0-base)
 *  - filePath: 원본 프레임 이미지 경로 (storage.raw-path 기반)
 *  - deidFilePath: 비식별 처리 후 프레임 경로 (조건부; ANONY 영상이면 null)
 */
@Entity
@Table(name = "LS_DATA_SRC",
        uniqueConstraints = @UniqueConstraint(name = "UK_LS_DATA_SRC_RAW_FRAME",
                columnNames = {"RAW_SN", "FRAME_NO"}))
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

    @Column(name = "DEID_FILE_PATH", length = 500)
    private String deidFilePath;

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

    public static LsDataSrc create(Long rawSn, int frameNo, String filePath, LocalDateTime capturedAt) {
        return LsDataSrc.builder()
                .rawSn(rawSn)
                .frameNo(frameNo)
                .filePath(filePath)
                .capturedAt(capturedAt)
                .build();
    }

    /** 비식별 단계가 성공하면 호출. 원본 filePath 는 절대 변경되지 않음 (원본 보존 원칙). */
    public void attachDeidPath(String deidFilePath) {
        this.deidFilePath = deidFilePath;
        this.updDt = LocalDateTime.now();
    }
}
