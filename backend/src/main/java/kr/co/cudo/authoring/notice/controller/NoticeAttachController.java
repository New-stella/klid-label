package kr.co.cudo.authoring.notice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.notice.dto.NoticeAttachResponse;
import kr.co.cudo.authoring.notice.entity.LsNoticeAttach;
import kr.co.cudo.authoring.notice.service.NoticeAttachService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * 게시판(공지) 첨부파일 REST API.
 *
 * <p>권한: 업로드/삭제는 REVIEWER 전용, 다운로드는 REVIEWER/WORKER.
 * DRAFT 공지의 첨부 다운로드는 WORKER 에게 404 (Phase 1 가시성 규칙 일관).
 * PORTAL_USER 는 SecurityConfig 의 {@code /v1/notices/**} 매처로 차단(403).
 */
@Tag(name = "NoticeAttach", description = "게시판 첨부파일 — 업로드/삭제는 REVIEWER, 다운로드는 REVIEWER/WORKER.")
@RestController
@RequestMapping("/v1/notices/{id}/attachments")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class NoticeAttachController {

    private final NoticeAttachService attachService;

    @Operation(summary = "첨부파일 업로드 (REVIEWER)",
            description = "multipart/form-data 의 file 파트. 허용 확장자만, 최대 20MB.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "업로드 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "확장자/빈 파일/파일명 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음 (REVIEWER 아님)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공지 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "파일 크기 초과")
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NoticeAttachResponse> upload(
            @PathVariable long id,
            @Parameter(description = "첨부 파일") @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal TokenClaims actor) {
        LsNoticeAttach saved = attachService.upload(id, file, actor);
        return ApiResponse.ok(NoticeAttachResponse.from(saved));
    }

    @Operation(summary = "첨부파일 다운로드 (REVIEWER/WORKER)",
            description = "DRAFT 공지의 첨부는 WORKER 에게 404. 한글 파일명은 RFC 5987 인코딩.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "다운로드 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공지/첨부 없음 또는 DRAFT 가시성 차단")
    })
    @GetMapping("/{attachId}/download")
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ResponseEntity<Resource> download(
            @PathVariable long id,
            @PathVariable long attachId,
            @AuthenticationPrincipal TokenClaims actor) {
        NoticeAttachService.Download download = attachService.download(id, attachId, actor);

        // RFC 5987 — 한글 파일명을 UTF-8 percent-encoding (filename*) 으로 안전 전달.
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.contentLength())
                .body(download.resource());
    }

    @Operation(summary = "첨부파일 삭제 (REVIEWER)", description = "DB row + 물리 파일 모두 삭제.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음 (REVIEWER 아님)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공지/첨부 없음")
    })
    @DeleteMapping("/{attachId}")
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable long id,
            @PathVariable long attachId,
            @AuthenticationPrincipal TokenClaims actor) {
        attachService.delete(id, attachId, actor);
    }
}
