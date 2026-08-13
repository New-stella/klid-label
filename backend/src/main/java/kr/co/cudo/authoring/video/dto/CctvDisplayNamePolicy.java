package kr.co.cudo.authoring.video.dto;

/**
 * 영상 화면 표시명(CCTV명) 폴백 <b>단일 진실원</b> — 목록·상세가 같은 이름을 그리게 한다.
 *
 * <h3>판정 순서</h3>
 * <ol>
 *   <li>관제 인입 평면값 {@code LS_DATA_INGEST.CCTV_NM}</li>
 *   <li>{@code LS_DATA_RAW.VMS_CCTV_ID}</li>
 *   <li>{@code 영상 #{rawSn}}</li>
 * </ol>
 *
 * <h3>왜 3순위가 필요한가 (@design ERD-012)</h3>
 * <p>관제서버팀 회신(2026-08-12)으로 <b>CCTV 식별자가 없는 영상(수동 업로드 등)이 존재</b>함이 확정되어
 * {@code VMS_CCTV_ID} 의 NOT NULL 을 해제했다(V185). 그래서 기존 2단 폴백만으로는 <b>둘 다 없는 영상이
 * 빈칸으로 표시</b>되어 목록에서 어느 행인지 구분할 수 없다. {@code rawSn} 은 PK 라 항상 있고,
 * {@code 영상 #N} 표기는 화면에서 이미 쓰는 관례다.
 *
 * <p>관제는 이런 영상에 <b>{@code CCTV_NM} 에 대체 표기(수동 업로드 파일명 등)를 채워 보내기로</b> 했으므로
 * 실제로 3순위까지 내려가는 것은 관제가 그마저 비운 경우다 — 그때도 화면은 식별 가능해야 한다.
 *
 * <h3>공백은 "값 없음"이다</h3>
 * <p>빈 문자열·공백만을 값으로 취급하면 화면이 빈칸을 그린다. 세 단계 모두 같은 판정을 쓴다.
 *
 * <p><b>복제 금지</b> — 호출부가 삼항식을 각자 들면 한쪽만 갱신돼 같은 영상이 화면마다 다른 이름으로
 * 보인다. 판정을 바꿔야 하면 이 클래스만 고친다.
 */
public final class CctvDisplayNamePolicy {

    /** 3순위 폴백 표기 접두 — FE 가 이미 쓰는 관례({@code 영상 #201})와 같은 형식이다. */
    private static final String RAW_SN_PREFIX = "영상 #";

    private CctvDisplayNamePolicy() {
    }

    /**
     * 화면 표시명 산출.
     *
     * @param cctvNm    관제 인입 CCTV명(없으면 null)
     * @param vmsCctvId 영상의 CCTV 식별자(V185 이후 null 가능)
     * @param rawSn     영상 PK — 최후 폴백의 근거라 null 이면 폴백이 성립하지 않는다
     * @return 항상 비어 있지 않은 표시명(rawSn 이 null 이고 앞 둘도 비면 null)
     */
    public static String resolve(String cctvNm, String vmsCctvId, Long rawSn) {
        if (hasText(cctvNm)) {
            return cctvNm;
        }
        if (hasText(vmsCctvId)) {
            return vmsCctvId;
        }
        // rawSn 은 PK 라 응답 조립 시점에 항상 있다. 방어적으로만 null 을 허용한다 —
        // 여기서 "영상 #null" 을 만들면 빈칸보다 나쁜(틀린) 식별자를 화면에 박는다.
        return rawSn == null ? null : RAW_SN_PREFIX + rawSn;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
