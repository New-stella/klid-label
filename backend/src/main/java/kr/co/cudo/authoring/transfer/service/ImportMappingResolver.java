package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.transfer.entity.LsOtsdCtgryMpng;
import kr.co.cudo.authoring.transfer.parser.ImportedDataset;
import kr.co.cudo.authoring.transfer.repository.LsOtsdCtgryMpngRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 적재 직전에 <b>분류 대응이 전부 확정돼 있는지</b>를 확인하고 확정된 연결 대상을 돌려준다.
 *
 * <h3>fail-closed — 미확정이 하나라도 남으면 적재하지 않는다</h3>
 * <p>처음 보는 분류를 이름만 보고 짐작해 연결하면 <b>다른 분류로 저장</b>되고, 저장된 뒤에는 어느 것이
 * 짐작이었는지 구분할 수 없다. 그래서 이 클래스에는 추천·자동 확정 통로가 없다 — 확정은 사람이 별도
 * 행위로 하고, 여기서는 이미 확정된 것만 읽는다.
 *
 * <h3>비활성 라벨은 미확정과 같이 다룬다</h3>
 * <p>대응이 가리키는 라벨 마스터가 <b>해제(soft delete)</b>됐으면 그 대응으로 라벨을 만들 수 없다
 * ({@code LBL_NM} 이 필수인데 마스터에서 이름을 조달할 수 없다). 그 경우 라벨 이름을 지어내거나 코드
 * 문자열로 대신하면 학습데이터에 존재하지 않는 분류가 실린다. 미확정으로 되돌려 사람이 다시 정하게 한다.
 *
 * <h3>트랜잭션은 짧게, 파싱은 밖에서</h3>
 * <p>여기서 하는 일은 조회뿐이다. 산출물 파싱·파일 복사는 이 트랜잭션 밖에서 끝난 뒤에 들어온다
 * (커넥션을 쥔 채 NAS I/O 를 하지 않는다).
 *
 * @design DOMAIN-017
 * @design ERD-031
 * @design AC-042
 */
@Service
@RequiredArgsConstructor
public class ImportMappingResolver {

    private final LsOtsdCtgryMpngRepository mappingRepository;
    private final LsLabelRepository labelRepository;

    /**
     * 확정된 대응 묶음.
     *
     * @param labelIdByCategory   외부 분류 식별 문자열 → 저작도구 라벨 아이디
     * @param labelNameByCategory 외부 분류 식별 문자열 → 저작도구 라벨 이름({@code LBL_NM} 적재값)
     * @param eventTypeCd         영상 이벤트 유형 코드. 산출물이 이벤트를 주지 않으면 {@code null}
     * @param unresolved          아직 확정되지 않은 분류(있으면 적재하지 않는다)
     */
    public record Resolved(Map<String, Long> labelIdByCategory,
                           Map<String, String> labelNameByCategory,
                           String eventTypeCd,
                           List<String> unresolved) {

        /** 미확정이 하나라도 있는가. */
        public boolean hasUnresolved() {
            return !unresolved.isEmpty();
        }
    }

    /** 산출물이 실제로 쓴 분류의 대응을 읽는다. 아무것도 저장하지 않는다. */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Resolved resolve(ImportedDataset dataset) {
        Map<String, String> labelCategories = ImportCategoryCollector.labelCategories(dataset);
        Map<String, String> eventCategories = ImportCategoryCollector.eventCategories(dataset);

        List<String> unresolved = new ArrayList<>();
        Map<String, Long> labelIds = new LinkedHashMap<>();
        Map<String, String> labelNames = new LinkedHashMap<>();

        if (!labelCategories.isEmpty()) {
            Map<String, Long> mapped = mappingRepository
                    .findByMpngKndCdAndUseYnAndOtsdCtgryCdIn(LsOtsdCtgryMpng.MPNG_KND_LABEL,
                            LsOtsdCtgryMpng.USE_YES, labelCategories.keySet())
                    .stream()
                    .filter(m -> m.getLblId() != null)
                    .collect(Collectors.toMap(LsOtsdCtgryMpng::getOtsdCtgryCd, LsOtsdCtgryMpng::getLblId,
                            (a, b) -> a));
            Map<Long, String> activeNames = loadActiveLabelNames(mapped.values());
            for (String category : labelCategories.keySet()) {
                Long labelId = mapped.get(category);
                String labelName = labelId == null ? null : activeNames.get(labelId);
                if (labelName == null) {
                    // 대응이 없거나, 있어도 그 라벨이 해제됐다 — 둘 다 "이 분류로는 적재할 수 없다"다.
                    unresolved.add(category);
                    continue;
                }
                labelIds.put(category, labelId);
                labelNames.put(category, labelName);
            }
        }

        String eventTypeCd = null;
        if (!eventCategories.isEmpty()) {
            Map<String, String> mapped = mappingRepository
                    .findByMpngKndCdAndUseYnAndOtsdCtgryCdIn(LsOtsdCtgryMpng.MPNG_KND_EVNT_TYPE,
                            LsOtsdCtgryMpng.USE_YES, eventCategories.keySet())
                    .stream()
                    .filter(m -> m.getEvntTypeCd() != null)
                    .collect(Collectors.toMap(LsOtsdCtgryMpng::getOtsdCtgryCd,
                            LsOtsdCtgryMpng::getEvntTypeCd, (a, b) -> a));
            for (String category : eventCategories.keySet()) {
                String code = mapped.get(category);
                if (code == null) {
                    unresolved.add(category);
                    continue;
                }
                eventTypeCd = code;
            }
        }

        return new Resolved(Map.copyOf(labelIds), Map.copyOf(labelNames), eventTypeCd,
                List.copyOf(unresolved));
    }

    private Map<Long, String> loadActiveLabelNames(java.util.Collection<Long> labelIds) {
        if (labelIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> distinct = Set.copyOf(labelIds);
        return labelRepository.findByLabelIdInAndUseYn(distinct, "Y").stream()
                .collect(Collectors.toMap(LsLabel::getLabelId, LsLabel::getLabelNm, (a, b) -> a));
    }
}
