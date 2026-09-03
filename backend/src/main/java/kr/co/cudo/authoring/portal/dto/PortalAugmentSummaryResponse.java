package kr.co.cudo.authoring.portal.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 포털 증강 요청 현황 목록의 한 행.
 *
 * <h3>화면은 {@link #augSttsCd} 로 분기하지 않는다</h3>
 * <p>요청 상태의 개별 표시 값을 창구가 정하지 않으므로(값역이 열려 있다) 화면이 그 값 자체로 분기할
 * 수 없다. 그래서 <b>결과 도착 여부를 별도 값으로</b> 함께 싣는다({@link #resultReady}).
 *
 * <h3>★ 목록 <b>하나로</b> 세 구분이 성립해야 한다 (ADR-061)</h3>
 * <p>결과 도착 여부 하나로는 <b>기다리는 중과 실패가 갈리지 않는다</b>. 그래서 실패 사유를 행에 함께
 * 싣고, 판정은 <b>이 차례</b>로 한다:
 * <ol>
 *   <li>{@link #resultReady} 가 참 → <b>결과 도착</b></li>
 *   <li>거짓 + {@link #failRsnCn} 이 비어 있음 → <b>결과 대기 중</b></li>
 *   <li>거짓 + {@link #failRsnCn} 이 차 있음 → <b>실패</b></li>
 * </ol>
 * <p>어느 것도 아니면 <b>대기</b>로 둔다(fail-closed). 실패를 가려내려고 행마다 단건 조회를 부르지
 * 않는다 — 그러려고 이 값을 목록에 실었다.
 *
 * @param augSn               증강 요청 식별자
 * @param uldSn               요청 대상이 된 업로드 영상 자산 식별자
 * @param orgnlFileNm         대상 영상의 원본 파일명(표시용). 보관값이 없으면 {@code null}
 * @param requestedAt         요청을 접수한 일시. 목록은 이 값의 내림차순이다
 * @param generationCondition 요청할 때 지정한 생성 조건 — 접수 시 보관한 값 그대로
 * @param augSttsCd           요청 상태
 * @param failRsnCn           실패 사유. 실패한 요청에서만 채워진다. <b>단건 조회와 같은 이름·형태·제약</b>
 *                            이며, 외부 채널로 그대로 나가는 값이라 내부 오류 코드·경로·제약 이름이 아니라
 *                            사용자가 읽을 수 있는 문장이다({@code PortalAugmentFailureReason})
 * @param resultReady         결과 도착 여부
 * @param resultArrivedAt     결과가 도착한 일시. 대기·실패는 {@code null}
 * @design API-232
 * @design ADR-061
 */
public record PortalAugmentSummaryResponse(
        Long augSn,
        Long uldSn,
        String orgnlFileNm,
        LocalDateTime requestedAt,
        Map<String, Object> generationCondition,
        String augSttsCd,
        String failRsnCn,
        boolean resultReady,
        LocalDateTime resultArrivedAt
) {
}
