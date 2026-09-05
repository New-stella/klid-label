package kr.co.cudo.authoring.portal.upload;

import kr.co.cudo.authoring.video.entity.LsDataRaw;

import java.util.Locale;

/**
 * 포털 업로드 자산이 <b>공용 원장 어디에 어떤 값으로 앉는가</b>의 단일 진실원 (ADR-058 흡수).
 *
 * <h3>흡수 대응</h3>
 * <table>
 *   <caption>흡수 전 전용 표 → 공용 원장</caption>
 *   <tr><th>흡수 전</th><th>흡수처</th></tr>
 *   <tr><td>{@code LS_PORTAL_ULD}</td><td>{@code LS_DATA_RAW} + {@code LS_DATA_META}</td></tr>
 *   <tr><td>{@code LS_PORTAL_ULD_FRME}</td><td>{@code LS_DATA_SRC} (신설 컬럼 0)</td></tr>
 *   <tr><td>{@code LS_PORTAL_ULD_LBL}</td><td>{@code LS_DATA_LBL} (소유자 = {@code REG_USER_NO})</td></tr>
 *   <tr><td>{@code LS_PORTAL_TUS_ULD}</td><td>{@code LS_TUS_UPLOAD}</td></tr>
 * </table>
 *
 * <h3>★ 업로드 상태가 <b>없으면</b> 「업로드됨」이다 (2026-09-02 확정, 구속)</h3>
 * <p>흡수 전에는 상태가 NOT NULL 컬럼이라 「아직 없음」을 표현할 수 없었다. 이제 키·값 원장에 살면서
 * 부재가 가능해졌고, 그 부재를 <b>업로드됨으로 읽는다</b>. 적재와 상태 기록이 한 트랜잭션이 아니면
 * 그 틈이 생기는데, 오류로 보면 <b>정상 자산이 잠시 실패로 보인다</b>.
 *
 * <p>⇒ 이 판정이 <b>스윕 후보 조회와 원자 전이 SQL 의 모양을 정한다</b> — 부재를 업로드됨과 같게
 * 다뤄야 하므로 조회는 「상태 행이 없거나 값이 업로드됨」 형태가 되고, 업로드됨에서 출발하는 전이는
 * 단순 UPDATE 가 아니라 <b>삽입 겸 갱신</b>(없으면 넣고 있으면 조건부로 고친다)이 된다.
 *
 * <h3>★ 자산 종류는 보관하지 않는다</h3>
 * <p>흡수 전 {@code ULD_TYPE_CD} 는 <b>착지처를 두지 않기로</b> 확정됐다(ERD-028) — 매체 유형에서
 * 판정하며 판정기는 {@link #assetTypeOf(String)} 한 곳뿐이다. 별도 키를 두면 두 값이 어긋났을 때
 * 어느 쪽이 정본인지 알 수 없는 두 번째 진실원이 된다.
 *
 * @design ADR-058
 * @design ERD-028
 */
public final class PortalUploadLedger {

    private PortalUploadLedger() {
    }

    // ------------------------------------------------------------------
    // 메타 원장 키 (LS_DATA_META.META_KEY)
    // ------------------------------------------------------------------

    /**
     * 포털 파이프라인 전용 키 접두. 관제 행에는 <b>이 접두의 키가 애초에 존재하지 않는다</b> — 그래서
     * 이 키로 대상을 고르는 자리는 관제 영상에 구조적으로 닿지 않는다(ADR-058 이 「전용 컬럼이라야
     * 삭제가 안전하다」를 기각한 근거).
     */
    public static final String PORTAL_KEY_PREFIX = "portal.";

    /** 업로드 처리 상태. <b>행이 없으면 {@link #STATUS_UPLOADED}</b>. */
    public static final String KEY_UPLOAD_STATUS = "portal.upload_status";

    /** 처리 실패 사유. {@link #STATUS_FAILED} 일 때만 채워진다. */
    public static final String KEY_FAIL_REASON = "portal.fail_reason";

    /** 사용자가 올린 원본 파일명(표시용). 기술메타 네임스페이스에 앉는다. */
    public static final String KEY_ORIGINAL_FILENAME = "video.original_filename";

    /** 업로드 시 확정한 매체 유형. 자산 종류 판정의 <b>유일한</b> 원천이기도 하다. */
    public static final String KEY_MIME = "video.mime";

    /** 초당 프레임 수 — 이미 존재하던 기술메타 키를 그대로 쓴다. */
    public static final String KEY_FPS = "video.fps";

    /** 파일 크기(byte) — 이미 존재하던 기술메타 키를 그대로 쓴다. */
    public static final String KEY_FILESIZE = "video.filesize";

    // ------------------------------------------------------------------
    // 업로드 처리 상태 값역 (이 클래스가 단독 소유 — 키·값 표에는 코드값을 걸 자리가 없다)
    // ------------------------------------------------------------------

