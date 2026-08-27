package kr.co.cudo.authoring.upload.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.security.adminsession.RequiresAdminSession;
import kr.co.cudo.authoring.upload.dto.InternalUploadCreateRequest;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import kr.co.cudo.authoring.upload.service.InternalUploadWiringGuard;
import kr.co.cudo.authoring.upload.service.TusUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.UUID;

/**
 * TUS 1.0 재개 가능 업로드 endpoint (관리 화면 대용량 영상 적재 — REVIEWER/INTERNAL 전용).
 *
 * <p><b>응답 형식 결정 (근거)</b>: 본 컨트롤러는 표준 {@code ApiResponse<T>} 래퍼를 사용하지
 * 않고 <b>TUS 1.0 프로토콜 규약(헤더 기반)</b>을 따른다. tus-js-client 등 표준 클라이언트는
 * {@code Upload-Offset}/{@code Location}/{@code Tus-Resumable} 응답 헤더와 상태코드로
 * 진행/재개를 제어하므로, 본문 래핑은 프로토콜 호환을 깨뜨린다.
 * <p><b>단, {@code POST}(세션 생성)는 표준 이탈이다</b> — 메타를 {@code Upload-Metadata} 헤더가
 * 아니라 <b>{@code application/json} 바디</b>로 받는다({@link #create} 참조). 그래서 표준 클라이언트로
 * 세션을 만들려면 이 계약을 알아야 하며, 우리는 {@code creation-with-upload} 를 광고하지 않는다.
 * {@code HEAD}/{@code PATCH}/{@code DELETE} 는 표준 그대로다. 오류 경로는
 * {@code CustomException} → {@code GlobalExceptionHandler} 가 {@code ApiResponse.error} JSON 으로
 * 응답하되 상태코드(409/410/412/413/429/403)는 TUS 의미를 그대로 유지한다.
 *
 * <p>인증/인가: SecurityConfig {@code /v1/**}(INTERNAL 채널) + {@code @PreAuthorize("hasRole('REVIEWER')")}.
 * 세션 소유자 검증(HIGH-8)은 서비스에서 USER_NO(토큰 sub) 기준 수행.
 *
 * <h3>관리자 단기 유효창은 <b>세션 생성 한 곳</b>에만 요구한다 [@design ADR-046 · API-158]</h3>
 * <p>업로드 시작({@link #create})은 운영·관리 성격의 쓰기라 검수자 권한만으로 열리지 않고
 * {@link RequiresAdminSession} 이 붙는다. 반면 {@code PATCH}(이어 올리기)·{@code DELETE}(취소)·
 * {@code HEAD}(진행 위치)·{@code OPTIONS}(능력 광고)에는 <b>요구하지 않는다</b> — 대용량 영상은
 * 유효창(기본 10분)보다 오래 걸려, 조각마다 요구하면 큰 파일은 <b>구조적으로 올릴 수 없다</b>.
 *
 * <p>그런데도 새지 않는 근거는 <b>이미 있던 소유자 판정</b>이다. 업로드 식별자가 사실상 허가증이
 * 되는 자리에 {@code getForOwner}·{@code appendChunk}·{@code cancel} 이 전부 토큰 {@code sub} 를
 * 넘겨 {@code LS_TUS_UPLOAD.USER_NO}(NOT NULL)와 대조하므로, 식별자를 알아도 소유자가 아니면 이어
 * 쓸 수 없다. 그리고 그 소유자는 <b>유효창을 연 바로 그 사람</b>이다 — 두 판정이 같은 자격 주체
 * ({@code sub})를 기준으로 하기 때문이며, 이 소유자 규칙은 이번에 신설한 것이 아니다.
 *
 * <p>⚠ 포털 채널 이용자의 자기 자산 업로드({@code /v1/portal/uploads/**})는 <b>이 요구의 대상이
 * 아니다</b> — 관리 행위가 아니라 그 이용자 본인의 데이터이며, 함께 묶으면 그 기능이 성립하지 않는다.
 */
@Tag(name = "tus-upload",
        description = "TUS 1.0 재개 가능 업로드 — 관리 화면 대용량 영상 적재. 헤더 기반 프로토콜(ApiResponse 미사용).")
@RestController
@RequestMapping("/v1/uploads")
@RequiredArgsConstructor
public class TusUploadController {

    private static final String TUS_VERSION = "1.0.0";
    private static final String H_RESUMABLE = "Tus-Resumable";
    private static final String H_VERSION = "Tus-Version";
    private static final String H_EXTENSION = "Tus-Extension";
    private static final String H_MAX_SIZE = "Tus-Max-Size";
    private static final String H_UPLOAD_LENGTH = "Upload-Length";
    private static final String H_UPLOAD_OFFSET = "Upload-Offset";
    private static final String OFFSET_OCTET_STREAM = "application/offset+octet-stream";

