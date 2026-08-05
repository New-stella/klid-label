package kr.co.cudo.authoring.assignment.service;

import kr.co.cudo.authoring.assignment.dto.EventTypeOptionsResponse;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 작업목록·배정목록 <b>이벤트유형 필터 축</b>의 공용 판정기 — 옵션 접기 + 필터 키 확장.
 *
 * <h3>왜 공용 1곳인가 (Critical)</h3>
 * <p>{@code GET /v1/tasks/board/event-types}(REVIEWER)와 {@code GET /v1/assignments/event-types}
 * (WORKER)는 <b>같은 응답 계약</b>({@link EventTypeOptionsResponse})을 쓰고 FE 도 같은 셀렉트
 * 컴포넌트로 두 경로를 다룬다. 접기 규칙이 두 서비스에 복붙되면 한쪽만 갱신돼 <b>역할에 따라 옵션이
 * 달라지는</b> 화면 분기가 생긴다(이 저장소의 반복 결함 패턴 — 판정 복제). 그래서 두 서비스가 모두
 * 이 빈을 호출한다.
 *
 * <h3>접기 규칙 — 표시명 그룹 (R3)</h3>
 * <p>데이터에 실재하는 EV-코드를 {@link EventTypeService#filterKeyOf(String)} 로 <b>그룹 대표코드</b>에
 * 접는다. 관제가 유형별 이름({@code EVNT_NM})을 아직 보내지 않아 표시명이 카테고리명으로 폴백되면서
 * 드롭다운에 같은 이름이 여러 번(침수 3·교통사고 3) 뜨던 것을 없앤다.
 * <ul>
 *   <li><b>미등록·비규격 코드는 원문을 유지</b>한다 — 버리면 그 코드의 영상이 필터로 도달 불가능해진다
 *       (조용한 데이터 손실). 실제로 {@code INTRUSION} 같은 비규격 코드가 인입 중이다.</li>
 *   <li>노출 대상이 아닌 등록 코드(비수집·제외 대분류)도 {@code filterKeyOf} 가 자기 자신을 키로
 *       돌려주므로 그대로 남는다(종전 동작).</li>
 * </ul>
 *
 * <h3>★ 절단은 <b>접은 뒤</b> 적용한다 (순서가 계약이다)</h3>
 * <p>접기 전에 자르면 ① 잘린 구간에만 존재하던 그룹이 통째로 사라지고(items 손실) ② 접은 결과가
 * 상한보다 훨씬 적은데도 {@code truncated=true} 가 나가 "일부만 표시" 오안내가 뜬다. 그래서
 * <b>접기 → distinct → 정렬 → 상한</b> 순서를 이 메서드 하나가 고정한다.
 *
 * <p><b>스캔 상한은 {@code max + 1} 이 아니다 — {@link #scanLimitFor(int)} 를 써야 한다</b>:
 * 접기가 코드 수를 줄이므로, {@code max + 1} 만 읽으면 "스캔은 잘렸는데 접은 결과는 상한 이하"라
 * <b>절단 사실을 알 수 없는</b> 구간이 생긴다(과소 신고 = 데이터는 있는데 UI 로 도달 불가).
 * 스캔 상한을 {@code max + (노출 그룹 멤버 코드 수) + 1} 로 잡으면, 접기로 줄어드는 최대 개수가
 * 그 멤버 수를 넘을 수 없으므로 <b>스캔이 잘린 경우 접은 결과가 반드시 상한을 넘는다</b>. 덕분에
 * {@code truncated} 는 <b>접은 결과 하나만</b> 보고 판정해도 정확하다.
 *
 * <p>[req: R6]
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventTypeFilterSupport {

    private final EventTypeService eventTypeService;

    /**
     * 리포지토리 스캔 상한 — {@code max + (노출 그룹 멤버 코드 수) + 1}.
     *
     * <p><b>왜 {@code max + 1} 이 아닌가 (Critical)</b>: 접기는 코드 수를 <b>줄인다</b>. {@code max + 1}
     * 만 읽으면 "스캔은 잘렸는데 접은 결과가 상한 이하"인 구간에서 절단을 알아챌 수 없어, 실제로는
     * 옵션이 빠졌는데 {@code truncated=false} 로 나간다(조용한 손실). 접기로 줄어드는 개수는
     * <b>노출 그룹 멤버 코드 수</b>를 넘을 수 없으므로 그만큼 더 읽어 두면, 스캔이 잘린 경우 접은
     * 결과가 <b>반드시</b> 상한을 넘게 되어 {@link #foldOptions} 의 판정만으로 절단이 드러난다.
     *
     * <p>여분은 마스터 크기(수십 행 규모)로 유계이며 값은 캐시된 그룹 인덱스에서 나온다 — 무제한
     * 조회 방어(OWASP API4)는 그대로 유지된다.
     */
    public int scanLimitFor(int max) {
        return max + eventTypeService.validFilterKeys().size() + 1;
    }

    /**
     * 실재 EV-코드 목록을 <b>표시명 그룹 대표코드</b>로 접어 셀렉트 옵션 응답을 만든다.
     *
     * @param scannedCodes 리포지토리가 돌려준 정규화된 실재 코드 목록(중복 없음·오름차순).
     *                     호출자는 {@link #scanLimitFor(int)} 를 상한으로 조회해야 한다.
     * @param max          응답 옵션 개수 상한
     * @param logTag       WARN 로그 도메인 태그(상수 리터럴만 — 사용자 입력을 싣지 않는다, CWE-117)
     */
    public EventTypeOptionsResponse foldOptions(List<String> scannedCodes, int max, String logTag) {
        if (scannedCodes == null || scannedCodes.isEmpty()) {
            return EventTypeOptionsResponse.of(List.of());
        }
        // TreeSet — 접은 뒤 중복 제거 + 오름차순(EventTypeOptionsResponse 계약)을 한 번에 만족시킨다.
        Set<String> folded = new TreeSet<>();
        for (String code : scannedCodes) {
            if (code == null || code.isBlank()) {
                continue;
            }
            // 값은 리포지토리가 이미 필터 입력과 같은 규칙으로 정규화했다 — 여기서 다시 손대면
            //   "옵션에서 고른 값을 그대로 필터로 되돌려 보내면 매칭된다" 는 왕복 계약이 깨진다.
            folded.add(eventTypeService.filterKeyOf(code).orElse(code));
        }

        List<String> items = new ArrayList<>(folded);
        if (items.size() > max) {
            // 카디널리티 이상(코드 오염 등) — 응답을 무제한으로 키우지 않고 잘라 낸다(OWASP API4).
            // ★ 자르는 것은 <접은 뒤>다 — 접기 전에 자르면 잘린 구간의 그룹이 통째로 사라지고,
            //   접은 결과가 상한보다 훨씬 적은데도 truncated=true 가 나가 오안내가 된다.
            log.warn("[{}] eventTypeOptionsTruncated limit={}, folded={}", logTag, max, items.size());
            return EventTypeOptionsResponse.truncated(items.subList(0, max));
        }
        return EventTypeOptionsResponse.of(items);
    }

    /**
     * 필터 키(사용자가 고른 옵션 값) → 실제로 매칭할 <b>EV-코드 집합</b>.
     *
     * <p>옵션이 대표코드로 접혔으므로 필터도 <b>그룹 전체</b>를 봐야 한다 — 단일 코드 동등비교로 두면
     * 대표코드로 필터할 때 그룹의 나머지 코드 영상이 통째로 누락된다. 비대표 코드로 들어온 기존
     * 북마크도 {@link EventTypeService#codesForFilterKey(String)} 가 그룹 전체로 해석한다(하위호환).
     *
     * <p><b>미등록 키는 원문 1건으로 폴백</b>한다 — 옵션 쪽에서 미등록 코드를 원문으로 남기므로,
     * 여기서 빈 집합(=0건)을 돌려주면 그 옵션을 고른 순간 목록이 비어 UI 로 도달할 수 없게 된다.
     * (영상 처리 현황 목록은 "미등록이면 0건" 정책이지만, 그 화면은 옵션을 <b>마스터</b>에서 만들어
     * 미등록 코드가 옵션에 나타나지 않는다 — 옵션 원천이 달라 정책도 다르다.)
     *
     * @return 매칭할 코드 집합. 입력이 null/공백이면 <b>빈 집합 = 필터 미적용</b>.
     */
    public Set<String> matchCodesFor(String filterKey) {
        if (filterKey == null || filterKey.isBlank()) {
            return Set.of();
        }
        Set<String> codes = eventTypeService.codesForFilterKey(filterKey);
        return codes.isEmpty() ? Set.of(filterKey) : Collections.unmodifiableSet(codes);
    }
}
