package kr.co.cudo.authoring.dev.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.adminsession.RequiresAdminSession;
import kr.co.cudo.authoring.dev.dto.AutolabelTestRequest;
import kr.co.cudo.authoring.dev.dto.AutolabelTestResponse;
import kr.co.cudo.authoring.dev.service.DevAutolabelTestService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <p>{@code authoring.dev.upload.enabled=true}(env {@code DEV_UPLOAD_ENABLED}) 일 때만 빈이 등록되어
 * endpoint 가 노출된다 (값 미지정 시 미등록 — fail-closed). <b>기본값은 프로파일이 정한다</b> —
 * 운영(prd)은 ON, 그 밖은 OFF. 추가로 {@code @PreAuthorize("hasRole('ADMIN')")} 권한 가드.
 *
 * <h3>세 겹은 서로를 대체하지 않고 가산된다 [@design ADR-046 · API-152]</h3>
 * <p>운영 토글이 켜져야 창구가 열리고, <b>관리자 권한</b>이 있어야 하며, 그 위에 <b>관리자 단기 유효창</b>이
 * 하나 더 필요하다({@link RequiresAdminSession} — {@code X-Admin-Session} 헤더). 영상 업로드의 시작은
 * 운영·관리 성격의 쓰기이기 때문이며, 이 창구는 <b>한 번의 요청으로 업로드가 끝나므로</b> 확인도 이
 * 호출 하나에서 끝난다(TUS 처럼 이어 올리기 예외를 둘 자리가 없다).
 *
 * <p>유효창은 역할을 <b>승격시키지 않는다</b> — 발급받은 사람에게 결박돼 있어 요청은 여전히 본인
 * 자격으로 인증되고, 공유 패스워드로 여는 유효창인데도 업로드 기록에는 실제 행위자가 개인 단위로
 * 남는다. 없거나 만료됐으면 403 이며 권한 부족과 구분해 알리지 않는다(CWE-209).
 *
 * <h3>역할 표기가 <b>관리자</b>인 이유 [@design API-152 · ROLE-004 · ADR-055]</h3>
 * <p>유효창을 발급하는 창구({@code POST /v1/manage/admin-session})가 관리자 전용이고, 그 서비스의
 * 판정이 <b>역할 계층을 타지 않는 enum 동등 비교</b>({@code actor.role() != Role.ADMIN})라 검수자는
 * 유효창 자체를 얻을 수 없다. 즉 실효 게이트는 이전부터 관리자였고 표기만 검수자로 남아 있었다 —
 * 이 창구를 부르는 화면도 관리자 전용이다. 표기를 실효 게이트에 맞춘 것이므로 동작은 바뀌지 않는다.
 *
 * <p>⚠ {@code SecurityConfig} 의 {@code /v1/dev/**} 대역 매처는 <b>검수자 그대로 둔다</b> — 그
 * 아래 배치 트리거·스캔·대기 조회 등 다른 창구들은 설계상 정당하게 검수자다. 대역을 올리면 그것들이
 * 함께 막힌다. 이 창구를 좁히는 것은 대역이 아니라 메서드 가드와 유효창이다.
 *
 * <p>다음 보안 가드를 두 레이어에서 이중 적용한다:
 * <ul>
 *   <li>{@code AutolabelTestRequest} — {@code @Valid} 입력 검증 (영문/숫자 패턴 + 범위).</li>
 *   <li>{@link DevAutolabelTestService} — 파일 확장자/크기/MIME, 경로 순회, vmsClipId 중복(409),
 *       이벤트유형 마스터 등록 여부(400) 검증. <b>cctvId 는 형식만 검증하고 등록 여부는 보지 않는다</b> —
 *       대조할 CCTV 마스터가 없다(형식 가드는 요청 DTO 의 {@code @Pattern}).</li>
 * </ul>
 *
 * <h3>경로 이름 = dev 업로드 ({@code @design API-152})</h3>
 * <p>엔드포인트는 {@code /v1/dev/upload} 하나다. 이 화면이 올린 영상으로 확인하는 것은 오토라벨만이
 * 아니라 적재·비식별·마킹까지의 전 구간이라, 이름을 역할(수동 업로드)에 맞췄다. <b>구 경로는 별칭·
 * 리다이렉트 없이 폐기</b>됐고 404 가 정상이다 — dev 토글로 게이팅되는 내부 endpoint 라 관제·외부
 * 호출자가 없고, 별칭을 두면 구 이름이 영구히 남아 개명 목적이 사라진다.
 *
 * <p>구 이름과 그 회귀 가드는 {@code DevUploadPathRenameGuardTest} 한 곳에만 둔다(소스 전역에서
 * 구 이름을 0건으로 유지하려면 그 문자열이 사는 곳이 하나여야 한다).
 */
@Tag(name = "dev-upload",
        description = "수동 업로드 + 오토라벨 파이프라인 트리거. 노출은 authoring.dev.upload.enabled 가 정한다 (운영 기본 ON, 그 밖은 기본 OFF).")
@RestController
@RequestMapping("/v1/dev/upload")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "authoring.dev.upload", name = "enabled", havingValue = "true")
public class DevAutolabelTestController {

    private final DevAutolabelTestService devAutolabelTestService;

    @Operation(
            summary = "수동 업로드 + 오토라벨 파이프라인 트리거 (ADMIN + 관리자 단기 유효창)",
            description = """
                    multipart/form-data 로 영상 파일과 JSON 메타데이터를 함께 업로드한다.
                    응답은 즉시 200 으로 반환되며, 프레임 추출 + YOLO + SAM2 는 백그라운드로 실행된다.

                    <ul>
                      <li>인증 필요 — ADMIN 역할 + 관리자 단기 유효창(X-Admin-Session 헤더).</li>
                      <li>허용 확장자: mp4, webm, mov, avi. 기본 최대 크기 500MB.</li>
                      <li>vmsClipId 중복 시 409.</li>
                      <li>eventTypeCd 는 이벤트유형 마스터에 등록된 코드여야 한다 — 미등록 시 400.</li>
                      <li>cctvId 는 형식(영문/숫자/-/_ 1~64자)만 검증한다. 대조할 CCTV 마스터가 없어
                          등록 여부는 보지 않는다.</li>
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 검증 실패 / 확장자·MIME 불일치 / 미등록 이벤트유형 코드 / 영상 길이 추출 실패·허용 범위 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음(ADMIN 아님) 또는 관리자 단기 유효창 없음·만료 — 두 사유를 구분해 알리지 않는다"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "vmsClipId 중복"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "파일 크기 초과")
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresAdminSession
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
