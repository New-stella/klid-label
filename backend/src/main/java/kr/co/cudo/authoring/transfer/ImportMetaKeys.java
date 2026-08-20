package kr.co.cudo.authoring.transfer;

import kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy;

/**
 * 외부 산출물 이관이 <b>메타에 원문 보관</b>하는 값의 열쇠 모음.
 *
 * <h3>왜 보관하는가</h3>
 * <p>산출물 문서의 영상 블록에는 저작도구 스키마에 <b>착지할 컬럼이 없는</b> 값이 섞여 있다
 * (좌표·위치·카메라 높이/방위/관리번호·데이터 출처·이벤트 기록·이벤트 상위 계층 이름). 버리면
 * 되돌릴 수 없으므로 {@code LS_DATA_META} 에 원문 그대로 담는다(ERD-031 메타 절).
 *
 * <h3>개인정보 3필드는 여기 두지 않는다</h3>
 * <p>원천 축 개인정보 3필드의 열쇠는 <b>그 값을 읽는 쪽</b>인 {@link ExportPrivacyPolicy} 가 가진다.
 * 이관 경로는 관제 수신 원장을 거치지 않아 원천 축 착지 컬럼({@code LS_DATA_INGEST.*_INCL_YN})이
 * 없으므로 메타가 그 자리를 대신하는데, 열쇠를 쓰는 쪽과 읽는 쪽에 각각 두면 <b>같은 문자열이 두 벌</b>이
 * 되어 한쪽만 바뀌었을 때 산출물에서 값이 조용히 사라진다. 그래서 이 클래스는 그 상수를 <b>다시 선언하지
 * 않고 참조만</b> 한다.
 *
 * <h3>열쇠 이름 규칙</h3>
 * <p>{@code import.} 접두를 붙여 다른 축({@code vlm.*} · {@code video.*} · 레거시 구간 키)과 겹치지
 * 않게 한다. 겹치면 학습데이터 산출물의 상황묘사 조달({@code VlmDescriptionPolicy})이 이 값을 서술로
 * 잘못 집을 수 있다. 컬럼 폭({@code META_KEY VARCHAR(64)}) 안에 들어오는 길이만 쓴다.
 *
 * @design DOMAIN-017
 * @design ERD-031
 * @design DFEAT-057
 */
public final class ImportMetaKeys {

    /** 열쇠 접두 — 다른 메타 축과 겹치지 않게 한다. */
    public static final String PREFIX = "import.";

    /** 영상 좌표 원문({@code video.coordinates}). */
    public static final String VIDEO_COORDINATES = PREFIX + "video.coordinates";
    /** 영상 위치 원문({@code video.location}). */
    public static final String VIDEO_LOCATION = PREFIX + "video.location";
    /** 카메라 설치 높이 원문({@code video.cctv_height}). */
    public static final String VIDEO_CCTV_HEIGHT = PREFIX + "video.cctv_height";
    /** 카메라 설치 방위 원문({@code video.cctv_azimuth}). */
    public static final String VIDEO_CCTV_AZIMUTH = PREFIX + "video.cctv_azimuth";
    /** 카메라 관리 번호 원문({@code video.cctv_mng_no}). */
    public static final String VIDEO_CCTV_MNG_NO = PREFIX + "video.cctv_mng_no";
    /** 데이터 출처 원문({@code video.data_source}). */
    public static final String VIDEO_DATA_SOURCE = PREFIX + "video.data_source";
    /** 이벤트 기록 원문({@code video.event_log}). */
    public static final String VIDEO_EVENT_LOG = PREFIX + "video.event_log";
    /** 이벤트 상위 계층 이름 원문 — 대응 대상이 아니라 참고 정보다(ERD-031). */
    public static final String VIDEO_EVENT_LEVEL1_NAME = PREFIX + "video.event_level1_name";
    /** @see #VIDEO_EVENT_LEVEL1_NAME */
    public static final String VIDEO_EVENT_LEVEL2_NAME = PREFIX + "video.event_level2_name";
    /** @see #VIDEO_EVENT_LEVEL1_NAME */
    public static final String VIDEO_EVENT_LEVEL3_NAME = PREFIX + "video.event_level3_name";
    /** 산출물이 준 영상 식별자 원문({@code video.id}) — 사후 대조용. */
    public static final String VIDEO_EXTERNAL_ID = PREFIX + "video.id";

