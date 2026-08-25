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
 * @param presetLinkStatus 프리셋 <b>연결 상태</b> — 그 유형에 오토라벨 저장 기준이 실제로 걸려 있는지.
 *                     저장값이 아니라 프리셋 해석 결과에서 파생하며, 판정은
 *                     {@code PresetLabelLookupService.resolve} 한 곳이 소유한다(화면·이 DTO 가 다시
 *                     유도하지 않는다). <b>목록 조회에서만 채워진다 — 수정 응답은 null 이다</b>
 *                     (아래 {@link #from(LsEvntType, String)} javadoc 의 근거 참조).
 */
public record EventTypeAdminResponse(String evntTypeCd, String dsplNm, String dsplNmSource,
                                     String optrIndctNm,
                                     String evntNm, String evntCtgryNm, String evntClsfCd,
                                     String evntCtgryCd, String clctYn, LocalDateTime regDt,
                                     PresetLinkStatus presetLinkStatus) {

    /**
     * 엔티티(+카테고리명) → 응답, <b>연결 상태 없이</b>(=null). <b>수정(PATCH) 경로 전용</b>이다.
     *
     * <p>★<b>왜 수정 응답은 연결 상태를 싣지 않는가</b>: 표시명을 바꾸면 표시명 그룹이 쪼개져 그 유형의
     * <b>그룹 대표코드가 바뀌고</b>, 따라서 어느 프리셋이 걸리는지도 바뀐다(ADR-054 가 위험으로 명시한
     * 바로 그 상황이다). 그런데 그룹 인덱스 캐시 무효화는 <b>커밋 이후</b>라 이 트랜잭션 안에서는 여전히
     * <b>수정 전 그룹</b>으로 판정된다. 옛 값을 실어 보내면 "프리셋이 조용히 떨어진" 상태를 정반대로
     * (연결됨으로) 안내하게 되므로, 값을 지어내는 대신 비운다. 화면은 표시명 수정 후 목록을 재조회한다
     * — 표시명 변경은 그 행 하나가 아니라 <b>같은 그룹의 다른 행들도</b> 함께 바꾸므로 어차피 재조회가 맞다.
     *
     * <p>표시명과 그 <b>채택 단계</b>는 {@link EventTypeDisplayNamePolicy} 로만 해석한다(판정 복제 금지 —
     * 한 번 호출해 둘을 함께 받는다). 여부 컬럼은 CHAR(1) 패딩 방어를 위해 trim 한다.
     */
    public static EventTypeAdminResponse from(LsEvntType type, String evntCtgryNm) {
        return from(type, evntCtgryNm, null);
    }

    /**
     * 엔티티(+카테고리명+연결 상태) → 응답. <b>목록 조회 경로</b>가 쓴다. [@design API-185]
     *
     * @param presetLinkStatus 프리셋 해석 결과에서 파생한 연결 상태(호출자가 이미 판정해 넘긴다)
     */
    public static EventTypeAdminResponse from(LsEvntType type, String evntCtgryNm,
                                              PresetLinkStatus presetLinkStatus) {
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
                type.getRegDt(),
                presetLinkStatus);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
