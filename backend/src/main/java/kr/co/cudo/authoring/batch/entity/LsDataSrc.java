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

    /**
     * 프레임 폐기여부(V179) — "이 프레임을 학습데이터 산출물에서 뺀다"는 <b>사람의 판정</b>.
     * {@code Y}=폐기(산출 제외) / {@code N}=사용(적재 기본값). NULL 이 아니다.
     *
     * <p><b>행을 삭제하지 않고 표시만 한다</b> — {@code LS_DATA_LBL}(라벨)·{@code LS_DATA_LBL_HSTRY}
     * (이력)·{@code LS_LABEL_VERSION}(승인 스냅샷)이 {@code SRC_SN} 을 참조하므로, 행을 지우면 이미
     * 승인·통지된 산출물의 근거가 사라지고 복원이 성립하지 않는다.
     *
     * <p>여부(YN) 도메인은 프로젝트 표준(V85·공공 여부C1) CHAR(1) — {@code @JdbcTypeCode(CHAR)}.
     * 상태 변경은 {@link #discard()}/{@link #restore()} 로만 한다(@Setter 금지 — Mass Assignment 방어).
     *
     * <p><b>폐기/복원 API·목록 필터·export 제외 배선은 후속 단계</b>이며, 이 단계는 컬럼·매핑·
     * 상태 전이 메서드까지만 담당한다(호출자 없음).
     *
     * @design D1
     * @req R4
     * @req R5
     */
    @Column(name = "DSCD_YN", nullable = false, length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String dscdYn;

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
        // 폐기여부는 <생성 통로 전부>가 값을 갖도록 여기서 채운다 (V179). 각 create(...) 팩토리에
        // 개별로 넣지 않는 이유: 팩토리가 늘어날 때 한 곳만 빠지면 그 통로로 만든 프레임만 명시적
        // NULL 로 INSERT 되어 NOT NULL 제약에 걸린다(개인정보 3필드가 겪은 축을 반복하지 않는다).
        // DB DEFAULT 에 기대지 않는 이유는 DSCD_NO 상수 주석 참조.
        this.dscdYn = DSCD_NO;
        this.regDt = LocalDateTime.now();
    }

    /**
     * 폐기여부 값 — 사용(기본값). 프레임 <b>생성 시 실제로 INSERT</b> 된다.
     *
     * <p><b>왜 DB 컬럼 DEFAULT 가 아니라 애플리케이션인가</b>: 이 엔티티에는 {@code @DynamicInsert}
     * 가 없어 Hibernate 가 <b>모든 컬럼을 명시적으로</b> INSERT 한다(값이 없으면 명시적 NULL).
     * 따라서 DB DEFAULT 는 주 적재 경로에서 <b>절대 적용되지 않는다</b>. V179 의 DEFAULT 는 기존 행
     * 채움 + 우리 코드를 거치지 않는 INSERT 를 위한 안전망이다. (개인정보 3필드와 동일한 판단.)
     */
    public static final String DSCD_NO = "N";

    /** 폐기여부 값 — 폐기(산출 제외). */
    public static final String DSCD_YES = "Y";

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
     * <b>외부 산출물 이관</b> 프레임 생성 — 외부에서 이미 라벨링이 끝난 프레임을 그대로 가져온다(ADR-048).
     *
     * <h3>왜 전용 팩토리인가 — 두 프레임 번호를 바꿔 담는 사고를 구조로 막는다</h3>
     * <p>이 경로에는 <b>서로 다른 두 프레임 번호</b>가 동시에 들어온다.
     * <ul>
     *   <li>{@code frameNo}({@code FRM_NO}) — <b>추출 순번</b>. 산출물 폴더 안에서 몇 번째로 담긴
     *       프레임인가이며 우리가 매긴다.</li>
     *   <li>{@code videoFrameNo}({@code VDO_FRM_NO}) — <b>영상 내 실제 위치</b>. 산출물 문서의
     *       프레임 번호를 그대로 옮긴 값이다.</li>
     * </ul>
     * 둘을 바꿔 담으면 재비식별 프레임 재추출이 <b>영상 맨 앞</b>을 뽑아 붙인다(이 저장소에서 실제로
     * 일어난 사고다). 일반 {@code create(...)} 오버로드는 인자 이름만 다를 뿐 서명이 같아 바꿔 넣어도
     * 컴파일이 되므로, 이관 경로는 뜻이 이름에 드러나는 전용 진입점을 쓴다.
     *
     * <h3>비식별 프레임 경로는 "비식별 완료본을 받았을 때만" 채운다</h3>
     * <p>원본으로 받은 산출물의 프레임은 비식별본이 아직 없다. 그 자리에 원본 경로를 넣으면 그 값이
     * 곧 "비식별본"이 되어 마스킹 전 화면이 비식별본으로 서빙된다. 없으면 {@code null} 로 둔다.
     *
     * <h3>개인정보 3필드는 산출물이 준 값을 그대로 쓰되, 착지 여부는 밖에서 정한다</h3>
     * <p>산출물은 프레임마다 익명·가명·개인정보 포함여부를 준다. 그 값이 우리 <b>비식별 축</b> 3필드에
     * 착지하는지는 <b>가져올 때 지정한 비식별 상태</b>에 따라 갈리며, 그 분기의 단일 소유 지점은
     * {@code ExportPrivacyPolicy.importedFrameValuesLandOnDeidentAxis} 다(ERD-031 프레임 행 절). 이
     * 팩토리는 그 판정을 다시 하지 않고 <b>넘어온 값을 담기만</b> 한다 — 판정을 여기서 또 하면 같은
     * 분기가 두 벌이 되어 한쪽만 바뀌었을 때 값이 조용히 어긋난다.
     *
     * <p>넘어온 값이 없으면(원본으로 가져와 착지하지 않는 경우, 또는 산출물이 그 값을 주지 않은 경우)
     * 다른 적재 경로와 <b>같은 적재 기본값</b>으로 시작한다 — 값을 지어내지 않는다. 착지하지 않은 원문은
     * 메타에 그대로 보관되므로 잃지 않는다.
     *
     * @param srcFilePathNm        가져온 프레임 이미지의 저장 경로
     * @param deIdntfSrcFilePathNm 비식별 완료본을 받았을 때의 비식별 프레임 경로. 아니면 {@code null}
     * @param anonyInclYn          산출물이 준 익명정보 포함여부({@code Y}/{@code N}). 착지하지 않거나
     *                             값이 없으면 {@code null} — 적재 기본값이 쓰인다
     * @param psdoInclYn           산출물이 준 가명정보 포함여부. 규칙은 위와 같다
     * @param prvcInclYn           산출물이 준 개인정보 포함여부. 규칙은 위와 같다
     * @design DOMAIN-017
     * @design ERD-031
     * @design ADR-048
     */
    public static LsDataSrc createFromImport(Long rawSn, long frameNo, Long videoFrameNo,
                                             String srcFilePathNm, String deIdntfSrcFilePathNm,
                                             LocalDateTime shtDt, String anonyInclYn,
                                             String psdoInclYn, String prvcInclYn) {
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

    /**
     * 이 프레임을 학습데이터 산출물에서 <b>제외</b>한다(R4). 행을 삭제하지 않고 표시만 바꾼다 —
     * 라벨·이력·승인 스냅샷이 {@code SRC_SN} 을 참조하므로 삭제하면 복원이 성립하지 않는다.
     *
     * <p>@Setter 금지 규약에 따라 상태 변경은 이 메서드와 {@link #restore()} 로만 한다. 두 메서드가
     * 유일한 쓰기 통로이므로 {@code DSCD_YN} 에는 {@code 'Y'}/{@code 'N'} 외의 값이 들어갈 수 없다
     * (CHAR(1) 코드값 오염 차단). 이미 폐기된 프레임에 다시 호출해도 안전하다(멱등).
     *
     * <p><b>호출자는 후속 단계에서 붙인다</b> — 이 단계는 상태 전이 메서드까지만 담당한다.
     * 감사 이력은 {@code LsTaskEventLog.frameDiscarded} 가 별도로 남긴다.
     *
     * @design D1
     * @req R4
     */
    public void discard() {
        this.dscdYn = DSCD_YES;
        this.updDt = LocalDateTime.now();
    }

    /**
     * 폐기했던 프레임을 다시 <b>사용</b> 상태로 되돌린다(R5). 이미 사용 중이어도 안전하다(멱등).
     *
     * @design D1
     * @req R5
     */
    public void restore() {
        this.dscdYn = DSCD_NO;
        this.updDt = LocalDateTime.now();
    }

    /** 이 프레임이 폐기 표시된 상태인가. 레거시 행 방어로 null 은 <b>사용 중</b>으로 읽는다. */
    public boolean isDiscarded() {
        return DSCD_YES.equals(dscdYn);
    }

    /** blank/null → null(미입력). CHAR(1) 저장 시 공백 패딩 오염 방지 위해 trim 후 판정. */
    private static String normalizeYn(String yn) {
        return (yn == null || yn.isBlank()) ? null : yn.trim();
    }
}
