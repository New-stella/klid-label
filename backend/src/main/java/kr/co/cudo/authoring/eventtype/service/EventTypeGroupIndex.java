package kr.co.cudo.authoring.eventtype.service;

import kr.co.cudo.authoring.eventtype.dto.EventTypeResponse;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 이벤트유형 <b>표시명 그룹</b> 인덱스 — 필터 옵션·필터 키 판정의 계산 결과를 한 덩어리로 담는다.
 *
 * <h3>왜 한 덩어리인가 (Critical)</h3>
 * <p>옵션 목록 · 코드→대표코드 · 대표코드→그룹코드들 · 등록코드 집합은 <b>같은 스냅샷에서 같은
 * 규칙으로</b> 나와야 한다. 따로 계산해 각각 캐시하면 무효화 시점이 어긋나 "드롭다운은 새 그룹인데
 * 필터 매칭은 옛 그룹"이 되고, 마스터를 여러 번 읽어 N+1 도 생긴다. 그래서 {@code findAll()} 1회로
 * 전부 만들고 캐시도 <b>이 인덱스 하나</b>만 한다.
 *
 * <h3>그룹 정의</h3>
 * <p>그룹 = <b>표시명</b>({@code EventTypeDisplayNamePolicy.resolve} 결과, trim 기준)이 같은 유형코드들의
 * 묶음. 대표코드 = 그룹 내 <b>최소 유형코드</b>(정렬 기반 — 실행마다 흔들리면 프리셋 매핑이 조용히
 * 어긋난다). 그룹 대상은 <b>노출 유형</b>(수집대상 + 제외 대분류 아님)뿐이며, 그 밖의 등록 유형은
 * 그룹에 들어가지 않고 {@link #registeredCodes()} 로만 판정된다(= 종전과 같이 자기 자신이 키).
 *
 * <p>이 접기는 <b>영구 병합이 아니다</b> — 관제가 유형명({@code EVNT_NM})을 보내거나 운영자가 표시명을
 * 지정하면 그 유형만 자기 이름을 얻어 그룹이 자동으로 쪼개진다.
 *
 * @param options         필터 드롭다운 옵션(대표코드 오름차순). {@code categoryKey}=대표코드,
 *                        {@code memberCodes}=그룹 전체 코드(오름차순)
 * @param keyByCode       노출 유형코드 → 그 코드가 속한 그룹의 대표코드
 * @param codesByKey      대표코드 → 그룹 전체 코드 집합(오름차순)
 * @param registeredCodes 등록된 <b>전체</b> 유형코드(수집/비수집·제외 무관) — 등록 여부 판정의 원천
 */
public record EventTypeGroupIndex(
        List<EventTypeResponse> options,
        Map<String, String> keyByCode,
        Map<String, Set<String>> codesByKey,
        Set<String> registeredCodes) {

    /** 빈 인덱스(마스터 0건) — 예외 대신 빈 결과를 돌려주는 fail-safe 계약의 기본값. */
    public static final EventTypeGroupIndex EMPTY =
            new EventTypeGroupIndex(List.of(), Map.of(), Map.of(), Set.of());
}
