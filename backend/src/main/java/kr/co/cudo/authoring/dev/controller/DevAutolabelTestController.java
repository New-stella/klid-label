package kr.co.cudo.authoring.dev.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.dev.dto.AutolabelTestRequest;
import kr.co.cudo.authoring.dev.dto.AutolabelTestResponse;
import kr.co.cudo.authoring.dev.service.DevAutolabelTestService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * [개발/검수 전용] 영상 파일 + 메타데이터 업로드 → 오토라벨 파이프라인 즉시 트리거 endpoint.
 *
 * <p>운영(prd) 환경에서는 {@code @Profile("!prd")} 로 빈 자체가 등록되지 않아 endpoint 가 부재한다.
 * 추가로 {@code @PreAuthorize("hasRole('REVIEWER')")} 로 권한 가드.
 *
 * <p>다음 보안 가드를 두 레이어에서 이중 적용한다:
 * <ul>
 *   <li>{@code AutolabelTestRequest} — {@code @Valid} 입력 검증 (영문/숫자 패턴 + 범위).</li>
 *   <li>{@link DevAutolabelTestService} — 파일 확장자/크기/MIME, 경로 순회, vmsClipId/cctvId 검증.</li>
 * </ul>
 */
@Tag(name = "dev-autolabel-test",
        description = "[개발/검수 전용] 영상 업로드 + 오토라벨 파이프라인 트리거. ⚠ 운영(prd) 미노출.")
@RestController
@RequestMapping("/v1/dev/autolabel-test")
@RequiredArgsConstructor
@Profile("!prd")
public class DevAutolabelTestController {

    private final DevAutolabelTestService devAutolabelTestService;

    @Operation(
            summary = "영상 업로드 + 오토라벨 파이프라인 트리거 (개발/검수 전용)",
            description = """
                    multipart/form-data 로 영상 파일과 JSON 메타데이터를 함께 업로드한다.
                    응답은 즉시 200 으로 반환되며, 프레임 추출 + YOLO + SAM2 는 백그라운드로 실행된다.

                    <ul>
                      <li>인증 필요 — REVIEWER 역할만 호출 가능.</li>
                      <li>허용 확장자: mp4, webm, mov, avi. 기본 최대 크기 500MB.</li>
                      <li>vmsClipId 중복 시 409, cctvId 미등록 시 400.</li>
                    </ul>
                    """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(implementation = MultipartUploadForm.class),
                            encoding = {
                                    @Encoding(name = "file", contentType = "video/*"),
                                    @Encoding(name = "meta", contentType = MediaType.APPLICATION_JSON_VALUE)
                            }
                    )
            )
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "업로드 성공 + 파이프라인 트리거"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 검증 실패 / 확장자 불일치 / cctvId 미등록"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음 (REVIEWER 아님)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "vmsClipId 중복"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "파일 크기 초과")
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<AutolabelTestResponse> upload(
            @RequestPart("file") MultipartFile file,
            @Valid @RequestPart("meta") AutolabelTestRequest meta
    ) {
        return ApiResponse.ok(devAutolabelTestService.upload(file, meta));
    }

    /** Swagger UI 용 multipart 스키마 더미. 실제 바인딩에는 사용되지 않는다. */
    @Schema(description = "multipart/form-data 업로드 파트")
    @SuppressWarnings("unused")
    static class MultipartUploadForm {
        @Schema(description = "영상 파일 (mp4/webm/mov/avi)", type = "string", format = "binary")
        public Object file;
        @Schema(description = "JSON 메타데이터", implementation = AutolabelTestRequest.class)
        public AutolabelTestRequest meta;
    }
}
