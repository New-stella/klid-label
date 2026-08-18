package kr.co.cudo.authoring.eventtype.dto;

import kr.co.cudo.authoring.eventtype.entity.LsEvntType;
import kr.co.cudo.authoring.eventtype.policy.EventTypeDisplayNamePolicy;

import java.time.LocalDateTime;

/**
 * 이벤트유형 <b>관리 화면</b> 응답 — 필터 옵션({@link EventTypeResponse})과 달리 <b>등록된 전부</b>를
 * 있는 그대로 보여준다(비수집·제외 대분류 포함).
 *
 * <p>두 DTO 를 합치지 않는 이유: 필터 옵션은 <b>사용자에게 보일 것만</b> 걸러 내보내는 화면 계약이고,
 * 관리 화면은 <b>걸러진 이유(수집여부·대분류)와 표시명의 출처</b>까지 봐야 고칠 수 있다.
 *
 * <p>@design API-185, API-186
 *
 * @param evntTypeCd   이벤트유형코드(PK, 수정 불가 — 영상이 참조하는 식별자)
 * @param dsplNm       <b>표시명</b> — 4단 폴백 해석 결과({@link EventTypeDisplayNamePolicy})
 * @param dsplNmSource {@code dsplNm} 이 <b>어느 단계에서 왔는지</b> —
 *                     {@code operator}(운영자 지정명) / {@code control}(관제 수신 유형명) /
 *                     {@code category}(카테고리명) / {@code code}(유형코드) 중 하나(소문자).
 *                     서버가 채택 단계를 직접 내려주므로 <b>화면이 원본 필드로 폴백을 다시 판정하지
 *                     않는다</b> — 재판정은 곧 두 번째 진실원이라 정책이 바뀔 때 조용히 갈라진다.
 * @param optrIndctNm  운영자 표시명 — <b>수정 가능</b>. 비우면 관제 수신명으로 복귀한다
 * @param evntNm       관제 수신 유형명 — <b>읽기 전용</b>(화면에 "관제 원본"으로 보여준다)
 * @param evntCtgryNm  카테고리명 — 읽기 전용. 유형에 고유 이름이 없을 때 표시명의 출처다
 * @param evntClsfCd   이벤트분류코드(대분류) — 관제 수신값이라 화면에서 수정하지 않는다
 * @param evntCtgryCd  이벤트카테고리코드 — 관제 수신값이라 화면에서 수정하지 않는다
 * @param clctYn       수집여부(Y/N) — 필터 드롭다운 노출 토글(수정 가능)
 * @param regDt        등록일시(자동등록 시각)
 */
public record EventTypeAdminResponse(String evntTypeCd, String dsplNm, String dsplNmSource,
                                     String optrIndctNm,
                                     String evntNm, String evntCtgryNm, String evntClsfCd,
                                     String evntCtgryCd, String clctYn, LocalDateTime regDt) {

    /**
     * 엔티티(+카테고리명) → 응답. 표시명과 그 <b>채택 단계</b>는 {@link EventTypeDisplayNamePolicy}
     * 로만 해석한다(판정 복제 금지 — 한 번 호출해 둘을 함께 받는다).
     * 여부 컬럼은 CHAR(1) 패딩 방어를 위해 trim 한다.
     */
    public static EventTypeAdminResponse from(LsEvntType type, String evntCtgryNm) {
        EventTypeDisplayNamePolicy.Resolved resolved = EventTypeDisplayNamePolicy.resolveWithSource(
                type.getOptrIndctNm(), type.getEvntNm(), evntCtgryNm, type.getEvntTypeCd());
        return new EventTypeAdminResponse(
                type.getEvntTypeCd(),
                resolved.dsplNm(),
                resolved.source().wireValue(),
                type.getOptrIndctNm(),
                type.getEvntNm(),
                evntCtgryNm,
                type.getEvntClsfCd(),
                type.getEvntCtgryCd(),
                trim(type.getClctYn()),
                type.getRegDt());
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
