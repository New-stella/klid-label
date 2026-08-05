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

    /**
     * 라벨셋 버전(V135, C-ISSUE-21) — 이 프레임의 라벨 집합이 실제로 바뀔 때마다 +1 된다.
     *
     * <p><b>엔티티 flush 로는 절대 쓰이지 않는다</b>({@code insertable=false, updatable=false}). 값 변경은
     * {@code LsDataSrcRepository#bumpLabelVersion*} 원자 UPDATE 로만 수행한다. 이렇게 격리한 이유:
     * 프레임 행의 다른 컬럼(설명·개인정보 3필드·비식별 경로)을 dirty-update 하는 트랜잭션이 flush 시
     * <b>전체 컬럼</b>을 SET 하면서(이 엔티티는 {@code @DynamicUpdate} 없음) 자신이 읽었던 낡은 라벨버전을
     * 되돌려 쓰는 lost update 가 발생하기 때문이다. 신규 INSERT 는 DB DEFAULT 0 을 사용한다.
     *
     * <p>원자 UPDATE 이후 같은 영속성 컨텍스트의 엔티티 필드는 stale 이므로, 저장 응답의 새 버전은
     * 호출부가 (잠금 하에 읽은 값 + 1) 로 계산한다.
     */
    @Column(name = "LBL_VER", nullable = false, insertable = false, updatable = false)
    private Long lblVer;

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

    /**
     * ★ 비식별 축 개인정보 3필드 <b>적재 기본값</b> (2026-08-04 사용자 확정) — 프레임 생성 시
     * {@code 익명 Y / 가명 N / 개인정보 N} 을 <b>실제 값으로 INSERT</b> 하고, 이후 <b>라벨링 화면</b>
     * ({@code PUT /v1/frames/{srcSn}/privacy-meta})에서 수정한다. 값의 단일 원천은
     * {@code ExportPrivacyPolicy.DEID_DEFAULT_*} 이며 여기서 <b>참조</b>만 한다(상수 복제 금지 —
     * 2026-08-03 에 복제로 화면↔export 가 어긋난 사고가 있었다).
     *
     * <h3>왜 DB 컬럼 DEFAULT 가 아니라 팩토리인가 (실측 근거)</h3>
     * <p>이 엔티티에는 {@code @DynamicInsert} 가 없어 <b>Hibernate 가 모든 컬럼을 명시적으로</b>
     * INSERT 한다(값이 없으면 명시적 NULL). 따라서 DB DEFAULT 를 걸어도 <b>주 적재 경로에서는 절대
     * 적용되지 않는다</b>. 반대로 관제 인입 축({@code LS_DATA_INGEST}, V170)은 <b>관제가 우리 코드를
     * 거치지 않고 직접 INSERT</b> 하므로 DB DEFAULT 가 유일한 수단이다 — 두 축이 다른 기법을 쓰는
     * 이유는 "누가 INSERT 하는가"가 다르기 때문이다.
     *
     * <h3>⚠ 잃는 것 (사용자 인지·수용 — 되돌리지 말 것)</h3>
     * <p>값이 항상 실재하므로 <b>"사람이 Y 로 판정함"과 "적재 기본값"이 구분되지 않는다</b>.
     * 인입 축 DEFAULT 와 동일한 트레이드오프다.
     *
     * <p>{@code null} 이 남는 경로는 <b>이 변경 이전에 생성된 레거시 행 하나뿐</b>이다. 그래서
     * {@code ExportPrivacyPolicy} 의 프리필 상수는 <b>제거하지 않고 유지</b>한다.
     * (구 서술의 "② 비식별 누락 신고 리셋({@code resetPrivacyMetaByRawSn})" 경로는 <b>폐기</b>됐다 —
     * 2026-08-04 사용자 확정으로 신고가 개인정보 3필드를 리셋하지 않는다. {@code DeidentReportService} 참조.)
     */
    private static final String DEID_ANONYMITY_ON_INSERT =
            kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY;
    private static final String DEID_PSEUDONYMITY_ON_INSERT =
            kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy.DEID_DEFAULT_PSEUDONYMITY;
    private static final String DEID_PRIVACY_INCLUDED_ON_INSERT =
            kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy.DEID_DEFAULT_PRIVACY_INCLUDED;

    /** 원본(RAW) 프레임 row 생성 (videoFrameNo 미지정 = null). */
    public static LsDataSrc create(Long rawSn, long frameNo, String srcFilePathNm, LocalDateTime shtDt) {
        return LsDataSrc.builder()
                .rawSn(rawSn)
                .frameNo(frameNo)
                .srcFilePathNm(srcFilePathNm)
                .shtDt(shtDt)
                .anonyInclYn(DEID_ANONYMITY_ON_INSERT)
                .psdoInclYn(DEID_PSEUDONYMITY_ON_INSERT)
                .prvcInclYn(DEID_PRIVACY_INCLUDED_ON_INSERT)
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
                .anonyInclYn(DEID_ANONYMITY_ON_INSERT)
                .psdoInclYn(DEID_PSEUDONYMITY_ON_INSERT)
                .prvcInclYn(DEID_PRIVACY_INCLUDED_ON_INSERT)
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
                .anonyInclYn(DEID_ANONYMITY_ON_INSERT)
                .psdoInclYn(DEID_PSEUDONYMITY_ON_INSERT)
                .prvcInclYn(DEID_PRIVACY_INCLUDED_ON_INSERT)
                .build();
    }

    /**
     * 파생(증강·해상도) 프레임 row 생성 — 비식별 프레임 경로 + 부모 프레임의 개인정보 3필드(익명/가명/개인정보)를
     * 최초 INSERT 에 함께 담는다(Phase 3 #3 — 파생 프레임 개인정보 복사). 빌더/setter 는 외부에 노출하지 않고
     * 이 팩토리에서만 3필드를 채운다(Mass Assignment 방어 — 임의 필드 주입 차단).
     *
     * <p><b>부모 값이 미입력(null)이면 적재 기본값</b>({@code Y}/{@code N}/{@code N})으로 채운다
     * (2026-08-04) — "값은 항상 실재한다"는 규약을 파생에서도 유지한다. 부모가 null 인 경우는 이 변경
     * 이전에 생성된 <b>레거시 프레임</b>뿐이며(신고 리셋 경로는 폐기됐다), export 결과값은 프리필 상수와
     * 같아 <b>산출물이 달라지지 않는다</b>.
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
                .anonyInclYn(orInsertDefault(anonyInclYn, DEID_ANONYMITY_ON_INSERT))
                .psdoInclYn(orInsertDefault(psdoInclYn, DEID_PSEUDONYMITY_ON_INSERT))
                .prvcInclYn(orInsertDefault(prvcInclYn, DEID_PRIVACY_INCLUDED_ON_INSERT))
                .build();
    }

    /** 부모 계승값이 미입력(null/blank)이면 적재 기본값을 쓴다. */
    private static String orInsertDefault(String inherited, String insertDefault) {
        return (inherited == null || inherited.isBlank()) ? insertDefault : inherited;
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

    /**
     * 라벨셋 버전(V135) — 조회/응답용. 컬럼이 NOT NULL DEFAULT 0 이라 항상 값이 있으나, 신규 INSERT 직후
     * ({@code insertable=false} 라 DB DEFAULT 적용) refresh 전 인스턴스는 null 일 수 있어 0 으로 폴백한다.
     */
    public long getLabelVersion() {
        return lblVer == null ? 0L : lblVer;
    }

    /** blank/null → null(미입력). CHAR(1) 저장 시 공백 패딩 오염 방지 위해 trim 후 판정. */
    private static String normalizeYn(String yn) {
        return (yn == null || yn.isBlank()) ? null : yn.trim();
    }
}
