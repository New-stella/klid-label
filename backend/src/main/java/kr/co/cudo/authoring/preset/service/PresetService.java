package kr.co.cudo.authoring.preset.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 프리셋 도메인 응용 서비스.
 *
 * <p>입력 검증(중복 이름 등)은 본 서비스에서 수행하고, 도메인 상태 변경은
 * Aggregate Root({@link LsLabelPreset}) 의 정적 팩토리/도메인 메서드에 위임한다.
 *
 * <p>UNIQUE 제약 충돌(중복 이벤트 매핑) 은 race-safe 하게 DB 단에서만 차단되며,
 * 본 서비스가 {@link DataIntegrityViolationException} 을 {@link ErrorCode#CONFLICT} 로 변환한다.
 */
@Service
@RequiredArgsConstructor
public class PresetService {

    /** 복제 시 충돌 회피 위한 최대 시도 횟수. */
    private static final int CLONE_SUFFIX_MAX = 50;

    private static final String MSG_EVENT_CONFLICT = "이미 다른 프리셋에 매핑된 이벤트입니다";

    private final LsLabelPresetRepository presetRepository;

    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<LsLabelPreset> list() {
        return presetRepository.findAllByOrderByPresetIdDesc();
    }

    @Transactional("controlTransactionManager")
    public LsLabelPreset create(String name, String description, List<String> labelCodes, String eventTypeCd) {
        if (presetRepository.existsByName(name)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 사용 중인 프리셋 이름입니다.");
        }
        LsLabelPreset preset = LsLabelPreset.create(name, description, labelCodes, eventTypeCd);
        return saveWithEventUniqueGuard(preset);
    }

    @Transactional("controlTransactionManager")
    public LsLabelPreset update(long id, String name, String description, List<String> labelCodes, String eventTypeCd) {
        LsLabelPreset preset = presetRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프리셋을 찾을 수 없습니다."));
        if (presetRepository.existsByNameAndPresetIdNot(name, id)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 사용 중인 프리셋 이름입니다.");
        }
        preset.updateBasics(name, description);
        preset.replaceCodes(labelCodes);
        preset.assignToEvent(eventTypeCd);
        // dirty-checking 으로 flush 시 UNIQUE 위반 가능 → 명시적 flush 로 throw 위치를 본 메서드 안으로 끌어온다.
        try {
            presetRepository.saveAndFlush(preset);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.CONFLICT, MSG_EVENT_CONFLICT, e);
        }
        return preset;
    }

    @Transactional("controlTransactionManager")
    public void delete(long id) {
        if (!presetRepository.existsById(id)) {
            return;
        }
        presetRepository.deleteById(id);
    }

    @Transactional("controlTransactionManager")
    public LsLabelPreset clone(long id) {
        LsLabelPreset src = presetRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프리셋을 찾을 수 없습니다."));
        String baseName = resolveCloneName(src.getName());
        // 복제본은 이벤트 매핑 미상속 — 이벤트 UNIQUE 충돌을 피하기 위해 null 로 생성.
        LsLabelPreset copy = LsLabelPreset.create(baseName, src.getDescription(), src.codeValues(), null);
        return presetRepository.save(copy);
    }

    /** insert 시점 UNIQUE 위반(이벤트 중복) 을 CONFLICT 로 변환. */
    private LsLabelPreset saveWithEventUniqueGuard(LsLabelPreset preset) {
        try {
            return presetRepository.saveAndFlush(preset);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.CONFLICT, MSG_EVENT_CONFLICT, e);
        }
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
