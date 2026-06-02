package kr.co.cudo.authoring.deident.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비식별 재처리 요청 (placeholder).
 *
 * <p>비식별 결과 조회 화면/API 는 외부 비식별 솔루션으로 이관되어 제거됨. 저작도구는 재처리 요청만 보유.
 *
 * <p>보안:
 * <ul>
 *   <li>REVIEWER 만 접근 (SecurityConfig + {@code @PreAuthorize}).</li>
 *   <li>videoId 는 path variable long — 자동 타입 검증 (CWE-89/22 차단).</li>
 * </ul>
 */
@Tag(name = "Deidentify", description = "비식별 재처리 요청 — REVIEWER. (placeholder)")
@RestController
@RequestMapping("/v1/deident")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class DeidentController {

    @Operation(summary = "비식별 재처리 요청 (placeholder)")
    @PostMapping("/{videoId}/reprocess")
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Void> reprocess(@PathVariable long videoId) {
        // placeholder — 후속 Phase 에서 큐 적재.
        return ApiResponse.ok(null);
    }
}
