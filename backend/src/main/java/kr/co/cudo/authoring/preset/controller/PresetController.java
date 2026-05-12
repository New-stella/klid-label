package kr.co.cudo.authoring.preset.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
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
import java.util.List;

/**
 * SCR-LBL-PRESET-001 라벨링 프리셋 관리.
 *
 * <p>V13 마이그레이션으로 LS_LABEL_PRESET / LS_LABEL_PRESET_CODE 영속 저장소를 사용한다.
 *
 * <p>보안:
 * <ul>
 *   <li>REVIEWER 전용 — SecurityConfig {@code /v1/manage/**} 매처 + {@code @PreAuthorize}.</li>
 *   <li>RequestBody 는 DTO ({@link PresetRequest}) 로 강제 — Mass Assignment 방어.</li>
 *   <li>입력 검증: 이름 1~64자, description 최대 500자, labelCodes null 금지(각 코드 1~32자).</li>
 *   <li>JSON unknown 필드는 ignore — 클라이언트 호환성.</li>
 * </ul>
 */
@Tag(name = "Preset", description = "라벨링 프리셋 관리 — REVIEWER 전용.")
@RestController
@RequestMapping("/v1/manage/presets")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
public class PresetController {

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
        LsLabelPreset saved = presetService.create(
                request.name(),
                request.description(),
                request.labelCodes() == null ? List.of() : List.copyOf(request.labelCodes())
        );
        return ApiResponse.ok(toResponse(saved));
    }

    @Operation(summary = "프리셋 수정 (REVIEWER)")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<PresetResponse> update(@PathVariable long id, @Valid @RequestBody PresetRequest request) {
        LsLabelPreset updated = presetService.update(
                id,
                request.name(),
                request.description(),
                request.labelCodes() == null ? List.of() : List.copyOf(request.labelCodes())
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

    /** Entity → Response 변환. timestamp 는 ISO-8601 UTC 문자열로 직렬화한다. */
    private static PresetResponse toResponse(LsLabelPreset entity) {
        return new PresetResponse(
                entity.getPresetId(),
                entity.getName(),
                entity.getDescription(),
                entity.codeValues(),
                formatTimestamp(entity.getCreatedAt()),
                formatTimestamp(entity.getUpdatedAt())
        );
    }

    private static String formatTimestamp(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.atOffset(ZoneOffset.UTC).toInstant().toString();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PresetRequest(
            @NotBlank @Size(max = 64) String name,
            @Size(max = 500) String description,
            @NotNull List<@NotBlank @Size(max = 32) String> labelCodes
    ) {
    }

    public record PresetResponse(
            long id,
            String name,
            String description,
            List<String> labelCodes,
            String createdAt,
            String updatedAt
    ) {
    }
}
