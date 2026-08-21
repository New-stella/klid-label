package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.transfer.entity.LsOtsdCtgryMpng;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 처음 보는 외부 분류에 대해 <b>이름이 비슷한 후보</b>를 제시한다. 확정은 하지 않는다.
 *
 * <h3>후보까지이고 확정은 사람이 한다</h3>
 * <p>이름만 보고 짐작해 연결하면 다른 분류로 저장되고, <b>저장된 뒤에는 어느 것이 짐작이었는지
 * 구분할 수 없다</b>. 그래서 이 컴포넌트는 저장 통로를 갖지 않는다 — 결과는 화면에 보여 줄 후보
 * 목록일 뿐이다.
 *
 * <h3>fail-closed — 애매하면 비운다</h3>
 * <p>가장 강한 단계에서 <b>후보가 정확히 하나</b>일 때만 그 하나를 돌려주고, 0건이거나 동점이
 * 여럿이면 <b>빈 목록</b>을 돌려준다. 여럿을 늘어놓으면 사람이 "서버가 고른 것"으로 읽고 그대로
 * 누르기 쉬운데, 그 순간 이 안전장치가 자동 확정과 같아진다.
 *
 * <h3>판정 단계</h3>
 * <ol>
 *   <li>정규화 후 <b>같은 이름</b>(대소문자·공백·기호 무시)</li>
 *   <li>①이 없을 때만 — 정규화 후 <b>한쪽이 다른 쪽을 품는</b> 이름(두 글자 이상일 때만)</li>
 * </ol>
 * <p>두 단계 모두 결정적이다. 편집거리 같은 점수 방식을 쓰지 않는 이유는, 점수가 임계값을 요구하고
 * 그 임계값에는 설계 근거가 없기 때문이다(근거 없는 값이 곧 짐작이 된다).
 *
 * <h3>후보 목록에는 상한을 두지 않는다</h3>
 * <p>상한을 두면 잘린 자리에 동점 후보가 남아 <b>유일하지 않은 것이 유일해 보인다</b> — 안전장치가
 * 거꾸로 뒤집힌다. 후보의 출처는 라벨 마스터와 등록된 이벤트 유형이라 둘 다 사람이 관리하는
 * 경계 있는 표다.
 *
 * @design DOMAIN-017
 * @design API-205
 * @design AC-042
 */
@Component
@RequiredArgsConstructor
public class CategoryMatchSuggester {

    /** 담기 전 이름 정규화 상한 — 비교용 문자열이 응답·로그로 새어 나가지 않게 길이만 제한한다. */
    private static final int NORMALIZED_MAX = 200;

    private final LsLabelRepository labelRepository;
    private final EventTypeService eventTypeService;

    /** 추천 후보 1건 — {@code targetId} 는 라벨이면 라벨 아이디, 이벤트 유형이면 유형 코드다. */
    public record Suggestion(String targetId, String targetName) {
    }

    /** 후보 1건(내부 표현) — 원본 이름과 비교용 정규화 이름을 함께 들고 있는다. */
    record Candidate(String targetId, String targetName, String normalized) {
    }

    /**
     * 한 번의 검사에서 <b>한 번만</b> 읽는 후보 묶음.
     *
     * <p>분류마다 마스터를 다시 읽으면 산출물 분류 수만큼 조회가 늘고, 그 사이에 마스터가 바뀌면
     * 같은 응답 안에서 판정 기준이 갈린다.
     */
    public record Snapshot(List<Candidate> labels, List<Candidate> eventTypes) {
    }

    /** 라벨 마스터와 등록된 이벤트 유형을 한 번에 읽어 후보 묶음을 만든다. */
    @Transactional(readOnly = true)
    public Snapshot snapshot() {
        List<Candidate> labels = new ArrayList<>();
        for (LsLabel label : labelRepository.findByUseYnOrderBySortSeqAsc(LsOtsdCtgryMpng.USE_YES)) {
            String normalized = normalize(label.getLabelNm());
            if (normalized != null && label.getLabelId() != null) {
                labels.add(new Candidate(String.valueOf(label.getLabelId()), label.getLabelNm(), normalized));
            }
        }
        List<Candidate> eventTypes = new ArrayList<>();
        for (Map.Entry<String, String> entry : eventTypeService.codeLabelMap().entrySet()) {
            String normalized = normalize(entry.getValue());
            if (normalized != null) {
                eventTypes.add(new Candidate(entry.getKey(), entry.getValue(), normalized));
            }
        }
        return new Snapshot(List.copyOf(labels), List.copyOf(eventTypes));
    }

    /**
     * 외부 분류 이름으로 후보를 찾는다.
     *
     * @param mpngKndCd    대응 종류({@link LsOtsdCtgryMpng#MPNG_KND_LABEL} /
     *                     {@link LsOtsdCtgryMpng#MPNG_KND_EVNT_TYPE})
     * @param externalName 외부 산출물이 준 표시 이름. 없으면 식별 문자열을 그대로 넣어도 된다
     * @return 후보가 유일할 때만 1건, 그 밖에는 <b>빈 목록</b>
     */
    public List<Suggestion> suggest(Snapshot snapshot, String mpngKndCd, String externalName) {
        if (snapshot == null) {
            return List.of();
        }
        String needle = normalize(externalName);
        if (needle == null) {
            return List.of();
        }
        List<Candidate> pool = LsOtsdCtgryMpng.MPNG_KND_EVNT_TYPE.equals(mpngKndCd)
                ? snapshot.eventTypes() : snapshot.labels();

        List<Candidate> exact = pool.stream().filter(c -> c.normalized().equals(needle)).toList();
        if (!exact.isEmpty()) {
            return single(exact);
        }
        if (needle.length() < 2) {
            // 한 글자는 아무 이름에나 걸린다 — 품기 판정을 적용하지 않는다.
            return List.of();
        }
        List<Candidate> contained = pool.stream()
                .filter(c -> c.normalized().length() >= 2)
                .filter(c -> c.normalized().contains(needle) || needle.contains(c.normalized()))
                .toList();
        return single(contained);
    }

    /** 유일할 때만 돌려준다 — 0건이거나 동점 다수면 사람이 고르게 비운다. */
    private static List<Suggestion> single(List<Candidate> matched) {
        if (matched.size() != 1) {
            return List.of();
        }
        Candidate only = matched.get(0);
        return List.of(new Suggestion(only.targetId(), only.targetName()));
    }

    /**
     * 비교용 정규화 — 자모 결합 형태를 맞추고(한글은 파일시스템·입력기마다 다르게 저장된다) 대소문자와
     * 공백·기호를 지운다. 값 자체를 바꾸는 것이 아니라 <b>비교에만</b> 쓴다.
     */
    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder(nfc.length());
        for (int i = 0; i < nfc.length() && sb.length() < NORMALIZED_MAX; i++) {
            char c = nfc.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        String cleaned = sb.toString();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
