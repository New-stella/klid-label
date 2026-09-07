package kr.co.cudo.authoring.evntanno.service;

import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * <b>위탁 시점</b> 질문 조달의 1순위 입력인 「마킹에서 고른 질문」을 그 영상의 마킹에서 읽는다.
 * [design: ADR-036] [design: ERD-013] [design: INTSPEC-003]
 *
 * <p>⚠ <b>소비 시점이 콜백에서 위탁으로 옮겨졌다.</b> 구 판은 결과 수신 시 어노테이션 질문 칸을 채우려고
 * 이 값을 읽었다. 지금은 추가 질문 축이 <b>질문 문구를 요청 본문에 직접</b> 싣게 되면서 위탁 조립부가
 * 읽고, 보낸 문구는 상관키 원장에 보관돼 콜백이 <b>재조달 없이</b> 그 값을 쓴다. 재조달하면 그 사이의
 * 질문 목록 전체 교체로 <b>보낸 질문과 기록된 질문이 갈리기</b> 때문이다.
 *
 * <p>클래스가 이 패키지에 남아 있는 것은 <b>이력</b>이다 — 동작·계약은 그대로이고 부르는 쪽만 바뀌었다.
 *
 * <h3>★ 읽는 선택값이 둘이다 — 이름이 질문 축만 가리키는 것은 이력이다</h3>
 * <p>마킹 화면에서 작업자가 고르는 값은 <b>질문</b>과 <b>검증 이벤트 유형</b> 둘이고, 둘 다 <b>같은
 * 마킹 행</b>에서 읽어야 한다. 그래서 유형 읽기를 별도 클래스로 두지 않고 여기에 더했다 — 나누면 행
 * 선택 규칙이 둘이 되어, 같은 영상에서 질문과 유형이 서로 다른 마킹에서 올 수 있다.
 *
 * <p>⚠ 그래서 클래스 이름({@code …SelectedQuestionReader})이 지금 책임보다 좁다. 개명하지 않은 것은
 * 배치 패키지의 호출부를 함께 바꿔야 하기 때문이며 <b>별도 라운드의 부채</b>다. <b>유형을 읽을 자리를
 * 찾다가 여기를 못 보고 새 리더를 만들지 말 것</b> — 그것이 이 항목을 적어 두는 이유다.
 *
 * <h3>어느 마킹 행에서 읽는가 — 활성 우선, 없으면 최신 한 건</h3>
 * <ol>
 *   <li><b>활성 마킹</b>({@link LsMarking#ACTIVE_STATUSES} — 위탁 사이클이 끝나지 않은 상태)에서 읽는다.
 *       이 상태의 마킹은 <b>영상당 최대 1건</b>임을 데이터 계층이 보장하므로(부분 유니크 인덱스)
 *       「여러 건 중 어느 것」을 고를 일이 없다.</li>
 *   <li>활성 마킹이 없으면 <b>그 영상의 최신 마킹 한 건</b>에서 읽는다.</li>
 * </ol>
 *
 * <p><b>2단이 필요한 이유 — 읽는 시점에 활성 마킹이 없을 수 있다.</b> 재개 경로(미결 회수·보류 재개)는
 * 마킹이 이미 전이된 뒤에 이 값을 다시 읽을 수 있어, 활성만 보면 <b>고른 질문이 있는데도 비어 보인다</b>.
 * 최신 한 건을 뒤에 두면 어느 경로에서 읽어도 같은 질문이 나온다.
 *
 * <p>⚠ 구 근거(콜백 도착 순서로 기록되는 질문이 갈리는 문제)는 <b>보관 도입으로 소멸했다</b> — 콜백은
 * 이제 마킹을 읽지 않고 원장에 보관된 「보낸 값」을 쓴다. 2단 자체는 위 이유로 그대로 유지한다.
 *
 * <h3>여기서 「소속」과 「첫 번째」를 판정하지 않는다</h3>
 * <p>이 클래스는 <b>선택값(일련번호)을 읽어 넘기기만</b> 한다. 그 값이 영상의 검증 이벤트 유형에 속하는지,
 * 속하지 않으면 무엇으로 되돌릴지는 조달 판정기({@code VerificationEventQuestionResolver})가 단일
 * 진실원으로 소유한다. 판정을 여기 복제하면 화면이 보여준 질문과 산출물에 실린 질문이 조용히 어긋난다.
 *
 * <p><b>읽기 전용</b>이다 — 마킹을 수정하지 않고 호출자의 트랜잭션에 합류해 동작한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkingSelectedQuestionReader {

    private final LsMarkingRepository markingRepository;

    /**
     * 그 영상의 마킹이 고른 질문 일련번호({@code LS_MARKING.VRFC_EVNT_QSTN_SN}).
     *
     * <p>고를 마킹이 없거나 그 마킹이 질문을 고르지 않았으면 {@code null} 이다 — 그 경우 조달 판정기가
     * 그 유형의 첫 번째 질문으로 되돌린다. <b>다른 행을 뒤져 값을 찾아 오지 않는다</b>: 규칙이 정한
     * 행이 고르지 않았다는 것도 사실이며, 이웃 행에서 주워 오면 어느 마킹의 질문인지 알 수 없게 된다.
     *
     * @param rawSn 영상 번호. {@code null} 이면 {@code null}
     * @return 선택 질문 일련번호 (nullable)
     */
    public Long findSelectedQuestionSn(Long rawSn) {
        return selectMarking(rawSn)
                .map(LsMarking::getVrfcEvntQstnSn)
                .orElse(null);
    }

    /**
     * 그 영상의 마킹이 고른 <b>검증 이벤트 유형</b>({@code LS_MARKING.VRFC_EVNT_TYPE_CD}).
     * [design: ERD-013]
     *
     * <p>관제가 유형을 보내지 않은 영상에서만 채워지는 칸이라, 대부분의 영상에서는 {@code null} 이다 —
     * 그것이 정상이며 조달 순서의 1순위(관제 인입 값)가 있다는 뜻이다. 순서 자체는 여기서 판정하지 않고
     * {@link VerificationEventTypeResolver} 가 소유한다.
     *
     * <p>★ <b>질문과 같은 행에서 읽는다</b> — 두 값 모두 {@link #selectMarking} 이 고른 <b>한 행</b>에서
     * 나온다. 유형만 다른 규칙으로 읽으면 같은 영상에서 질문과 유형이 서로 다른 마킹에서 와도 아무도
     * 알아채지 못한다(정규화 전 원문을 그대로 돌려준다 — 정규화는 조달 판정기가 한 함수로 수행한다).
     *
     * @param rawSn 영상 번호. {@code null} 이면 {@code null}
     * @return 작업자가 고른 검증 이벤트 유형 코드 원문 (nullable)
     */
    public String findSelectedVrfcEvntType(Long rawSn) {
        return selectMarking(rawSn)
                .map(LsMarking::getVrfcEvntTypeCd)
                .orElse(null);
    }

    /**
     * <b>이 클래스가 소유하는 단 하나의 행 선택 규칙</b> — 활성 우선, 없으면 최신 한 건.
     *
     * <p>선택값을 읽는 메서드는 <b>전부 이것을 거친다</b>. 규칙을 메서드마다 적으면 값끼리 다른 행에서
     * 나오고, 그 어긋남은 조회 시점에만 드러나 재현이 어렵다.
     */
    private Optional<LsMarking> selectMarking(Long rawSn) {
        if (rawSn == null) {
            return Optional.empty();
        }
        return activeMarking(rawSn).or(() -> latestMarking(rawSn));
    }

    /**
     * 활성(미종결) 마킹 — 데이터 계층이 영상당 1건으로 수렴시키므로 첫 항목이 곧 그 1건이다.
     *
     * <p>상태 집합은 {@link LsMarking#ACTIVE_STATUSES} 를 그대로 쓴다. 문자열을 여기 새로 박으면
     * 그 집합을 조건으로 삼는 부분 유니크 인덱스와 어긋나도 아무도 알아채지 못한다.
     */
    private Optional<LsMarking> activeMarking(Long rawSn) {
        List<LsMarking> active = markingRepository.findByRawSnAndSttsCdIn(rawSn, LsMarking.ACTIVE_STATUSES);
        if (active.size() > 1) {
            // 부분 유니크 인덱스가 막고 있어 도달하지 않아야 하는 상태다. 값이 조용히 갈리는 것보다
            // 관측되는 편이 낫다(선택 자체는 첫 항목으로 진행 — 채움을 멈추면 질문 칸이 통째로 빈다).
            log.warn("[EvntAnno] more than one active marking found rawSn={} count={}", rawSn, active.size());
        }
        return active.stream().findFirst();
    }

    /**
     * 그 영상의 최신 마킹 한 건 — 정렬 기준은 마킹 도메인의 조회 메서드가 소유한다(여기서 다시 정하지 않는다).
     */
    private Optional<LsMarking> latestMarking(Long rawSn) {
        return markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(rawSn).stream().findFirst();
    }
}
