package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;

/**
 * 포털 증강 요청의 <b>실패 사유 문장</b> — 목록·단건이 공유하는 단일 판정기.
 *
 * <h3>★★ 이 값은 외부 채널로 그대로 나간다</h3>
 * <p>포털 회원의 화면에 그대로 찍히는 값이다. 그래서 담을 수 있는 것은 <b>사용자에게 보여 줄 수 있는
 * 문장</b> 하나뿐이고, 아래는 <b>어느 것도 담지 않는다</b>:
 * <ul>
 *   <li>예외 클래스명 · 스택 · 오류 원문</li>
 *   <li>파일 경로 · 저장소 구조</li>
 *   <li>데이터 제약 이름 · 내부 오류 코드 · 연동 상대가 돌려준 원문</li>
 * </ul>
 *
 * <p>⚠ <b>같은 저장소에 이미 어기는 자리가 있다 — 그 방식을 복사하지 말 것.</b> 다른 실패사유 필드는
 * {@code "…실패: " + e.getClass().getSimpleName()} 형태로 <b>예외 클래스명을 그대로</b> 싣는다. 그
 * 필드는 내부 화면용이라 그때는 성립했지만, 이 자리는 외부 채널이라 같은 형태가 곧 정보 노출이다
 * (CWE-209).
 *
 * <h3>왜 <b>고정 문장</b>인가 — 원인 문자열을 나르지 않는다</h3>
 * <p>실패의 실제 사유는 위탁 원장({@code LS_DATA_AUG_JOB})의 오류 코드·메시지에 남는데 그 값들은
 * <b>내부 진단용</b>이며 벤더 응답 원문이 섞일 수 있다. 그것을 걸러 내보내는 방식은 「거르는 규칙을
 * 한 번만 잊으면 새는」 형태다. 그래서 <b>애초에 읽지 않는다</b> — 이 판정기는 요청 상태만 보고
 * 안내 문장을 고르므로 원문이 흘러들 경로가 구조적으로 없다. 설계도 <i>"담을 사유를 알 수 없으면
 * 지어내지 말고 일반적인 실패 안내 문장을 쓴다"</i> 로 같은 태도를 규정한다.
 *
 * <h3>실패 판정 축 둘 — 하나만 보면 샌다</h3>
 * <ul>
 *   <li><b>생성 결과 종결</b>({@link LsDataAug#STTS_REJECTED}) — 위탁 자체가 거부·실패로 확정된 경우</li>
 *   <li><b>비동기 확정 실패 표식</b>({@link LsDataAug#isProcessingFailed()}) — 위탁은 수락됐으나
 *       결과 반입이 영구 실패한 경우. 이때 상태는 {@code ACCEPTED} 로 남으므로 상태만 보면
 *       <b>영원히 「기다리는 중」</b>으로 보인다(관제 화면에서 실제로 겪은 형태다).</li>
 * </ul>
 *
 * <p>그 밖의 상태는 <b>비어 있다</b> — 「비어 있음 = 아직 실패하지 않았다」이며, 판정이 서지 않는
 * 요청을 실패로 단정하지 않는다(fail-closed, ADR-061).
 *
 * <p>⚠ 사용자 취소({@link LsDataAug#STTS_CANCELED})는 <b>실패가 아니다</b> — 취소는 종결이지만 실패
 * 사유를 붙일 일이 아니라 비워 둔다. 포털 채널에는 취소 창구가 없어 이 값이 생길 경로도 없다.
 *
 * @design API-232
 * @design API-233
 * @design ADR-061
 */
public final class PortalAugmentFailureReason {

    /**
     * 실패 안내 문장 — <b>이 클래스가 낼 수 있는 유일한 값</b>이다.
     *
     * <p>무엇이 잘못됐는지를 요청한 사람이 읽을 수 있게 적고, 다음에 무엇을 할 수 있는지까지 담는다
     * (같은 자산에 같은 조건으로 다시 요청하는 것이 정상 동선이다).
     */
    public static final String GENERIC_FAILURE =
            "증강 생성에 실패했습니다. 생성 조건을 바꾸어 다시 요청해 주세요.";

    private PortalAugmentFailureReason() {
    }

    /**
     * 이 요청의 실패 사유 — 실패했으면 안내 문장, 아니면 {@code null}.
     *
     * @param aug 증강 요청 행. {@code null} 이면 판정 대상이 아니므로 {@code null}
     */
    public static String of(LsDataAug aug) {
        if (aug == null) {
            return null;
        }
        boolean failed = LsDataAug.STTS_REJECTED.equals(aug.getAugProcSttsCd())
                || aug.isProcessingFailed();
        return failed ? GENERIC_FAILURE : null;
    }
}
