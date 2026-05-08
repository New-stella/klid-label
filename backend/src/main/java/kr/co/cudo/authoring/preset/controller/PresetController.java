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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SCR-LBL-PRESET-001 라벨링 프리셋 관리 (placeholder).
 *
 * <p>V1.x 시점 후속 Phase 까지 in-memory 저장소로 동작한다 (FE 연동 차단 해소용).
 * 영속화는 후속 Phase 에서 LS_LABEL_PRESET 테이블로 이전 예정.
 *
 * <p>보안:
 * <ul>
 *   <li>REVIEWER 전용 — SecurityConfig {@code /v1/manage/**} 매처 + {@code @PreAuthorize}.</li>
 *   <li>RequestBody 는 DTO ({@link PresetRequest}) 로 강제 — Mass Assignment 방어.</li>
 *   <li>입력 검증: 이름 1~64자, eventTypeCd 1~32자, items null 금지.</li>
 *   <li>JSON unknown 필드는 ignore — 클라이언트 호환성.</li>
 * </ul>
 */
@Tag(name = "Preset", description = "라벨링 프리셋 관리 — REVIEWER 전용. (placeholder, in-memory)")
@RestController
@RequestMapping("/v1/manage/presets")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
public class PresetController {

    /** in-memory 저장소 — placeholder. 운영 환경 전 영속화 필수. */
    private final Map<Long, PresetResponse> store = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(0);

    @Operation(summary = "프리셋 전체 조회 (REVIEWER)")
    @GetMapping
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<List<PresetResponse>> list() {
        return ApiResponse.ok(store.values().stream().toList());
    }

    @Operation(summary = "프리셋 신규 등록 (REVIEWER)")
    @PostMapping
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PresetResponse> create(@Valid @RequestBody PresetRequest request) {
        long id = seq.incrementAndGet();
        String now = Instant.now().toString();
        PresetResponse saved = new PresetResponse(
                id,
                request.name(),
                request.eventTypeCd(),
                request.subType(),
                request.items() == null ? List.of() : List.copyOf(request.items()),
                now,
                now
        );
        store.put(id, saved);
        return ApiResponse.ok(saved);
    }

    @Operation(summary = "프리셋 수정 (REVIEWER)")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<PresetResponse> update(@PathVariable long id, @Valid @RequestBody PresetRequest request) {
        PresetResponse existing = store.get(id);
        String createdAt = existing == null ? Instant.now().toString() : existing.createdAt();
        PresetResponse updated = new PresetResponse(
                id,
                request.name(),
                request.eventTypeCd(),
                request.subType(),
                request.items() == null ? List.of() : List.copyOf(request.items()),
                createdAt,
                Instant.now().toString()
        );
        store.put(id, updated);
        return ApiResponse.ok(updated);
    }

    @Operation(summary = "프리셋 삭제 (REVIEWER)")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        store.remove(id);
    }

    @Operation(summary = "프리셋 복제 (REVIEWER)")
    @PostMapping("/{id}/clone")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<PresetResponse> clone(@PathVariable long id) {
        PresetResponse src = store.get(id);
        if (src == null) {
            // placeholder — 비어있어도 빈 항목 1건으로 응답.
            src = new PresetResponse(id, "(원본 없음)", "FALL", null, List.of(),
                    Instant.now().toString(), Instant.now().toString());
        }
        long newId = seq.incrementAndGet();
        String now = Instant.now().toString();
        PresetResponse copy = new PresetResponse(
                newId,
                src.name() + " (복사본)",
                src.eventTypeCd(),
                src.subType(),
                src.items(),
                now,
                now
        );
        store.put(newId, copy);
        return ApiResponse.ok(copy);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PresetRequest(
            @NotBlank @Size(max = 64) String name,
            @NotBlank @Size(max = 32) String eventTypeCd,
            @Size(max = 32) String subType,
            @NotNull List<LabelItemDto> items
    ) {
    }

    public record PresetResponse(
            long id,
            String name,
            String eventTypeCd,
            String subType,
            List<LabelItemDto> items,
            String createdAt,
            String updatedAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LabelItemDto(
            Long id,
            @NotBlank @Size(max = 64) String name,
            @NotBlank @Size(max = 16) String shape,
            @NotBlank @Size(max = 7) String color,
            Map<String, String> attributes
    ) {
    }
}
