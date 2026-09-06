package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

/**
 * 본인 업로드 자산의 <b>원장 저장 창구가 0행을 돌려줬을 때</b> 무엇을 하는지의 단일 지점.
 *
 * <h3>★ 「조용한 성공」을 막는다</h3>
 * <p>원장 저장 창구 넷({@code upsertOwnedMeta}·{@code updateOwnedVideoColumn}·
 * {@code updateOwnedFrameColumn}·{@code upsertOwnedEventAnnotation})은 <b>출처 판별자와 소유자를
 * 문장 자체에</b> 걸고 「반영된 행 수」를 돌려준다. 0 은 <b>「그 자산이 이 사용자의 포털 자산이
 * 아니다」</b>라는 뜻이며 그 판별자가 <b>두 번째 방어선으로 실제로 발동했다</b>는 신호다.
 * <p>그 값을 버리면 창구는 <b>아무것도 쓰지 않고 200 을 낸다</b> — 사용자는 저장됐다고 믿고,
 * 두 번째 방어선이 발동한 사실을 아무도 모른다. 방어선을 두고 그 발동을 관측하지 않는 것은
 * 방어선이 없는 것과 운영상 같다.
 *
 * <h3>★★ 경고 로그가 아니라 <b>거부</b>를 고른 이유</h3>
 * <ol>
 *   <li><b>0행이 정당한 경우가 없다</b> — 소유·출처는 이미 {@link PortalWorkTargetResolver} 가
 *       판정한 뒤라, 0행은 판정과 실행 사이에 조건이 풀렸거나 호출부의 판정이 틀렸다는 뜻이다.
 *       어느 쪽도 「저장됐다」가 참인 상태가 아니므로 거부가 정상 저장을 막지 않는다.</li>
 *   <li><b>한 요청이 여러 항목을 함께 받는다</b> — 경고만 남기면 일부만 반영된 <b>부분 저장</b>이
 *       남는다. 저장 창구는 「전량 검증 뒤 저장」을 경계로 두고 있어 그 경계와 어긋난다.
 *       예외는 트랜잭션을 되돌려 <b>전부 아니면 전무</b>를 지킨다.</li>
 * </ol>
 * <p>응답 코드는 <b>대상 판정과 같은 값</b>({@link ErrorCode#FORBIDDEN})을 쓴다 — 사유가 같기
 * 때문이고, 다른 코드를 쓰면 응답 자체가 자산의 실재 여부를 알려 주는 단서가 된다.
 *
 * <p>⚠ 로그에는 <b>축 이름과 식별자만</b> 남긴다. 요청받은 키·값을 실으면 개행이 섞인 입력이
 * 로그를 위조한다(CWE-117). 축 이름은 호출부가 넘기는 <b>고정 문자열</b>이지 사용자 입력이 아니다.
 *
 * @design API-235
 * @design API-237
 */
@Slf4j
final class PortalOwnedLedgerGuard {

    private PortalOwnedLedgerGuard() {
    }

    /**
     * 원장 저장 창구의 반영 행 수를 판정한다.
     *
     * @param affectedRows 창구가 돌려준 반영 행 수
     * @param axis         어느 창구인지 나타내는 <b>고정 문자열</b>(사용자 입력 아님)
     * @param identifier   대상 식별자(영상 또는 프레임 PK)
     * @throws CustomException 0행이면 {@link ErrorCode#FORBIDDEN}
     */
    static void requireApplied(int affectedRows, String axis, Long identifier) {
        if (affectedRows > 0) {
            return;
        }
        log.warn("[Portal] owned-ledger write matched no row — rejected axis={} id={}", axis, identifier);
        throw new CustomException(ErrorCode.FORBIDDEN, "본인 작업 대상이 아니거나 존재하지 않습니다.");
    }
}
