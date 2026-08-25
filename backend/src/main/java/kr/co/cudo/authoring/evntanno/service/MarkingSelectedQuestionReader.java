package kr.co.cudo.authoring.evntanno.service;

import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 이벤트 어노테이션 질문 칸의 <b>1순위 조달값</b>인 「마킹에서 고른 질문」을 그 영상의 마킹에서 읽는다.
 * [design: ADR-036] [design: ERD-013]
 *
 * <h3>어느 마킹 행에서 읽는가 — 활성 우선, 없으면 최신 한 건</h3>
 * <ol>
 *   <li><b>활성 마킹</b>({@link LsMarking#ACTIVE_STATUSES} — 위탁 사이클이 끝나지 않은 상태)에서 읽는다.
 *       이 상태의 마킹은 <b>영상당 최대 1건</b>임을 데이터 계층이 보장하므로(부분 유니크 인덱스)
 *       「여러 건 중 어느 것」을 고를 일이 없다.</li>
 *   <li>활성 마킹이 없으면 <b>그 영상의 최신 마킹 한 건</b>에서 읽는다.</li>
 * </ol>
 *
 * <p><b>2단이 필요한 이유 — 도착 순서로 값이 갈리는 것을 막는다.</b> 두 위탁 창구(묘사·추가 질문)의
 * 콜백 도착 순서는 보장되지 않는데 <b>마킹 상태를 전이시키는 것은 묘사 축뿐</b>이다. 활성만 보면
 * 묘사가 먼저 도착해 마킹이 종결된 뒤에 온 추가 질문 결과는 읽을 활성 마킹이 없어, <b>같은 영상인데도
 * 도착 순서에 따라 기록되는 질문이 달라진다</b>. 최신 한 건을 뒤에 두면 어느 순서로 와도 같은 질문이
 * 남는다.
 *
 * <p>⚠ <b>인지·수용한 대가</b>: 위탁 뒤에 작업자가 <b>다시 마킹</b>한 다음 늦은 결과가 도착하면 그
 * 새 마킹의 질문을 읽는다. 결함이 아니라 확정된 선택이다.
 *
 * <h3>여기서 「소속」과 「첫 번째」를 판정하지 않는다</h3>
 * <p>이 클래스는 <b>선택값(일련번호)을 읽어 넘기기만</b> 한다. 그 값이 영상의 검증 이벤트 유형에 속하는지,
 * 속하지 않으면 무엇으로 되돌릴지는 조달 판정기({@code VerificationEventQuestionResolver})가 단일
 * 진실원으로 소유한다. 판정을 여기 복제하면 화면이 보여준 질문과 산출물에 실린 질문이 조용히 어긋난다.
 *
 * <p><b>읽기 전용</b>이다 — 마킹을 수정하지 않는다. 호출자(어노테이션 초안 채움)의 트랜잭션에
 * 합류해 동작한다. 마킹의 선택값·상태는 <b>위탁 제출 시점에 이미 커밋</b>돼 있으므로, 호출자가 별도
 * 트랜잭션(REQUIRES_NEW)에서 돌더라도 이 읽기는 영향을 받지 않는다.
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
        if (rawSn == null) {
            return null;
        }
        return activeMarking(rawSn)
                .or(() -> latestMarking(rawSn))
                .map(LsMarking::getVrfcEvntQstnSn)
                .orElse(null);
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