    /**
     * 원천 축 개인정보 3필드 — {@link ExportPrivacyPolicy} 소유 상수를 <b>참조</b>한다.
     * @see ExportPrivacyPolicy#IMPORT_SOURCE_ANONYMITY_KEY
     */
    public static final String VIDEO_ANONYMITY = ExportPrivacyPolicy.IMPORT_SOURCE_ANONYMITY_KEY;
    /** @see #VIDEO_ANONYMITY */
    public static final String VIDEO_PSEUDONYMITY = ExportPrivacyPolicy.IMPORT_SOURCE_PSEUDONYMITY_KEY;
    /** @see #VIDEO_ANONYMITY */
    public static final String VIDEO_PRIVACY_INCLUDED = ExportPrivacyPolicy.IMPORT_SOURCE_PRIVACY_INCLUDED_KEY;

    /**
     * 프레임 축 개인정보 3필드 원문 — <b>원본이라고 지정해 가져온 경우에만</b> 보관한다.
     *
     * <h3>착지 자리가 축에 따라 갈린다 (ERD-031 프레임 행 절)</h3>
     * <ul>
     *   <li><b>비식별이 끝난 것으로 지정</b> — 그 판정이 우리 비식별 축과 뜻이 같으므로
     *       {@code LS_DATA_SRC} 의 3필드에 <b>프레임마다</b> 적재한다. 여기 보관하지 않는다 —
     *       같은 사실이 두 자리에 있으면 어느 것이 진실인지 갈린다.</li>
     *   <li><b>원본이라고 지정</b> — 그 판정은 <b>원천 축</b>의 사실인데 프레임 축 원천에는 착지 컬럼이
     *       없다. 버리면 되돌릴 수 없으므로 이 열쇠로 원문을 보관한다.</li>
     * </ul>
     * 분기의 단일 소유 지점은 {@code ExportPrivacyPolicy.importedFrameValuesLandOnDeidentAxis} 이며
     * 이관은 그 판정을 복제하지 않는다.
     *
     * <h3>보관 형태 — 관측된 값만 이어 담는다</h3>
     * <p>메타 표는 영상 단위({@code (RAW_SN, META_KEY)} 유일)라 프레임마다 행을 둘 수 없다. 그래서
     * <b>프레임 전체에서 관측된 서로 다른 값</b>을 쉼표로 이어 담는다 — 값이 하나면 그 값 그대로이고,
     * 갈리면 갈렸다는 사실 자체가 남는다. 어느 쪽이든 관측되지 않은 값을 지어내지 않는다.
     *
     * <p>⚠ 보관된 이 값은 <b>산출물의 원천 축 판정에 쓰지 않는다</b>. 확인한 표본에서 프레임 축 값이
     * 비식별 축 기본값과 같은 모양이라, 원천 축에 실으면 "원천 영상인데 익명처리를 거쳤다"는 성립할 수
     * 없는 산출이 나온다. 프레임 축 원천 판정은 종전대로 정책 상수를 쓴다.
     */
    public static final String IMAGE_ANONYMITY = PREFIX + "image.anonymity";
    /** @see #IMAGE_ANONYMITY */
    public static final String IMAGE_PSEUDONYMITY = PREFIX + "image.pseudonymity";
    /** @see #IMAGE_ANONYMITY */
    public static final String IMAGE_PRIVACY_INCLUDED = PREFIX + "image.privacy_included";

    private ImportMetaKeys() {
    }
}
