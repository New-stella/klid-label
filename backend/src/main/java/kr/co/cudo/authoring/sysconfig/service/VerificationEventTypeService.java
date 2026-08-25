package kr.co.cudo.authoring.sysconfig.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.sysconfig.dto.VerificationEventQuestionResponse;
import kr.co.cudo.authoring.sysconfig.dto.VerificationEventQuestionsResponse;
import kr.co.cudo.authoring.sysconfig.dto.VerificationEventQuestionsUpdateRequest;
import kr.co.cudo.authoring.sysconfig.dto.VerificationEventTypeResponse;
import kr.co.cudo.authoring.sysconfig.entity.LsVrfcEvntQstn;
import kr.co.cudo.authoring.sysconfig.entity.LsVrfcEvntType;
import kr.co.cudo.authoring.sysconfig.repository.LsVrfcEvntQstnRepository;
import kr.co.cudo.authoring.sysconfig.repository.LsVrfcEvntTypeRepository;
import kr.co.cudo.authoring.sysconfig.repository.VrfcEvntTypePairRepository;
import kr.co.cudo.authoring.sysconfig.repository.VrfcEvntTypePairRow;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 검증 이벤트 유형·질문 <b>관리</b> 서비스 — REVIEWER 가 유형별 질문 문구를 편집한다.
 * [design: API-219 · API-220]
 *
 * <h3>할 수 있는 것 / 없는 것</h3>
 * <ul>
 *   <li>가능 — 한 유형의 <b>질문 목록 전체 교체</b>(추가·수정·삭제·순서 변경이 이 하나로 처리된다).</li>
 *   <li><b>불가 — 유형 자체의 등록·수정·삭제.</b> 시드는 연동 규격서의 지원 이벤트 목록에서 왔고,
 *       이 경로가 다루는 것은 질문 목록뿐이다.</li>
 * </ul>
 *
 * <h3>★ 이 카탈로그는 허용목록이 아니다 (되돌리지 말 것)</h3>
 * <p>여기에 없는 검증 이벤트 유형의 영상도 위탁은 <b>그대로 나가고</b> 수용 여부는 사업자 응답이
 * 정한다. 목록에 없으면 <b>질문 칸이 비어 있을 뿐</b>이다. 위탁 게이팅에 이 표를 쓰면 과거에 사업자
 * 열거값의 사본이 두 번째 진실원이 되어 정상 값을 우리가 먼저 막은 결함이 재발한다.
 *
 * <h3>★ 관제 코드 짝은 인입 원장을 읽어 만든다</h3>
 * <p>매핑표를 만들지 않는다 — 관제가 인입 행에서 이미 둘을 짝지어 보내며, 사본은 두 번째 진실원이
 * 된다. 한 검증 유형에 관제 코드가 여러 개 붙을 수 있고 하나도 없을 수도 있다(빈 목록).
 *
 * <p><b>보안</b>: 인가는 컨트롤러의 {@code @PreAuthorize("hasRole('REVIEWER')")} + SecurityConfig
 * {@code /v1/manage/**} 매처가 담당한다. 질문 문구 검증은 DTO 의 Bean Validation 이 1차,
 * {@link LsVrfcEvntQstn#isQstnCnValid(String)} 를 부르는 이 서비스가 2차다(<b>같은 함수</b> —
 * 두 곳이 갈라지면 한쪽만 통과하는 값이 조용히 생긴다). 로그에는 요청자·유형코드·건수만 남기고
 * <b>문구 원문은 싣지 않는다</b>(운영자 자유텍스트가 그대로 남는 것을 막는다 — CWE-117).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class VerificationEventTypeService {

    /** 유형코드 로그 절단 기준 — 컬럼 길이(코드V20). */
    private static final int CODE_LOG_LIMIT = 20;

    /** actor(토큰 subject) 로그 절단 기준. */
    private static final int ACTOR_LOG_LIMIT = 64;

    private final LsVrfcEvntTypeRepository typeRepository;
    private final LsVrfcEvntQstnRepository questionRepository;
    private final VrfcEvntTypePairRepository pairRepository;

    /**
     * 등록된 검증 이벤트 유형 전체 — 정렬순서 오름차순. 각 유형에 질문 목록(정렬순서 오름차순)과
     * 관제 이벤트유형 코드 짝을 함께 싣는다.
     *
     * <p><b>질문이 없는 유형도 빠지지 않는다</b> — 그 유형은 어노테이션 질문 칸을 비운 채로 둔다는
     * 사실 자체가 운영자가 봐야 할 상태다.
     *
     * <p>질문은 유형마다 조회하지 않고 <b>한 번에</b> 읽어 메모리에서 묶는다(N+1 회피). 두 표 모두
     * 코드 체계 규모라 성립한다.
     */
    public List<VerificationEventTypeResponse> list() {
        Map<String, List<VerificationEventQuestionResponse>> questionsByType = questionsByType();
        Map<String, List<String>> pairsByType = receivedPairsByType();

        return typeRepository.findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc().stream()
                .map(type -> VerificationEventTypeResponse.of(
                        type,
                        pairsByType.getOrDefault(type.getVrfcEvntTypeCd(), List.of()),
                        questionsByType.getOrDefault(type.getVrfcEvntTypeCd(), List.of())))
                .toList();
    }

    /**
     * 한 유형의 질문 목록을 <b>통째로 교체</b>한다. 받은 배열의 순서가 곧 정렬순서(1부터)이며
     * 첫 번째가 그 유형의 기본 질문이 된다.
     *
     * <h3>★ 한 트랜잭션 안에서 「지우고 다시 넣는다」 — 왜 그 방식인가</h3>
     * <p>{@code (VRFC_EVNT_TYPE_CD, SORT_SEQ)} 유일 제약 때문에 <b>기존 행을 남긴 채 순서를 재배치하면
     * 중간에 제약 위반</b>이 난다(예: [A=1, B=2] 를 [B=1, A=2] 로 바꾸면 B 를 1 로 올리는 순간 A 와
     * 충돌한다). 임시 번호로 우회하는 방법은 그 임시값이 또 다른 충돌 축을 만들고, 순서 갱신 SQL 이
     * 목록 길이만큼 늘어난다.
     * <p>그래서 <b>벌크 삭제(즉시 DB 반영) → 1..N 순번으로 재삽입</b>을 쓴다. 삭제는 JPQL 벌크라
     * 영속성 컨텍스트를 우회해 곧바로 나가므로 이어지는 INSERT 와 순서가 뒤집히지 않는다(지연 flush 에
     * 맡기면 Hibernate 가 INSERT 를 먼저 내보내 제약 위반이 난다).
     * <p><b>전 과정이 한 트랜잭션</b>이라 중간 상태가 다른 요청에 보이지 않는다 — 보이면 그 순간
     * 「첫 번째」가 흔들려 그 사이에 조달되는 질문이 운영자가 의도하지 않은 문구가 된다.
     *
     * <p><b>일련번호는 보존되지 않는다</b>(재삽입이므로). 그래서 교체 전 질문을 가리키던 마킹의
     * 선택값이 더 이상 그 유형의 질문을 가리키지 않을 수 있고, 그때는 확정된 폴백대로
     * {@link VerificationEventQuestionResolver} 가 첫 번째 질문으로 되돌린다. 요청 본문이 문구만 싣고
     * 일련번호를 받지 않으므로 <b>동일성 보존은 애초에 표현할 수 없다</b> — 설계가 그 폴백을 전제한다.
     *
     * @param vrfcEvntTypeCd 대상 유형 코드
     * @param request        교체할 질문 목록 전체(빈 배열이면 그 유형의 질문이 없어진다)
     * @param actor          요청자(감사 — 토큰 subject). {@code null} 허용
     * @return 교체 후 상태
     * @throws CustomException 미등록 유형(404) · 질문 문구 검증 실패(400)
     */
    @Transactional("controlTransactionManager")
    public VerificationEventQuestionsResponse replaceQuestions(
            String vrfcEvntTypeCd,
            VerificationEventQuestionsUpdateRequest request,
            TokenClaims actor) {

        String normalized = LsDataIngest.normalizeVrfcEvntType(vrfcEvntTypeCd);
        LsVrfcEvntType type = normalized == null ? null : typeRepository.findById(normalized).orElse(null);
        if (type == null) {
            // ★ 유형은 이 경로로 만들지 않는다 — 다루는 것은 질문 목록뿐이다.
            throw new CustomException(ErrorCode.NOT_FOUND, "검증 이벤트 유형을 찾을 수 없습니다.");
        }

        List<String> texts = normalizeAndValidate(request);
        String actorId = actorId(actor);

        questionRepository.deleteByVrfcEvntTypeCd(normalized);

        List<LsVrfcEvntQstn> saved = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            saved.add(LsVrfcEvntQstn.create(normalized, i + 1, texts.get(i), actorId));
        }
        List<VerificationEventQuestionResponse> questions = questionRepository.saveAll(saved).stream()
                .map(VerificationEventQuestionResponse::from)
                .toList();

        log.info("[VrfcEvntType] 질문 목록 교체 actor={} code={} count={}",
                LogSanitizer.sanitize(actorId, ACTOR_LOG_LIMIT),
                LogSanitizer.sanitize(normalized, CODE_LOG_LIMIT),
                questions.size());

        return VerificationEventQuestionsResponse.of(normalized, questions);
    }

    /**
     * 질문 문구 정규화 + <b>2차 방어선</b> 검증.
     *
     * <p>판정 함수는 DTO 의 Bean Validation 과 <b>같은</b> {@link LsVrfcEvntQstn} 의 것을 쓴다(상수·정규식
     * 리터럴을 여기에 복제하지 않는다). 컨트롤러를 거치지 않는 호출에도 같은 규칙이 걸리게 하는 것이
     * 목적이다.
     *
     * <p><b>하나라도 어긋나면 요청 전체를 거부한다</b> — 일부만 반영되면 남은 목록의 순서가 운영자가
     * 보낸 것과 달라진다.
     *
     * <p>거부 메시지에 <b>입력 원문을 싣지 않는다</b>(응답 echo 로 로그·화면에 제어문자가 되돌아가는
     * 경로를 만들지 않는다). 위치(몇 번째)만 알려 준다.
     */
    private static List<String> normalizeAndValidate(VerificationEventQuestionsUpdateRequest request) {
        if (request == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "질문 목록은 필수입니다.");
        }
        List<VerificationEventQuestionsUpdateRequest.Item> items = request.questionsOrEmpty();
        List<String> texts = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            VerificationEventQuestionsUpdateRequest.Item item = items.get(i);
            String normalized = item == null ? null : LsVrfcEvntQstn.normalizeQstnCn(item.qstnCn());
            if (!LsVrfcEvntQstn.isQstnCnValid(normalized)) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        (i + 1) + "번째 질문 문구가 올바르지 않습니다. "
                                + "빈 값·개행·제어문자를 넣을 수 없고 "
                                + LsVrfcEvntQstn.QSTN_CN_MAX_LENGTH + "자 이하여야 합니다.");
            }
            texts.add(normalized);
        }
        return texts;
    }

    /** 질문 전체를 유형별로 묶는다 — 정렬순서 오름차순(조회 쿼리가 이미 정렬해 온다). */
    private Map<String, List<VerificationEventQuestionResponse>> questionsByType() {
        Map<String, List<VerificationEventQuestionResponse>> grouped = new LinkedHashMap<>();
        for (LsVrfcEvntQstn question : questionRepository.findAllByOrderByVrfcEvntTypeCdAscSortSeqAsc()) {
            grouped.computeIfAbsent(question.getVrfcEvntTypeCd(), key -> new ArrayList<>())
                    .add(VerificationEventQuestionResponse.from(question));
        }
        return grouped;
    }

    /**
     * 인입 원장에서 <b>실제로 짝지어 수신된</b> 관제 이벤트유형 코드를 검증 유형별로 묶는다.
     *
     * <p>검증 유형 코드의 표기 정규화는 {@link LsDataIngest#normalizeVrfcEvntType(String)} 한 함수로
     * 한다 — 관제가 대문자·공백을 섞어 보낸 값도 카탈로그 키와 맞물리게 하기 위해서다. 관제 코드는
     * 관제가 채번한 식별자라 <b>원문 그대로</b> 둔다(우리가 표기를 바꾸면 화면이 관제 화면과 달라진다).
     *
     * <p>{@link LinkedHashSet} 으로 중복을 접되 쿼리가 준 순서를 유지한다 — 목록 순서가 조회마다
     * 흔들리면 화면이 이유 없이 뒤바뀐다.
     */
    private Map<String, List<String>> receivedPairsByType() {
        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        for (VrfcEvntTypePairRow row : pairRepository.findReceivedPairs()) {
            String typeCd = LsDataIngest.normalizeVrfcEvntType(row.getVrfcEvntTypeCd());
            String evntTypeCd = row.getEvntTypeCd();
            if (typeCd == null || evntTypeCd == null || evntTypeCd.isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(typeCd, key -> new LinkedHashSet<>()).add(evntTypeCd);
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        grouped.forEach((typeCd, codes) -> result.put(typeCd, List.copyOf(codes)));
        return result;
    }

    /** 요청자 식별자(감사) — 인증 컨텍스트가 없으면 {@code null}(기능이 로그 때문에 실패하지 않는다). */
    private static String actorId(TokenClaims actor) {
        return actor == null ? null : actor.sub();
    }
}
