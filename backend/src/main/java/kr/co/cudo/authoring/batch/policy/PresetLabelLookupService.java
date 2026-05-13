package kr.co.cudo.authoring.batch.policy;

import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 이벤트 타입 → 프리셋 라벨 코드 매핑 조회 서비스.
 *
 * <p>V1.8 변경: 기존 {@code EventPresetMapping} 코드 상수가 제거되고,
 * 매핑은 LS_LABEL_PRESET.EVNT_TYPE_CD (UNIQUE) 컬럼으로 DB 화. 운영자가 프리셋 UI 에서
 * 동적으로 관리한다.
 *
 * <p>호출자({@code YoloAutolabelStep}) 는 라벨 비교 시 소문자 정규화된 set 를 기대한다.
 *
 * <ul>
 *   <li>eventTypeCd 가 null/blank → {@link Optional#empty()} (호출자 fail-safe: 필터 미적용)</li>
 *   <li>해당 이벤트에 매핑된 프리셋이 없음 → {@link Optional#empty()} (호출자 fail-safe)</li>
 *   <li>매핑 존재 → 라벨 코드 집합 (소문자 정규화)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PresetLabelLookupService {

    private final LsLabelPresetRepository presetRepository;

    /**
     * 주어진 이벤트 타입 코드에 매핑된 허용 라벨 집합을 반환한다.
     *
     * @param eventTypeCd 이벤트 타입 코드 (예: EVT_FALL). null/blank/미매핑 시 빈 Optional 반환.
     * @return 매핑된 허용 라벨 집합 (소문자 정규화). 빈 Optional 이면 필터 미적용 (전체 통과).
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Optional<Set<String>> labelsFor(String eventTypeCd) {
        if (eventTypeCd == null || eventTypeCd.isBlank()) {
            return Optional.empty();
        }
        return presetRepository.findByEventTypeCd(eventTypeCd)
                .map(preset -> preset.codeValues().stream()
                        .map(code -> code.toLowerCase())
                        .collect(Collectors.toUnmodifiableSet()))
                .filter(set -> !set.isEmpty());
    }
}