    /**
     * 완료 응답에 <b>인입 대기</b> 상태를 드러내는 비표준 헤더 (DEV_FIX F5).
     *
     * <p>업로드 완료는 곧 적재가 아니다 — 인입 행이 {@code PENDING} 으로 남고 폴링 배치가 적재한다.
     * 그 폴링이 꺼져 있으면 영원히 적재되지 않는데 화면에는 아무 신호도 없었다. FE 표시는 별도
     * Phase 이므로 BE 는 로그와 이 헤더까지만 책임진다.
     */
    private static final String H_INGEST_STATUS = "X-Ingest-Status";
    /** 인입 행이 생성돼 폴링 적재를 대기 중. */
    private static final String INGEST_PENDING = "PENDING";
    /** 인입 행은 생겼으나 폴링이 꺼져 있어 적재되지 않는다(운영 형상 오류). */
    private static final String INGEST_PENDING_SCAN_DISABLED = "PENDING_SCAN_DISABLED";

    private final TusUploadService tusUploadService;
    /** 저장 경로 오설정 형상에서 업로드 기능만 닫는 게이트(F4-b) + 인입 폴링 관측(F5). */
    private final InternalUploadWiringGuard wiringGuard;

    /**
     * OPTIONS — TUS 서버 능력 광고 (tus-js-client 사전 협상).
     *
     * <p><b>{@code creation-with-upload} 를 광고하지 않는다</b> — 우리는 POST 바디를
     * <b>인입 메타 JSON</b> 으로 쓰기 때문이다({@link #create} 참조). 이 확장을 광고하면 표준
     * 클라이언트가 POST 바디에 <b>첫 청크(바이너리)</b>를 실어 보내 계약이 정면 충돌한다.
     */
    @RequestMapping(method = RequestMethod.OPTIONS)
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<Void> options() {
        return ResponseEntity.noContent()
                .header(H_RESUMABLE, TUS_VERSION)
                .header(H_VERSION, TUS_VERSION)
                .header(H_EXTENSION, "creation,termination")
                .header(H_MAX_SIZE, String.valueOf(524288000L))
                .build();
    }

