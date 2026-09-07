package kr.co.cudo.authoring.evntanno.service;

import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 그 영상의 <b>검증 이벤트 유형</b>을 확정된 조달 순서대로 읽어 준다 — <b>이 순서를 아는 유일한 자리</b>.
 * [design: ERD-013] [design: ERD-033] [design: DFEAT-039]
 *
 * <h3>조달 순서 (설계가 정한 것 — 여기서 새로 정하지 않는다)</h3>
 * <ol>
 *   <li><b>관제 인입 값</b> {@code LS_DATA_INGEST.VRFC_EVNT_TYPE_CD} — 있으면 이것이 진실원이다.</li>
 *   <li><b>마킹에서 작업자가 고른 값</b> {@code LS_MARKING.VRFC_EVNT_TYPE_CD} — 관제가 보내지 않은
 *       영상에서만 채워진다.</li>
 *   <li>둘 다 없으면 {@code null} — <b>지어내지 않는다</b>.</li>
 * </ol>
 *
 * <p>★ <b>두 값이 경쟁하지 않는다</b> — 관제 값이 있으면 마킹 화면이 유형 선택을 <b>아예 노출하지 않아</b>
 * 2순위 칸이 비어 있다. 그래서 우선순위 충돌이 구조적으로 발생하지 않는다.
 *
 * <h3>★ 왜 별도 협력자인가 — 순서를 아는 자리가 늘면 갈라진다</h3>
 * <p>이 순서는 소비자가 여럿이다(묘사 축 위탁 조립·이벤트 어노테이션 초안 적재). 소비자마다 같은 순서를
 * 다시 적으면 <b>한쪽만 고쳐도 아무 시험이 죽지 않는</b> 상태가 된다 — 이 저장소는 그 형태의 결함
 * (판정 사본이 앞단에서 가로채기)을 이미 실측으로 겪었다. 그래서 소비자는 <b>부르기만</b> 한다.
 *
 * <p>⚠ <b>남은 사본이 하나 있다</b> — 위탁 조립부({@code VlmTimeseriesStep.resolveEventType})가 아직
 * 같은 순서를 자기 안에 들고 있다. 그쪽을 이 협력자에 위임하는 것이 다음 단계이며, 그때까지 <b>순서를
 * 바꾸려면 두 자리를 함께</b> 봐야 한다(이 사실을 지우지 말 것).
 *
 * <h3>어느 마킹 행에서 읽는가 — 규칙을 여기서 정하지 않는다</h3>
 * <p>행 선택(활성 우선, 없으면 최신 한 건)은 {@link MarkingSelectedQuestionReader} <b>한 곳</b>이
 * 소유한다. 그래서 <b>유형과 질문이 같은 마킹 행</b>에서 나온다 — 여기에 행 선택을 다시 적으면 두 값이
 * 서로 다른 행에서 와도 아무도 알아채지 못한다.
 *
 * <h3>정규화</h3>
 * <p>두 후보 모두 {@link LsDataIngest#normalizeVrfcEvntType(String)} 을 <b>재사용</b>한다(규칙 복제 금지).
 * 복제하면 인입이 실어 보낸 표기와 작업자가 고른 값이 서로 다른 정규화를 타 조용히 어긋난다.
 *
 * <p><b>읽기 전용</b>이며 호출자의 트랜잭션에 합류한다. 값을 판정하지 않는다 — 우리가 아는 유형 목록에
 * 없는 값도 그대로 돌려준다(허용목록 사전 차단은 폐기된 정책이다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationEventTypeResolver {

    private final IngestSourceRepository ingestSourceRepository;
    private final MarkingSelectedQuestionReader markingSelectionReader;

    /**
     * 정규화된 검증 이벤트 유형 — 관제 인입 값 → 마킹에서 고른 값 → {@code null}.
     *
     * @param rawSn 영상 번호. {@code null} 이면 조회 없이 {@code null}
     * @return 정규화된 유형 코드, 어느 쪽에서도 얻지 못하면 {@code null}
     */
    public String resolve(Long rawSn) {
        if (rawSn == null) {
            return null;
        }
        // 영상 행이 없거나 인입 행이 없으면 null 행이 온다.
        IngestSourceRow source = ingestSourceRepository.findSourceMeta(rawSn);
        String ingested = LsDataIngest.normalizeVrfcEvntType(
                source == null ? null : source.getVrfcEvntTypeCd());
        if (ingested != null) {
            return ingested;
        }

        String selected = LsDataIngest.normalizeVrfcEvntType(
                markingSelectionReader.findSelectedVrfcEvntType(rawSn));
        if (selected != null) {
            // 값 자체는 남기지 않는다 — 우리 코드를 거치지 않고 저장될 수 있는 외부 입력이라
            //  로그에 그대로 실으면 로그 인젝션(CWE-117) 표면이 된다.
            log.info("[EvntAnno] verification event type taken from marking selection rawSn={}", rawSn);
        }
        return selected;
    }
}
