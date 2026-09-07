package kr.co.cudo.authoring.sysconfig.service;

import kr.co.cudo.authoring.sysconfig.dto.VerificationEventQuestionResponse;
import kr.co.cudo.authoring.sysconfig.entity.LsVrfcEvntQstn;
import kr.co.cudo.authoring.sysconfig.repository.LsVrfcEvntQstnRepository;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * ★ 이벤트 어노테이션 <b>질문 칸 조달 판정기</b> — 단일 진실원. [design: ERD-033]
 *
 * <h3>이 클래스가 하나여야 하는 이유</h3>
 * <p>마킹·배치·어노테이션 <b>세 소비자가 같은 질문을 봐야 한다</b>. 판정을 복제하면 한쪽만 고쳐져
 * 화면이 보여준 질문과 산출물에 실린 질문이 조용히 어긋난다 — 이 저장소의 반복 결함 패턴이며 확정
 * 정책이 그것을 금지한다. <b>「첫 번째 질문」의 해석은 여기 말고 어디에도 두지 않는다.</b>
 *
 * <h3>조달 규칙</h3>
 * <ol>
 *   <li>마킹에서 작업자가 <b>고른 질문</b>이 있으면 그 값을 쓴다.</li>
 *   <li>값이 없거나 <b>그 유형에 속한 질문이 아니면</b> 그 유형의 <b>첫 번째 질문</b>으로 되돌린다 —
 *       화면 입력을 그대로 신뢰하지 않는다({@code LS_MARKING.VRFC_EVNT_QSTN_SN} 에 물리 FK 가 없고,
 *       질문 목록은 전체 교체로 저장되어 <b>가리키던 행이 사라지는 것이 정상 동선</b>이기 때문이다.
 *       참조 무결성을 DB 가 아니라 이 판정기가 갖는다).</li>
 *   <li>마킹을 거치지 않는 경로는 <b>언제나</b> 첫 번째 질문을 쓴다.</li>
 *   <li>★ 영상의 <b>유형을 알 수 없으면(미수신)</b> 작업자가 고른 질문이 있는 한 <b>그 질문을 쓴다</b>.
 *       선택값이 없거나 그 행이 이미 사라졌을 때만 비어 있음이다.</li>
 * </ol>
 *
 * <h3>★ ④ 가 뒤집힌 근거 — 지우지 말 것</h3>
 * <p>종전 규칙은 <b>유형이 미수신이면 선택값이 있어도 버리고</b> 비어 있음을 돌려주는 것이었고, 그
 * 근거는 <i>「유형을 모르면 그 선택값이 어느 유형에 속하는지 판정할 축이 없다. 선택값만 믿고
 * 내보내면 다른 유형의 질문이 어노테이션에 실린다 — 비워 두는 편이 지어내는 것보다 안전하다」</i>
 * 였다. <b>질문이 우리 기록일 뿐이던 동안에는 옳은 판단이었다</b> — 사업자가 실제로 쓴 질문은 따로
 * 있었으므로 우리 기록이 틀리면 안 됐기 때문이다.
 *
 * <p>전제 둘이 바뀌어 뒤집혔다. <b>첫째</b>, 추가 질문 축의 창구가 질문 문구를 <b>요청 본문에 직접
 * 싣는</b> 쪽으로 바뀌어 이 값이 기록이 아니라 <b>실제로 보내는 값</b>이 됐다 — 비우면 그 영상은
 * 추가 질문 축 위탁 자체가 나가지 못한다(그 창구는 질문 문구가 필수다). <b>둘째</b>, 그 창구는
 * <b>이벤트 유형을 받지 않으므로</b> 「다른 유형의 질문이 간다」는 우려가 <b>위탁 축에서 성립하지
 * 않는다</b>. 남는 판단 축은 <b>작업자가 영상을 보고 고른 값 하나뿐</b>이며, 그 값을 쓰는 편이
 * 비우는 것보다 낫다.
 *
 * <p>⚠ 그래도 <b>지어내지는 않는다</b> — 선택값이 가리키던 행이 이미 사라졌으면 유형이 미수신일
 * 때도 비어 있음이다(유형이 없어 첫 번째로 되돌릴 대상도 없다).
 *
 * <h3>★ 없으면 예외가 아니라 「비어 있음」이다</h3>
 * <p>유형이 카탈로그에 없거나 그 유형에 등록된 질문이 0건이면 {@link Optional#empty()} 를 돌려준다.
 * 여기서 예외를 던지면 <b>위탁·마킹이 막힌다</b>. 확정 정책은 검증 이벤트 유형이 미수신이거나 우리
 * 목록 밖이어도 <b>그대로 실어 위탁하고 수용 여부는 사업자 응답이 정한다</b> 이므로, 이 카탈로그는
 * 허용목록이 아니다 — 목록에 없는 유형의 영상도 위탁은 그대로 나가고 <b>질문 칸만 빈다</b>.
 *
 * <p>「첫 번째」의 결정성은 {@code (VRFC_EVNT_TYPE_CD, SORT_SEQ)} 유일 제약 + 정렬순서 오름차순 조회가
 * 함께 받친다. 정렬 없이 조회 순서에 기대면 기본값이 실행마다 달라진다.
 *
 * <p>유형 코드 표기 정규화는 {@link LsDataIngest#normalizeVrfcEvntType(String)} <b>한 함수</b>를
 * 재사용한다 — 그 값은 관제 인입이 실어 보내는 값과 같은 값 공간이고, 정규화 규칙을 복제하면 인입이
 * 대문자로 실어 보낸 값이 여기서만 조달에 실패한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class VerificationEventQuestionResolver {

    private final LsVrfcEvntQstnRepository questionRepository;

    /**
     * 그 유형의 <b>첫 번째 질문</b>(정렬순서 최선두 1건).
     *
     * @param vrfcEvntTypeCd 검증 이벤트 유형 코드(정규화 전 원문 허용). {@code null}·공백이면 비어 있음
     * @return 첫 번째 질문. 유형이 없거나 질문이 0건이면 {@link Optional#empty()} — <b>예외를 던지지 않는다</b>
     */
    public Optional<VerificationEventQuestionResponse> firstQuestion(String vrfcEvntTypeCd) {
        String normalized = LsDataIngest.normalizeVrfcEvntType(vrfcEvntTypeCd);
        if (normalized == null) {
            return Optional.empty();
        }
        return questionRepository.findFirstByVrfcEvntTypeCdOrderBySortSeqAsc(normalized)
                .map(VerificationEventQuestionResponse::from);
    }

    /**
     * 마킹이 고른 질문을 <b>해석</b>한다 — 그 유형에 속하면 그대로, 아니면 첫 번째로 되돌린다.
     * [design: ERD-033] [design: AC-1013]
     *
     * <p>선택값이 그 유형에 속하는지 <b>조달 시점마다</b> 확인한다. 저장 시점에 한 번 검사하는 것으로는
     * 부족하다 — 그 사이에 질문 목록이 전체 교체되어 가리키던 행이 사라졌을 수 있다.
     *
     * <p>★ <b>유형이 미수신이면 작업자가 고른 값이 유일한 판단 축</b>이라 소속 검증 없이 그대로 쓴다.
     * 뒤집힌 근거와 종전 판단이 왜 옳았는지는 클래스 주석 「④ 가 뒤집힌 근거」에 있다.
     *
     * @param selectedQstnSn 마킹이 보관한 선택값({@code LS_MARKING.VRFC_EVNT_QSTN_SN}). {@code null} 허용
     * @param vrfcEvntTypeCd 그 영상의 검증 이벤트 유형 코드(정규화 전 원문 허용). {@code null} 허용
     * @return 조달된 질문. 조달할 것이 하나도 없으면 {@link Optional#empty()} — <b>예외를 던지지 않는다</b>
     */
    public Optional<VerificationEventQuestionResponse> resolve(Long selectedQstnSn, String vrfcEvntTypeCd) {
        String normalized = LsDataIngest.normalizeVrfcEvntType(vrfcEvntTypeCd);
        if (normalized == null) {
            if (selectedQstnSn == null) {
                // 유형도 선택값도 없다 — 되돌릴 대상 자체가 없으므로 지어내지 않는다.
                return Optional.empty();
            }
            // 유형 미수신 — 소속을 판정할 축이 없으나, 그 질문 문구가 곧 위탁 요청 본문에 실려 나가는
            // 값이라 비우면 추가 질문 축 위탁이 아예 못 나간다. 작업자가 고른 값을 그대로 쓴다.
            // 가리키던 행이 사라졌으면 비어 있음이다(없는 질문을 지어내지 않는다).
            return questionRepository.findById(selectedQstnSn)
                    .map(VerificationEventQuestionResponse::from);
        }
        if (selectedQstnSn != null) {
            Optional<LsVrfcEvntQstn> selected = questionRepository.findById(selectedQstnSn)
                    .filter(q -> q.belongsTo(normalized));
            if (selected.isPresent()) {
                return selected.map(VerificationEventQuestionResponse::from);
            }
        }
        return firstQuestion(normalized);
    }

    /**
     * 조달된 질문의 <b>문구</b>만 필요할 때의 편의 진입점 — 어노테이션 질문 칸 채우기 전용.
     *
     * <p>판정은 {@link #resolve(Long, String)} 에 위임한다(여기서 규칙을 다시 쓰지 않는다).
     */
    public Optional<String> resolveQuestionText(Long selectedQstnSn, String vrfcEvntTypeCd) {
        return resolve(selectedQstnSn, vrfcEvntTypeCd)
                .map(VerificationEventQuestionResponse::qstnCn);
    }
}
