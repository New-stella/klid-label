package kr.co.cudo.authoring.preset.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 프리셋 도메인 응용 서비스.
 *
 * <p>입력 검증(중복 이름 등)은 본 서비스에서 수행하고, 도메인 상태 변경은
 * Aggregate Root({@link LsLabelPreset}) 의 정적 팩토리/도메인 메서드에 위임한다.
 */
@Service
@RequiredArgsConstructor
public class PresetService {

    /** 복제 시 충돌 회피 위한 최대 시도 횟수. */
    private static final int CLONE_SUFFIX_MAX = 50;

    private final LsLabelPresetRepository presetRepository;

    @Transactional(readOnly = true)
    public List<LsLabelPreset> list() {
        return presetRepository.findAllByOrderByPresetIdDesc();
    }

    @Transactional
    public LsLabelPreset create(String name, String description, List<String> labelCodes) {
        if (presetRepository.existsByName(name)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 사용 중인 프리셋 이름입니다.");
        }
        LsLabelPreset preset = LsLabelPreset.create(name, description, labelCodes);
        return presetRepository.save(preset);
    }

    @Transactional
    public LsLabelPreset update(long id, String name, String description, List<String> labelCodes) {
        LsLabelPreset preset = presetRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프리셋을 찾을 수 없습니다."));
        if (presetRepository.existsByNameAndPresetIdNot(name, id)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 사용 중인 프리셋 이름입니다.");
        }
        preset.updateBasics(name, description);
        preset.replaceCodes(labelCodes);
        return preset;
    }

    @Transactional
    public void delete(long id) {
        if (!presetRepository.existsById(id)) {
            return;
        }
        presetRepository.deleteById(id);
    }

    @Transactional
    public LsLabelPreset clone(long id) {
        LsLabelPreset src = presetRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프리셋을 찾을 수 없습니다."));
        String baseName = resolveCloneName(src.getName());
        LsLabelPreset copy = LsLabelPreset.create(baseName, src.getDescription(), src.codeValues());
        return presetRepository.save(copy);
    }

    /** 복제 이름 충돌 시 " (복사본 2)", " (복사본 3)" ... 식으로 시퀀스 부여. */
    private String resolveCloneName(String sourceName) {
        String candidate = sourceName + " (복사본)";
        if (!presetRepository.existsByName(candidate)) {
            return candidate;
        }
        for (int i = 2; i <= CLONE_SUFFIX_MAX; i++) {
            String alt = sourceName + " (복사본 " + i + ")";
            if (!presetRepository.existsByName(alt)) {
                return alt;
            }
        }
        throw new CustomException(ErrorCode.CONFLICT, "복제 이름 생성에 실패했습니다.");
    }
}