    /** 업로드 완료(처리 대기). <b>상태 행 부재와 같은 뜻</b>이다. */
    public static final String STATUS_UPLOADED = "UPLOADED";
    /** 프레임 추출 등 후처리 중. */
    public static final String STATUS_PROCESSING = "PROCESSING";
    /** 라벨링 가능. */
    public static final String STATUS_READY = "READY";
    /** 처리 실패 — {@link #KEY_FAIL_REASON} 에 사유가 남는다. */
    public static final String STATUS_FAILED = "FAILED";

    // ------------------------------------------------------------------
    // 자산 종류 (보관하지 않는다 — 매체 유형에서 판정)
    // ------------------------------------------------------------------

    /**
     * 이미지 자산. ★<b>신규 접수는 폐기됐다</b>(영상만 받는다) — 그러나 <b>이미 적재된 이미지 행</b>의
     * 조회·라벨링·내려받기·삭제는 유지되므로 값역에서 빼지 않는다. 빼면 그 행이 판독 불가가 된다.
     */
    public static final String TYPE_IMAGE = "IMAGE";
    /** 영상 자산. */
    public static final String TYPE_VIDEO = "VIDEO";

    /**
     * 매체 유형에서 자산 종류를 판정한다 — <b>판정기는 여기 하나뿐</b>이다.
     *
     * <p>매체 유형을 모르면 {@link #TYPE_VIDEO} 다. 신규 접수가 영상뿐이라 미상은 영상으로 읽는 편이
     * 실제와 가깝고, 이미지로 읽으면 영상 자산이 프레임 목록·재생 동선에서 사라진다.
     */
    public static String assetTypeOf(String mimeTypeNm) {
        if (mimeTypeNm != null && mimeTypeNm.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return TYPE_IMAGE;
        }
        return TYPE_VIDEO;
    }

    /** 상태 문자열 정규화 — {@code null}·공백은 {@link #STATUS_UPLOADED}(부재 = 업로드됨). */
    public static String statusOrUploaded(String stored) {
        return (stored == null || stored.isBlank()) ? STATUS_UPLOADED : stored;
    }

    // ------------------------------------------------------------------
    // 실패 사유 절단 표기 (2026-09-02 확정)
    // ------------------------------------------------------------------

    /**
     * {@code LS_DATA_META.META_VL} 컬럼 폭. 흡수 전 실패 사유 컬럼({@code TEXT})보다 좁아진 것은
     * ADR-058 이 인지·수용한 대가이며, 그래서 <b>잘렸다는 사실이 드러나야</b> 한다.
     */
    public static final int META_VALUE_MAX = 2000;

    /** 절단 표기의 말줄임(U+2026). 본문과 꼬리를 가르는 표식이다. */
    private static final String ELLIPSIS = "…";

    /**
     * 실패 사유를 저장 폭에 맞춰 <b>잘린 사실이 드러나게</b> 줄인다. @design ADR-058, ERD-028
     *
     * <p>형식: {@code {잘린 본문}… (원문 N자 중 M자)}
     *
     * <ul>
     *   <li>⚠ <b>잘리지 않았으면 꼬리를 붙이지 않는다</b> — 모든 값에 붙이면 그 표기가 신호가 되지
     *       못한다. 값만 보고 「잘렸는가」가 판정돼야 한다.</li>
     *   <li>⚠ <b>꼬리까지 폭 안에 들어가야 한다</b> — 본문을 자를 때 꼬리 길이를 먼저 빼고 자른다.
     *       꼬리 길이가 담긴 글자 수에 따라 달라지므로(자릿수) 수렴할 때까지 다시 계산한다.</li>
     *   <li>서로게이트 쌍을 가르지 않는다 — 반쪽 글자를 남기면 표시가 깨진다.</li>
     * </ul>
     *
     * @param reason 원문 사유. {@code null} 이면 {@code null}
     * @return 폭 안에 들어가는 값. 잘렸으면 끝에 원문 길이와 담긴 길이가 붙는다
     */
    public static String truncateFailReason(String reason) {
        if (reason == null) {
            return null;
        }
        int total = reason.length();
        if (total <= META_VALUE_MAX) {
            return reason;
        }
        int body = META_VALUE_MAX;
        for (int i = 0; i < 5; i++) {
            int candidate = META_VALUE_MAX - tailOf(total, body).length();
            if (candidate < 0) {
                candidate = 0;
            }
            if (candidate == body) {
                break;
            }
            body = candidate;
        }
        // 서로게이트 쌍을 가르지 않는다.
        if (body > 0 && body < total && Character.isHighSurrogate(reason.charAt(body - 1))) {
            body--;
        }
        return reason.substring(0, body) + tailOf(total, body);
    }

    private static String tailOf(int total, int kept) {
        return ELLIPSIS + " (원문 " + total + "자 중 " + kept + "자)";
    }

    // ------------------------------------------------------------------
    // 채널 판별 (SQL 조각)
    // ------------------------------------------------------------------

    /**
     * 포털 업로드 자산의 <b>출처 판별자</b> 값. {@link LsDataRaw#SRC_TYPE_PORTAL_ULD} 를 여기서
     * 재선언하지 않고 참조한다(상수 사본 금지).
     */
    public static final String SRC_TYPE = LsDataRaw.SRC_TYPE_PORTAL_ULD;
}
