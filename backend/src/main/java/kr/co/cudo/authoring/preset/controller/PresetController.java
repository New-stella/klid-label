package kr.co.cudo.authoring.preset.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.preset.dto.LabelCodeOptionDto;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.entity.LsLabelPresetCode;
import kr.co.cudo.authoring.preset.service.PresetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * SCR-LBL-PRESET-001 라벨링 프리셋 관리.
 *
 * <p>V13 마이그레이션으로 LS_LABEL_PRESET / LS_LABEL_PRESET_CODE 영속 저장소를 사용한다.
 * V15 마이그레이션으로 LS_LABEL_PRESET.EVNT_TYPE_CD (이벤트 1:1 매핑) 컬럼이 추가되었다.
 * V16 마이그레이션으로 LS_LABEL_PRESET_CODE.BBOX_ENABLED / POLYGON_ENABLED 컬럼이 추가되었다.
 *
 * <p>보안:
 * <ul>
 *   <li>REVIEWER 전용 — SecurityConfig {@code /v1/manage/**} 매처 + {@code @PreAuthorize}.</li>
 *   <li>RequestBody 는 DTO ({@link PresetRequest}) 로 강제 — Mass Assignment 방어.</li>
 *   <li>입력 검증: 이름 1~64자, description 최대 500자, labelCodeOptions/labelCodes 둘 중 하나 1~20개 필수
 *       (각 코드 1~32자, 둘 다 false 인 옵션은 거부),
 *       eventTypeCd 는 SoT 6종({@code ^EVT_(FALL|VIOLENCE|ACCIDENT|ABNORMAL|FLOOD|FIRE)$}) 또는 null/빈 문자열.</li>
 *   <li>JSON unknown 필드는 ignore — 클라이언트 호환성.</li>
 * </ul>
 *
 * <p>Phase 1 호환성: 기존 클라이언트가 {@code labelCodes: List<String>} 만 보내도 모두 BBOX+POLYGON
 * 활성으로 정규화하여 저장한다. 응답에는 두 형식({@code labelCodes}, {@code labelCodeOptions}) 모두 포함한다.
 */
@Tag(name = "Preset", description = "라벨링 프리셋 관리 — REVIEWER 전용.")
@RestController
@RequestMapping("/v1/manage/presets")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
public class PresetController {

    /** 라벨 코드 목록의 최대 개수 (DTO 검증 + 도메인 컬렉션 boundary). */
    private static final int MAX_LABEL_CODES = 20;

    private final PresetService presetService;

    @Operation(summary = "프리셋 전체 조회 (REVIEWER)")
    @GetMapping
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<List<PresetResponse>> list() {
        List<PresetResponse> body = presetService.list().stream()
                .map(PresetController::toResponse)
                .toList();
        return ApiResponse.ok(body);
    }

    @Operation(summary = "프리셋 신규 등록 (REVIEWER)")
    @PostMapping
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PresetResponse> create(@Valid @RequestBody PresetRequest request) {
        List<LabelCodeOptionDto> options = normalizeOptions(request);
        LsLabelPreset saved = presetService.create(
                request.name(),
                request.description(),
                options,
                request.eventTypeCd()
        );
        return ApiResponse.ok(toResponse(saved));
    }

    @Operation(summary = "프리셋 수정 (REVIEWER)")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<PresetResponse> update(@PathVariable long id, @Valid @RequestBody PresetRequest request) {
        List<LabelCodeOptionDto> options = normalizeOptions(request);
        LsLabelPreset updated = presetService.update(
                id,
                request.name(),
                request.description(),
                options,
                request.eventTypeCd()
        );
        return ApiResponse.ok(toResponse(updated));
    }

    @Operation(summary = "프리셋 삭제 (REVIEWER)")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        presetService.delete(id);
    }

    @Operation(summary = "프리셋 복제 (REVIEWER)")
    @PostMapping("/{id}/clone")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<PresetResponse> clone(@PathVariable long id) {
        LsLabelPreset copy = presetService.clone(id);
        return ApiResponse.ok(toResponse(copy));
    }

    /**
     * 신규 {@code labelCodeOptions} 우선, 미지정 시 레거시 {@code labelCodes} 를 BBOX+POLYGON
     * 모두 활성으로 정규화한다. 둘 다 비어있으면 400 ({@code INVALID_INPUT}).
     */
    private static List<LabelCodeOptionDto> normalizeOptions(PresetRequest request) {
        List<LabelCodeOptionDto> options = request.labelCodeOptions();
        if (options != null && !options.isEmpty()) {
            return options;
        }
        List<String> codes = request.labelCodes();
        if (codes != null && !codes.isEmpty()) {
            return codes.stream()
                    .filter(c -> c != null && !c.isBlank())
                    .map(LabelCodeOptionDto::both)
                    .toList();
        }
        throw new CustomException(ErrorCode.INVALID_INPUT, "라벨 코드는 최소 1개 이상이어야 합니다.");
    }

    /** Entity → Response 변환. timestamp 는 ISO-8601 UTC 문자열로 직렬화한다. */
    private static PresetResponse toResponse(LsLabelPreset entity) {
        List<LabelCodeOptionResponse> opts = new ArrayList<>(entity.getCodes().size());
        List<String> codes = new ArrayList<>(entity.getCodes().size());
        for (LsLabelPresetCode c : entity.getCodes()) {
            opts.add(new LabelCodeOptionResponse(c.getCode(), c.isBboxEnabled(), c.isPolygonEnabled()));
            codes.add(c.getCode());
        }
        return new PresetResponse(
                entity.getPresetId(),
                entity.getPresetNm(),
                entity.getExpln(),
                codes,
                opts,
                entity.getEventTypeCd(),
                formatTimestamp(entity.getRegDt()),
                formatTimestamp(entity.getMdfcnDt())
        );
    }

    private static String formatTimestamp(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.atOffset(ZoneOffset.UTC).toInstant().toString();
    }

    /**
     * 프리셋 생성/수정 요청 DTO.
     *
     * <p>Phase 1 — {@code labelCodeOptions} 우선 사용 (신규), {@code labelCodes} 는 레거시 호환.
     * 둘 다 비어있으면 정규화 단계에서 400 으로 거부된다. 두 필드 동시 존재 시 신규가 우선이다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PresetRequest(
            @NotBlank(message = "프리셋 이름은 필수입니다")
            @Size(max = 64, message = "프리셋 이름은 64자 이하여야 합니다")
            String name,
            @Size(max = 500, message = "설명은 500자 이하여야 합니다")
            String description,
            @Size(max = MAX_LABEL_CODES, message = "라벨 코드는 최대 " + MAX_LABEL_CODES + "개까지 허용합니다")
            List<@Valid LabelCodeOptionDto> labelCodeOptions,
            @Size(max = MAX_LABEL_CODES, message = "라벨 코드는 최대 " + MAX_LABEL_CODES + "개까지 허용합니다")
            List<@NotBlank @Size(max = 32) String> labelCodes,
            @Pattern(regexp = "^$|^EVT_(FALL|VIOLENCE|ACCIDENT|ABNORMAL|FLOOD|FIRE)$",
                    message = "지원하지 않는 이벤트 타입입니다")
            @Size(max = 32)
            String eventTypeCd
    ) {
        /**
         * 둘 다 null 또는 빈 목록이면 거부. {@link AssertTrue} 는 @Valid 시점에 평가되어
         * 정규화 단계 진입 전에 400 을 만들어준다.
         */
        @AssertTrue(message = "labelCodeOptions 또는 labelCodes 중 최소 하나 이상의 라벨이 필요합니다")
        public boolean isAtLeastOneLabelPresent() {
            boolean hasOptions = labelCodeOptions != null && !labelCodeOptions.isEmpty();
            boolean hasCodes = labelCodes != null && !labelCodes.isEmpty();
            return hasOptions || hasCodes;
        }
    }

    /** 응답 — 라벨별 BBOX/POLYGON 토글 (Phase 1). */
    public record LabelCodeOptionResponse(
            String code,
            boolean bboxEnabled,
            boolean polygonEnabled
    ) {
    }

    /**
     * 응답 — Phase 1 부터 {@code labelCodes} 와 {@code labelCodeOptions} 둘 다 포함.
     * 레거시 클라이언트는 {@code labelCodes} 만, 신규는 {@code labelCodeOptions} 를 사용한다.
     */
    public record PresetResponse(
            long id,
            String name,
            String description,
            List<String> labelCodes,
            List<LabelCodeOptionResponse> labelCodeOptions,
            String eventTypeCd,
            String createdAt,
            String updatedAt
    ) {
    }
}
