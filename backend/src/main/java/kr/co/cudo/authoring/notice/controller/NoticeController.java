package kr.co.cudo.authoring.notice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.notice.dto.NoticeCreateRequest;
import kr.co.cudo.authoring.notice.dto.NoticeResponse;
import kr.co.cudo.authoring.notice.dto.NoticeSummaryResponse;
import kr.co.cudo.authoring.notice.dto.NoticeUpdateRequest;
import kr.co.cudo.authoring.notice.entity.LsNotice;
import kr.co.cudo.authoring.notice.repository.LsNoticeQueryRepository.SearchField;
import kr.co.cudo.authoring.notice.service.NoticeAttachService;
import kr.co.cudo.authoring.notice.service.NoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 게시판(공지사항) REST API.
 *
 * <p>권한: 목록/상세 조회는 REVIEWER/WORKER. 작성/수정/삭제/발행/발행취소는 REVIEWER 전용.
 * PORTAL_USER 는 SecurityConfig 의 {@code /v1/notices/**} 매처로 차단(403).
 *
 * <p>가시성: WORKER 는 PUBLISHED 만, REVIEWER 는 DRAFT 포함 전체.
 */
@Tag(name = "Notice", description = "게시판(공지사항) — 조회는 REVIEWER/WORKER, 쓰기는 REVIEWER 전용.")
@RestController
@RequestMapping("/v1/notices")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
public class NoticeController {

    private final NoticeService noticeService;
    private final NoticeAttachService noticeAttachService;

    @Operation(summary = "공지 목록 조회 (REVIEWER/WORKER)",
            description = "고정글 우선 → 최신순. WORKER 는 PUBLISHED 만, REVIEWER 는 전체. field=TITLE/CONTENT/ALL.")
    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<Page<NoticeSummaryResponse>> list(
            @Parameter(description = "페이지 번호 (0-based)", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기 (1~100)", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(description = "검색 필드 (TITLE/CONTENT/ALL)")
            @RequestParam(required = false) String field,
            @Parameter(description = "검색어 (최대 100자)")
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @AuthenticationPrincipal TokenClaims actor) {
        SearchField searchField = parseSearchField(field);
        Pageable pageable = PageRequest.of(page, size);
        Page<NoticeSummaryResponse> body = noticeService
                .search(searchField, keyword, pageable, actor)
                .map(NoticeSummaryResponse::from);
        return ApiResponse.ok(body);
    }

    @Operation(summary = "공지 상세 조회 (REVIEWER/WORKER)",
            description = "DRAFT 공지는 REVIEWER 만 조회 가능 — WORKER 접근 시 404.")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<NoticeResponse> get(@PathVariable long id,
                                           @AuthenticationPrincipal TokenClaims actor) {
        LsNotice notice = noticeService.get(id, actor);
        return ApiResponse.ok(NoticeResponse.from(notice, noticeAttachService.listByNotice(id),
                noticeService.resolveWriterName(notice)));
    }

    @Operation(summary = "공지 작성 (REVIEWER)", description = "기본 상태는 DRAFT.")
    @PostMapping
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NoticeResponse> create(@Valid @RequestBody NoticeCreateRequest request,
                                              @AuthenticationPrincipal TokenClaims actor) {
        LsNotice saved = noticeService.create(request.title(), request.content(), request.pinned(), actor);
        return ApiResponse.ok(NoticeResponse.from(saved, noticeService.resolveWriterName(saved)));
    }

    @Operation(summary = "공지 수정 (REVIEWER)")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<NoticeResponse> update(@PathVariable long id,
                                              @Valid @RequestBody NoticeUpdateRequest request,
                                              @AuthenticationPrincipal TokenClaims actor) {
        LsNotice updated = noticeService.update(id, request.title(), request.content(), request.pinned(), actor);
        return ApiResponse.ok(NoticeResponse.from(updated, noticeService.resolveWriterName(updated)));
    }

    @Operation(summary = "공지 삭제 (REVIEWER)")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        noticeService.delete(id);
    }

    @Operation(summary = "공지 발행 (REVIEWER)", description = "멱등 — 이미 발행된 공지는 PBLCN_DT 불변.")
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<NoticeResponse> publish(@PathVariable long id) {
        LsNotice published = noticeService.publish(id);
        return ApiResponse.ok(NoticeResponse.from(published, noticeService.resolveWriterName(published)));
    }

    @Operation(summary = "공지 발행취소 (REVIEWER)", description = "멱등 — 이미 DRAFT 면 no-op.")
    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<NoticeResponse> unpublish(@PathVariable long id) {
        LsNotice drafted = noticeService.unpublish(id);
        return ApiResponse.ok(NoticeResponse.from(drafted, noticeService.resolveWriterName(drafted)));
    }

    /**
     * field 파라미터를 화이트리스트 enum 으로 변환. null/blank 는 검색 미적용(null 반환),
     * 미허용 값은 400(INVALID_INPUT) — 사용자 문자열을 컬럼 선택에 직접 사용하지 않는다.
     */
    private static SearchField parseSearchField(String field) {
        if (field == null || field.isBlank()) {
            return null;
        }
        try {
            return SearchField.valueOf(field.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "지원하지 않는 검색 필드입니다.");
        }
    }
}