    /**
     * POST — 세션 생성. 201 + Location: /v1/uploads/{uploadId}.
     *
     * <h3>★ 표준 이탈 — 메타를 {@code Upload-Metadata} 헤더가 아니라 <b>JSON 바디</b>로 받는다</h3>
     * <p>TUS 1.0 의 creation 확장은 본래 POST 바디가 <b>비어 있다</b>고 보고, 메타는
     * {@code Upload-Metadata}(base64) 헤더로 싣는다. 우리는 관제 인입 29컬럼을 그대로 재현해야 하는데
     * 관제일지({@code MNTR_CN VARCHAR(4000)}) 하나만으로도 헤더 상한(1KB)을 넘긴다. 그래서 이 엔드포인트만
     * {@code application/json} 바디를 받는다. {@code creation-with-upload} 를 구현하지 않으므로(POST 바디에
     * 첫 청크를 싣는 확장) 프로토콜과 충돌하지 않으며, 그 확장을 {@code Tus-Extension} 에
     * <b>광고하지도 않는다</b>({@link #options}).
     *
     * <p>{@code Upload-Length} 는 TUS 헤더 그대로다 — 전송 크기는 프로토콜의 소관이고 인입 메타가 아니다.
     * <b>포털 업로드({@code /v1/portal/uploads/tus})는 별도 컨트롤러라 헤더 방식 그대로다.</b>
     *
     * <p>세션 생성 시점에 <b>인입 행(PENDING)</b> 이 만들어지므로 응답에도 인입 상태를 실어 준다
     * (폴링이 꺼진 형상에서 "업로드는 됐는데 영영 적재 안 됨"이 화면에 드러나게 한다).
     *
     * <h3>★ 관리자 단기 유효창을 요구하는 <b>유일한</b> 업로드 창구다 [@design ADR-046 · API-158]</h3>
     * <p>검수자 권한에 <b>가산</b>되며 대체하지 않는다 — 유효창은 역할을 승격시키지 않는다. 토큰은
     * {@code X-Admin-Session} 헤더로 싣고, 없거나 만료·위조·타인 토큰이면 <b>모두 같은 403·같은
     * 문구</b>다(권한 부족과도 구분하지 않는다 — 응답이 유효창 보유 여부의 오라클이 되면 안 된다,
     * CWE-209).
     *
     * <p>이 표식을 {@code PATCH}·{@code DELETE}·{@code HEAD}·{@code OPTIONS} 로 넓히거나 클래스
     * 레벨로 올리지 말 것 — 그 순간 대용량 업로드가 유효창 만료 시점에 끊긴다(이 라운드의 최악 회귀).
     * 근거는 클래스 javadoc 의 소유자 판정 문단에 있다.
     */
    @Operation(summary = "TUS 업로드 세션 생성 (REVIEWER + 관리자 단기 유효창) — 인입 메타는 JSON 바디")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('REVIEWER')")
    @RequiresAdminSession
    public ResponseEntity<Void> create(
            @RequestHeader(value = H_RESUMABLE, required = false) String tusResumable,
            @RequestHeader(value = H_UPLOAD_LENGTH, required = false) Long uploadLength,
            @Valid @RequestBody InternalUploadCreateRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        wiringGuard.requireUploadEnabled();
        requireTusVersion(tusResumable);
        if (uploadLength == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Length 헤더가 필요합니다.");
        }
        UUID uploadId = tusUploadService.createSession(requireUser(actor), uploadLength, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(H_RESUMABLE, TUS_VERSION)
                .header(HttpHeaders.LOCATION, "/v1/uploads/" + uploadId)
                .header(H_INGEST_STATUS, ingestStatus())
                .build();
    }

    /** HEAD — 현재 offset 조회 (재개용). */
    @Operation(summary = "TUS 업로드 offset 조회 (재개)")
    @RequestMapping(value = "/{uploadId}", method = RequestMethod.HEAD)
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<Void> head(
            @RequestHeader(value = H_RESUMABLE, required = false) String tusResumable,
            @PathVariable UUID uploadId,
            @AuthenticationPrincipal TokenClaims actor) {
        wiringGuard.requireUploadEnabled();
        requireTusVersion(tusResumable);
        LsTusUpload session = tusUploadService.getForOwner(uploadId, requireUser(actor));
        return ResponseEntity.noContent()
                .header(H_RESUMABLE, TUS_VERSION)
                .header(H_UPLOAD_OFFSET, String.valueOf(session.getUploadOffset()))
                .header(H_UPLOAD_LENGTH, String.valueOf(session.getUploadLength()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    /**
     * PATCH — 청크 append (application/offset+octet-stream).
     *
     * <p>HIGH-2: 본문을 {@code byte[]} 로 전체 메모리 적재하지 않고
     * {@link HttpServletRequest#getInputStream()} 으로 서비스에 스트리밍 위임한다(OOM 방어).
     * 서비스가 고정 64KB 버퍼로 FileChannel 에 기록하며 청크당 상한 초과 시 413 + truncate 롤백.
     */
    @Operation(summary = "TUS 청크 업로드")
    @PatchMapping(value = "/{uploadId}", consumes = OFFSET_OCTET_STREAM)
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<Void> patch(
            @RequestHeader(value = H_RESUMABLE, required = false) String tusResumable,
            @RequestHeader(value = H_UPLOAD_OFFSET, required = false) Long uploadOffset,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @PathVariable UUID uploadId,
            HttpServletRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        wiringGuard.requireUploadEnabled();
        requireTusVersion(tusResumable);
        if (uploadOffset == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Offset 헤더가 필요합니다.");
        }
        // Content-Length 선검증 — 누락 시 스트리밍 길이 판단 불가하므로 거부.
        if (contentLength == null || contentLength <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Content-Length 헤더가 필요합니다.");
        }
        try (InputStream in = request.getInputStream()) {
            TusUploadService.TusPatchResult result =
                    tusUploadService.appendChunk(uploadId, requireUser(actor), uploadOffset, in, contentLength);
            ResponseEntity.HeadersBuilder<?> response = ResponseEntity.noContent()
                    .header(H_RESUMABLE, TUS_VERSION)
                    .header(H_UPLOAD_OFFSET, String.valueOf(result.newOffset()));
            if (result.completed()) {
                // F5 — 완료 != 적재. 인입 행은 PENDING 이고 폴링이 꺼져 있으면 영영 적재되지 않는다.
                response.header(H_INGEST_STATUS, ingestStatus());
            }
            return response.build();
        } catch (java.io.IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "청크 처리 중 오류가 발생했습니다.");
        }
    }

    /** DELETE — 세션 취소 + 임시파일 삭제. */
    @Operation(summary = "TUS 업로드 세션 취소")
    @DeleteMapping("/{uploadId}")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = H_RESUMABLE, required = false) String tusResumable,
            @PathVariable UUID uploadId,
            @AuthenticationPrincipal TokenClaims actor) {
        wiringGuard.requireUploadEnabled();
        requireTusVersion(tusResumable);
        tusUploadService.cancel(uploadId, requireUser(actor));
        return ResponseEntity.noContent().header(H_RESUMABLE, TUS_VERSION).build();
    }

    // ======================== 헬퍼 ========================

    /** Tus-Resumable 버전 불일치 → 412. */
    private void requireTusVersion(String tusResumable) {
        if (tusResumable != null && !TUS_VERSION.equals(tusResumable)) {
            throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                    "지원하지 않는 TUS 버전입니다. 필요: " + TUS_VERSION);
        }
    }

    private String requireUser(TokenClaims actor) {
        if (actor == null || actor.sub() == null || actor.sub().isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 정보가 없습니다.");
        }
        return actor.sub();
    }

    /** 인입 대기 상태 — 폴링이 꺼져 있으면 그 사실을 응답에 드러낸다(F5). */
    private String ingestStatus() {
        return wiringGuard.ingestScanEnabled() ? INGEST_PENDING : INGEST_PENDING_SCAN_DISABLED;
    }
}
