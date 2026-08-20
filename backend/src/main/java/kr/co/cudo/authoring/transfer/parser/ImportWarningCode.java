package kr.co.cudo.authoring.transfer.parser;

/**
 * 산출물 검사 경고 사유 코드 — <b>적재를 막지 않는</b> 이상(AC-047)을 기계가 분기할 수 있게 한다.
 *
 * <p>문자열을 호출부에 흩어 두면 화면·응답·테스트가 각자 다른 문자열을 보게 되므로 여기 모은다.
 * 값은 응답에 실려 나가므로 <b>바꾸면 소비자가 깨진다</b> — 뜻이 달라지면 새 코드를 더한다.
 *
 * @design DOMAIN-017
 * @design AC-047
 */
public final class ImportWarningCode {

    /** 짝 문서가 없는 이미지 — 라벨이 없는 프레임으로 적재된다. */
    public static final String UNPAIRED_IMAGE = "UNPAIRED_IMAGE";

    /** 문서는 있으나 그 문서가 가리키는 이미지 파일이 없다. */
    public static final String MISSING_IMAGE_FILE = "MISSING_IMAGE_FILE";

    /** 문서가 선언한 건수와 실제 파일 수가 다르다 — 실제 파일을 기준으로 적재한다. */
    public static final String DECLARED_COUNT_MISMATCH = "DECLARED_COUNT_MISMATCH";

    /** 문서를 읽을 수 없다(형식 오류 등) — 그 문서 하나만 건너뛴다. */
    public static final String UNREADABLE_DOCUMENT = "UNREADABLE_DOCUMENT";

    /** 폴더 안의 문서들이 서로 다른 영상을 가리킨다 — 첫 문서의 영상 정보를 쓴다. */
    public static final String VIDEO_META_CONFLICT = "VIDEO_META_CONFLICT";

    /** 날씨가 저작도구 허용값이 아니다 — 값을 비운다(짐작해 옮기지 않는다). */
    public static final String UNKNOWN_WEATHER = "UNKNOWN_WEATHER";

    /** 시간대 표기를 알 수 없다 — 값을 비운다. */
    public static final String UNKNOWN_TIME_OF_DAY = "UNKNOWN_TIME_OF_DAY";

    /** 계절 표기를 알 수 없다 — 값을 비운다. */
    public static final String UNKNOWN_SEASON = "UNKNOWN_SEASON";

    /** 좌표가 짝을 이루지 않는 등 다각형을 읽을 수 없다 — 그 도형 하나를 버린다. */
    public static final String INVALID_POLYGON = "INVALID_POLYGON";

    /** 다각형이 링을 둘 이상 가진다 — 적재 규칙이 확정되지 않아 그대로 담아 둔다. */
    public static final String MULTI_RING_POLYGON = "MULTI_RING_POLYGON";

    /** 경계상자가 들어 있다 — 해석 규칙이 확정되지 않아 원문 그대로 담아 둔다. */
    public static final String UNRESOLVED_BBOX = "UNRESOLVED_BBOX";

    /** 키포인트가 들어 있다 — 해석 규칙이 확정되지 않아 원문 그대로 담아 둔다. */
    public static final String UNRESOLVED_KEYPOINTS = "UNRESOLVED_KEYPOINTS";

    /** 문서가 선언하지 않은 분류가 쓰였다 — 내용으로 도형/텍스트를 갈랐다. */
    public static final String UNDECLARED_CATEGORY = "UNDECLARED_CATEGORY";

    /** 날짜 표기를 읽을 수 없다 — 값을 비운다. */
    public static final String UNPARSABLE_DATE = "UNPARSABLE_DATE";

    /** 숫자여야 할 값이 숫자가 아니다 — 값을 비운다. */
    public static final String UNPARSABLE_NUMBER = "UNPARSABLE_NUMBER";

    /** 영상 파일명을 쓸 수 없다 — 그 값이 없으면 저장 위치를 정할 수 없다(호출부가 fail-closed). */
    public static final String UNUSABLE_VIDEO_FILE_NAME = "UNUSABLE_VIDEO_FILE_NAME";

    private ImportWarningCode() {
    }
}
