package kr.co.cudo.authoring.controlnotify.service;

/**
 * 수정 통지 {@code ver_expln}(관제 {@code dataset_versions.ver_expln}) 문구 판정의 <b>단일 원천</b>.
 *
 * <p>관제 컬럼이 NOT NULL 인데 저작도구가 미전송이라 관제가 스스로 값을 때우고 있었다. 판정 축은
 * {@code ControlNotifyService.sendModified} 가 <b>이미 들고 있는 두 값</b>뿐이며 새 분기 축을 만들지
 * 않는다(연동 개발항목 §1-2).
 *
 * <table>
 *   <tr><th>재생성 동반</th><th>변경 프레임</th><th>문구</th></tr>
 *   <tr><td>아니오</td><td>-</td><td>{@link #META_MODIFIED} — 디스크가 1바이트도 바뀌지 않은 메타 수정</td></tr>
 *   <tr><td>예</td><td>N건</td><td>{@code 라벨 수정 N건} — 라벨 수정 후 재승인</td></tr>
 *   <tr><td>예</td><td>0건</td><td>{@link #REVIEW_COMPLETED} — 그 외 재생성 동반</td></tr>
 * </table>
 *
 * <p><b>건수를 알 수 없는 경로는 지어내지 않는다</b>: 폴백 큐 재조립
 * ({@code ControlNotifyService.dispatchModified(null, rawSn)})과 완료 통지 409 자기치유는 어떤 프레임이
 * 바뀌었는지 큐에 남아 있지 않다. 이 두 경로는 {@link #REVIEW_COMPLETED} 로 폴백하며 추정 건수를
 * 만들지 않는다.
 *
 * <p>문구는 관제 화면·이력에 그대로 노출되는 값이라 PII·경로·식별자를 담지 않는다(CWE-359).
 */
public final class VersionExplanationPolicy {

    /** 재생성 없는 수정(촬영환경·프레임 설명 등 메타 수정) — 산출 파일은 그대로다. */
    public static final String META_MODIFIED = "메타데이터 수정";

    /** 재생성은 동반했으나 변경 프레임 건수를 알 수 없는 경우(자기치유·폴백 재조립 포함). */
    public static final String REVIEW_COMPLETED = "검수 완료";

    private static final String LABEL_MODIFIED_FORMAT = "라벨 수정 %d건";

    /**
     * @param exportRegenerated 이번 통지가 export 폴더 전량 재생성을 동반했는가
     * @param changedFrameCount 변경 프레임 건수 (모르면 0)
     */
    public static String of(boolean exportRegenerated, int changedFrameCount) {
        if (!exportRegenerated) {
            return META_MODIFIED;
        }
        if (changedFrameCount > 0) {
            return LABEL_MODIFIED_FORMAT.formatted(changedFrameCount);
        }
        return REVIEW_COMPLETED;
    }

    private VersionExplanationPolicy() {
    }
}
