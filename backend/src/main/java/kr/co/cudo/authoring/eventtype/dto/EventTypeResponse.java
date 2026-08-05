package kr.co.cudo.authoring.eventtype.dto;

import java.util.List;

/**
 * 이벤트유형 필터 옵션 응답.
 *
 * <p><b>축은 유형(type)이다</b> (V168 — 구 카테고리 축 폐기). 이벤트유형 마스터
 * ({@code LS_EVNT_TYPE})의 유형 1건이 필터 드롭다운 1행이 된다.
 *
 * <p><b>필드명·타입은 하위호환을 위해 유지</b>한다 — FE({@code EventTypeOption})가 그대로 미러하고
 * 프리셋 저장값·목록 필터 파라미터가 이 값을 되돌려 보낸다. 바뀐 것은 <b>값의 입도</b>뿐이다:
 * {@code categoryKey} 에는 이제 <b>표시명 그룹의 대표 유형코드</b>가, {@code memberCodes} 에는
 * 그 그룹에 속한 유형코드 <b>전부</b>가 담긴다.
 * (필드명을 바꾸면 FE·저장값·북마크가 동시에 깨지므로 의미 변화는 문서로만 고정한다.)
 *
 * <p><b>그룹은 영구 병합이 아니다</b> — 관제가 유형별 이름({@code EVNT_NM})을 보내기 시작하거나
 * 운영자가 표시명을 지정하면 표시명이 갈라져 그룹이 <b>자동으로 쪼개진다</b>.
 *
 * @param categoryKey 필터 키 = 표시명 그룹의 대표 유형코드(그룹 내 최소 코드, 예 "EV02000201")
 * @param label       표시명 — 4단 폴백 결과({@code EventTypeDisplayNamePolicy})
 * @param memberCodes 이 옵션이 매칭하는 EV-코드 목록 — <b>그룹 전체</b>이므로 2건 이상일 수 있다
 */
public record EventTypeResponse(String categoryKey, String label, List<String> memberCodes) {

    /** 카테고리 키·라벨·소속 코드로 응답을 생성한다. memberCodes 는 방어적 복사한다. */
    public static EventTypeResponse from(String categoryKey, String label, List<String> memberCodes) {
        return new EventTypeResponse(categoryKey, label, List.copyOf(memberCodes));
    }
}
