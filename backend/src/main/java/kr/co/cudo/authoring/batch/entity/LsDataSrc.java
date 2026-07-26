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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * LS_DATA_SRC: 영상에서 추출한 키프레임 (FRAME_EXTRACT 단계 산출).
 *  - rawSn: LS_DATA_RAW FK (객체 참조 대신 ID 참조)
 *  - frameNo: 추출 순번 (마킹 추출 loop index, 0-base)
 *  - videoFrameNo: 실제 영상 내 디코더 0-base 프레임 위치 (FRM_NO 와 의미 구분, nullable).
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

    @Column(name = "FRM_NO", nullable = false)
    private Long frameNo;

    /** 실제 영상 내 디코더 0-base 프레임 위치. FRM_NO(추출순번)와 의미 구분 — 재비식별 재추출용. nullable. */
    @Column(name = "VDO_FRM_NO", nullable = true)
    private Long videoFrameNo;

    /**
     * 원본 프레임 파일 경로. <b>파생영상(해상도 파생)은 원본 픽셀이 실재하지 않아 null</b> 이다
     * (E-ISSUE-41 정책 A — 없는 원본을 있는 척 기록하지 않는다). 일반 추출 프레임은 항상 채워진다.
     */
    @Column(name = "SRC_FILE_PATH_NM", length = 500)
    private String srcFilePathNm;

    @Column(name = "DE_IDNTF_SRC_FILE_PATH_NM", length = 1000)
    private String deIdntfSrcFilePathNm;

    /** 프레임 설명(작업자 수기, NIA image.description 조달원). null = 미입력/삭제. */
    @Column(name = "FRM_EXPLN", length = 1000)
    private String frmExpln;

    /**
     * 프레임 익명정보 포함여부(작업자 수동입력, V130). null = 미입력.
     * <p>여부(YN) 도메인은 프로젝트 표준(V85·공공 여부C1) CHAR(1) — {@code @JdbcTypeCode(CHAR)}.
     * 값 변경 로직·수동입력 API 는 후속 Phase — 본 Phase 는 스키마+매핑만 담당한다.
     */
    @Column(name = "ANONY_INCL_YN", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String anonyInclYn;

    /** 프레임 가명정보 포함여부(작업자 수동입력, V130). null = 미입력. */
    @Column(name = "PSDO_INCL_YN", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String psdoInclYn;

    /** 프레임 개인정보 포함여부(작업자 수동입력, V130). null = 미입력. */
    @Column(name = "PRVC_INCL_YN", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String prvcInclYn;

    @Column(name = "SHT_DT")
    private LocalDateTime shtDt;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "UPD_DT")
    private LocalDateTime updDt;

    @Builder
    private LsDataSrc(Long rawSn, Long frameNo, Long videoFrameNo, String srcFilePathNm,
                      String deIdntfSrcFilePathNm, LocalDateTime shtDt,
                      String anonyInclYn, String psdoInclYn, String prvcInclYn) {
        this.rawSn = rawSn;
        this.frameNo = frameNo;
        this.videoFrameNo = videoFrameNo;
        this.srcFilePathNm = srcFilePathNm;
        this.deIdntfSrcFilePathNm = deIdntfSrcFilePathNm;
        this.shtDt = shtDt;
        this.anonyInclYn = normalizeYn(anonyInclYn);
        this.psdoInclYn = normalizeYn(psdoInclYn);
        this.prvcInclYn = normalizeYn(prvcInclYn);
        this.regDt = LocalDateTime.now();
    }

    /** 원본(RAW) 프레임 row 생성 (videoFrameNo 미지정 = null). */
    public static LsDataSrc create(Long rawSn, long frameNo, String srcFilePathNm, LocalDateTime shtDt) {
        return LsDataSrc.builder()
                .rawSn(rawSn)
                .frameNo(frameNo)
                .srcFilePathNm(srcFilePathNm)
                .shtDt(shtDt)
                .build();
    }

    /** 원본(RAW) 프레임 row 생성 (실제 영상 프레임 위치 videoFrameNo 보존). */
    public static LsDataSrc create(Long rawSn, long frameNo, Long videoFrameNo, String srcFilePathNm, LocalDateTime shtDt) {
        return LsDataSrc.builder()
                .rawSn(rawSn)
                .frameNo(frameNo)
                .videoFrameNo(videoFrameNo)
                .srcFilePathNm(srcFilePathNm)
                .shtDt(shtDt)
                .build();
    }

    /**
     * 파생 프레임 row 생성 — 비식별 프레임 경로({@code deIdntfSrcFilePathNm})를 최초 INSERT 에 함께 담는다
     * (해상도 파생 Phase C — MEDIUM DB: {@code attachDeidPath} setter dirty-update 제거로 프레임당 UPDATE 왕복 제거).
     */
    public static LsDataSrc create(Long rawSn, long frameNo, Long videoFrameNo, String srcFilePathNm,
                                   String deIdntfSrcFilePathNm, LocalDateTime shtDt) {
        return LsDataSrc.builder()
                .rawSn(rawSn)
                .frameNo(frameNo)
                .videoFrameNo(videoFrameNo)
                .srcFilePathNm(srcFilePathNm)
                .deIdntfSrcFilePathNm(deIdntfSrcFilePathNm)
                .shtDt(shtDt)
                .build();
    }

    /**
     * 파생(증강·해상도) 프레임 row 생성 — 비식별 프레임 경로 + 부모 프레임의 개인정보 3필드(익명/가명/개인정보)를
     * 최초 INSERT 에 함께 담는다(Phase 3 #3 — 파생 프레임 개인정보 복사). 부모에 미입력(null)이면 파생도
     * null 로 시작해 파생 폴백(파생 로직)이 그대로 적용된다. 빌더/setter 는 외부에 노출하지 않고 이 팩토리에서만
     * 3필드를 채운다(Mass Assignment 방어 — 임의 필드 주입 차단).
     */
    public static LsDataSrc create(Long rawSn, long frameNo, Long videoFrameNo, String srcFilePathNm,
                                   String deIdntfSrcFilePathNm, LocalDateTime shtDt,
                                   String anonyInclYn, String psdoInclYn, String prvcInclYn) {
        return LsDataSrc.builder()
                .rawSn(rawSn)
                .frameNo(frameNo)
                .videoFrameNo(videoFrameNo)
                .srcFilePathNm(srcFilePathNm)
                .deIdntfSrcFilePathNm(deIdntfSrcFilePathNm)
                .shtDt(shtDt)
                .anonyInclYn(anonyInclYn)
                .psdoInclYn(psdoInclYn)
                .prvcInclYn(prvcInclYn)
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

    /**
     * 프레임 설명(작업자 수기 자연어)을 갱신한다. @Setter 금지 — 비즈니스 메서드로 상태 변경.
     * <p>blank/null 은 설명 삭제로 간주하여 null 로 정규화한다(데이터마트 NULL 노출 일관성).
     */
    public void updateDescription(String description) {
        this.frmExpln = (description == null || description.isBlank()) ? null : description;
        this.updDt = LocalDateTime.now();
    }

    /**
     * 프레임 개인정보 3필드(익명/가명/개인정보 포함여부)를 작업자 수동입력으로 갱신한다. @Setter 금지 —
     * 비즈니스 메서드로만 상태 변경(Mass Assignment 방어). blank/null 은 미입력(파생 폴백 복귀)으로 null 정규화.
     * 값은 화이트리스트('Y'|'N')로 검증된 요청에서만 전달된다(CHAR(1) 오염 차단은 상위 DTO @Pattern + 서비스).
     */
    public void updatePrivacyMeta(String anonyInclYn, String psdoInclYn, String prvcInclYn) {
        this.anonyInclYn = normalizeYn(anonyInclYn);
        this.psdoInclYn = normalizeYn(psdoInclYn);
        this.prvcInclYn = normalizeYn(prvcInclYn);
        this.updDt = LocalDateTime.now();
    }

    /** blank/null → null(미입력). CHAR(1) 저장 시 공백 패딩 오염 방지 위해 trim 후 판정. */
    private static String normalizeYn(String yn) {
        return (yn == null || yn.isBlank()) ? null : yn.trim();
    }
}
