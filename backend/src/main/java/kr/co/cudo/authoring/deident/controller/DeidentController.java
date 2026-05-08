package kr.co.cudo.authoring.deident.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * SCR-DEIDENT-001/002 비식별 결과 조회 (placeholder).
 *
 * <p>FE 호출 차단 해소용 임시 응답. 실제 비식별 처리 결과/이력 집계는 후속 Phase.
 *
 * <p>보안:
 * <ul>
 *   <li>REVIEWER/WORKER 양 역할 모두 접근 (PORTAL_USER 차단 — SecurityConfig + {@code @PreAuthorize}).</li>
 *   <li>videoId 는 path variable long — 자동 타입 검증 (CWE-89/22 차단).</li>
 *   <li>이미지 URL 은 BE 응답값만 사용 (FE 가 사용자 입력으로 구성 금지).</li>
 * </ul>
 */
@Tag(name = "Deidentify", description = "비식별 처리 결과 조회 — REVIEWER/WORKER. (placeholder)")
@RestController
@RequestMapping("/v1/deident")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class DeidentController {

    @Operation(summary = "비식별 결과 목록 (placeholder)")
    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "prvcYn", required = false) String prvcYn,
            @RequestParam(name = "status", required = false) String status
    ) {
        // size 상한 100 (Resource Consumption 방어).
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        Map<String, Object> page0 = Map.of(
                "content", List.of(),
                "totalElements", 0,
                "totalPages", 0,
                "number", safePage,
                "size", safeSize
        );
        return ApiResponse.ok(page0);
    }

    @Operation(summary = "비식별 상세 (placeholder)")
    @GetMapping("/{videoId}")
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long videoId) {
        Map<String, Object> empty = Map.ofEntries(
                Map.entry("videoId", videoId),
                Map.entry("cctvName", ""),
                Map.entry("vmsClipId", ""),
                Map.entry("prvcType", "PRVC"),
                Map.entry("prvcYn", "Y"),
                Map.entry("status", "PENDING"),
                Map.entry("totalFrames", 0),
                Map.entry("processedFrames", 0),
                Map.entry("failedFrames", 0),
                Map.entry("framePairs", List.of()),
                Map.entry("history", List.of())
        );
        return ApiResponse.ok(empty);
    }

    @Operation(summary = "비식별 재처리 요청 (placeholder)")
    @PostMapping("/{videoId}/reprocess")
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Void> reprocess(@PathVariable long videoId) {
        // placeholder — 후속 Phase 에서 큐 적재.
        return ApiResponse.ok(null);
    }
}
