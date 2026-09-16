package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 포털 데이터셋 영상이 <b>원장에 앉는 규약</b> — 메타 키와 그 값을 되읽는 규칙의 단일 지점(ADR-068).
 *
 * <h3>데이터셋 ↔ 영상 연결은 영상 메타에 둔다</h3>
 * <p>연결 전용 테이블을 두지 않는다(ADR-058 방향). 등록이 쓰는 키와 목록·내려받기가 읽는 키가 <b>같은
 * 상수</b>여야 한다 — 두 곳에 문자열로 적으면 한쪽만 바뀌어 목록이 영영 비거나 문서 블록이 조용히 빈다.
 *
 * <h3>키의 분류</h3>
 * <ul>
 *   <li>{@code portal.*} — 포털 파이프라인 키라 메타 창구에서 <b>읽기 전용</b>으로 분류된다
 *       ({@code PortalMetaKeyPolicy}).</li>
 *   <li>{@code video.*} — 기술메타라 <b>기술메타</b>로 분류된다. 원본 파일명·초당 프레임 수 키는 포털 업로드
 *       원장과 <b>같은 키</b>를 쓴다(같은 사실을 다른 이름으로 적지 않는다).</li>
 * </ul>
 *
 * @design ADR-068
 */
public final class PortalDatasetLedger {

    /** 데이터셋 번호 — 목록 창구의 대상 판정 키. */
    public static final String KEY_DATASET_ID = "portal.dataset_id";

    /** 해제본 안 영상 폴더 이름(영상 키). */
    public static final String KEY_DATASET_VIDEO_KEY = "portal.dataset_video_key";

    /** 원본 파일명 — 포털 업로드 원장과 같은 키다. 목록의 영상 이름과 문서 영상 블록 파일명의 조달처. */
    public static final String KEY_ORIGINAL_FILENAME = PortalUploadLedger.KEY_ORIGINAL_FILENAME;

    /** 초당 프레임 수 — 포털 업로드 원장과 같은 키다. */
    public static final String KEY_FPS = PortalUploadLedger.KEY_FPS;

    /** 가로 크기(픽셀). */
    public static final String KEY_WIDTH = "video.width";

    /** 세로 크기(픽셀). */
    public static final String KEY_HEIGHT = "video.height";

    /** 문서 조립이 되읽는 키 목록. */
    public static final List<String> DOCUMENT_META_KEYS =
            List.of(KEY_ORIGINAL_FILENAME, KEY_FPS, KEY_WIDTH, KEY_HEIGHT);

    private PortalDatasetLedger() {
    }

    /**
     * 동결 메타가 없는 데이터셋 영상의 문서 조립용 <b>영속하지 않는</b> 메타 스냅샷을 만든다.
     *
     * <p>★ 저장하지 않는다 — 동결 메타 원장에 행을 넣으면 그 영상이 승인 동결본을 가진 것처럼 읽힌다.
     * 값이 없거나 해석할 수 없는 칸은 비운다(지어내지 않는다). 원본 경로 칸은 <b>항상 비운다</b> — 포털
     * 산출은 비식별 고정이라 읽히지 않지만, 해제본 절대경로를 그 칸에 올릴 이유가 없다.
     *
     * @param rawSn 영상 식별자
     * @param metas 등록 때 넣은 영상 메타(키 → 값)
     */
    public static LsDatasetVideoMeta transientDocumentMeta(long rawSn, Map<String, String> metas) {
        return LsDatasetVideoMeta.builder()
                .rawSn(rawSn)
                .fps(parseDecimal(metas.get(KEY_FPS)))
                .vdoWdth(parsePositiveInt(metas.get(KEY_WIDTH)))
                .vdoHgt(parsePositiveInt(metas.get(KEY_HEIGHT)))
                .build();
    }

    /** 숫자로 해석할 수 없으면 {@code null}. */
    static BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(value.trim());
            return d.signum() < 0 ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 양의 정수로 해석할 수 없으면 {@code null}. */
    static Integer parsePositiveInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int v = Integer.parseInt(value.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
