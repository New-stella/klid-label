package kr.co.cudo.authoring.transfer.parser;

/**
 * 마킹 산출물 검사 경고 사유 코드.
 *
 * <h3>왜 라벨링 완료 갈래의 코드 표와 나누는가</h3>
 * <p>두 갈래는 방향이 반대다 — 그쪽은 라벨링이 끝난 결과를 받아 검수만 하고, 이쪽은 시작점만 받아
 * 앞 단계를 전부 밟는다. ADR-053 이 <b>계약을 합치지 않는다</b>고 정했고, 경고 코드는 응답에 실려
 * 나가는 계약의 일부다. 한 표에 섞으면 어느 갈래에서 나올 수 있는 값인지 소비자가 가릴 수 없고,
 * 한쪽 갈래의 사정으로 값을 더할 때마다 다른 갈래의 계약이 함께 흔들린다.
 *
 * <p>값은 응답에 그대로 실린다 — <b>바꾸면 소비자가 깨진다</b>. 뜻이 달라지면 새 코드를 더한다.
 *
 * <h3>★경고가 곧 적재 불가 판정은 아니다</h3>
 * <p>여기 담긴 코드에는 <b>적재를 막는 것과 막지 않는 것이 섞여 있다</b>. 적재 가능 여부의 판정은
 * 응답의 {@code importable} 값 하나가 한다(API-216). 경고 목록을 세어 판정하지 말 것.
 *
 * @design DOMAIN-017
 * @design ADR-053
 * @design API-216
 * @design AC-1033
 */
public final class MarkingImportWarningCode {

    // ------------------------------------------------------------------ 짝짓기 (적재를 막는다)

    /** 마킹 문서가 가리키는 이름의 영상을 훑은 범위 안에서 찾지 못했다. */
    public static final String VIDEO_NOT_FOUND = "VIDEO_NOT_FOUND";

    /**
     * 같은 이름의 영상이 둘 이상이라 <b>어느 쪽인지 정할 수 없다</b>.
     *
     * <p>짐작해 하나를 고르면 다른 영상의 마킹이 엉뚱한 영상에 붙고, 저장된 뒤에는 어느 것이
     * 짐작이었는지 구분할 수 없다(AC-1033).
     */
    public static final String AMBIGUOUS_VIDEO_NAME = "AMBIGUOUS_VIDEO_NAME";

    /** 마킹 문서를 읽을 수 없다(형식 오류 등) — 그 문서 하나만 건너뛴다. */
    public static final String UNREADABLE_DOCUMENT = "UNREADABLE_DOCUMENT";

    /** 마킹 문서에 영상 파일 이름이 없거나 쓸 수 없는 값이다 — 짝을 찾을 축 자체가 없다. */
    public static final String UNUSABLE_VIDEO_FILE_NAME = "UNUSABLE_VIDEO_FILE_NAME";

    /**
     * 영상 파일 이름에서 영상 식별자를 만들 수 없다(확장자를 뗀 값이 비었거나 폭을 넘는다).
     *
     * <p>잘라 담으면 <b>서로 다른 영상이 같은 식별자</b>가 되어 중복 판정이 무너진다.
     */
    public static final String UNUSABLE_CLIP_ID = "UNUSABLE_CLIP_ID";

    /** 마킹 문서에 시점이 하나도 없다 — 예약할 내용이 없다. */
    public static final String NO_MARK_FOUND = "NO_MARK_FOUND";

    /** 영상 식별자가 이미 쓰이고 있다 — 그 항목만 건너뛴다. 조용히 덮어쓰지 않는다(AC-1033). */
    public static final String DUPLICATE_CLIP_ID = "DUPLICATE_CLIP_ID";

    /**
     * 문서에서 역산한 프레임 재생 속도와 영상에서 읽은 값이 허용 오차를 넘어 다르다.
     *
     * <p>어긋난 채로 진행하면 이벤트가 없는 <b>엉뚱한 자리의 프레임</b>을 뽑는다(SEQ-030).
     */
    public static final String FPS_MISMATCH = "FPS_MISMATCH";

    // ------------------------------------------------------------------ 알림 (적재를 막지 않는다)

    /**
     * 영상에서 프레임 재생 속도를 읽지 못했다 — 대조할 수 없어 <b>대조를 건너뛴다</b>.
     *
     * <p>적재를 막지 않는다. 막으면 영상 판독 도구가 없는 환경에서 묶음 전체가 통째로 잠긴다.
     */
    public static final String VIDEO_PROBE_FAILED = "VIDEO_PROBE_FAILED";

    /** 문서에서 프레임 재생 속도를 역산할 수 없다(시점이 하나뿐이거나 시각이 겹친다). */
    public static final String DECLARED_FPS_UNAVAILABLE = "DECLARED_FPS_UNAVAILABLE";

    /**
     * 폴더 안의 바로가기(링크) 항목을 <b>따라가지 않고 건너뛰었다</b>.
     *
     * <p>폴더 위치는 허용 범위 안인지 한 겹만 판정하므로, 링크를 따라가면 그 판정을 통과한 폴더를
     * 통해 허용 범위 밖 파일이 읽힌다(CWE-22/59/367). 건너뛴 사실을 알리지 않으면 항목이 이유 없이
     * 사라진 것이 된다.
     */
    public static final String SYMBOLIC_LINK_SKIPPED = "SYMBOLIC_LINK_SKIPPED";

    /**
     * 깊이·항목 수 상한에 걸려 <b>일부만 훑었다</b>.
     *
     * <p>조용히 자르면 일부만 들어온 것이 전부로 보인다. 집계의 {@code truncated} 와 짝이다.
     */
    public static final String SCAN_LIMIT_EXCEEDED = "SCAN_LIMIT_EXCEEDED";

    /**
     * 어느 마킹 문서도 가리키지 않은 영상이 있다.
     *
     * <p>대상이 아니지만 몇 건인지 알려야 사람이 <b>묶음이 온전한지</b> 판단할 수 있다(SEQ-030).
     */
    public static final String UNMATCHED_VIDEO_PRESENT = "UNMATCHED_VIDEO_PRESENT";

    /**
     * 마킹 문서나 영상의 위치가 원장에 담을 수 있는 길이를 넘는다 — 그 항목은 적재하지 못한다.
     *
     * <p>잘라 담으면 존재하지 않는 자리를 가리켜, 나중에 그 값으로 파일을 열 때 <b>짝을 찾지 못한
     * 것과 구분되지 않는 실패</b>가 된다. 폴더를 저장소 위쪽으로 옮겨 경로를 줄이는 것이 처방이다.
     */
    public static final String PATH_TOO_LONG = "PATH_TOO_LONG";

    private MarkingImportWarningCode() {
    }
}
