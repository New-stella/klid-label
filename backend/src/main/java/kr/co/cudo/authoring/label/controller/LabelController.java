package kr.co.cudo.authoring.label.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.label.dto.Sam2SegmentRequest;
import kr.co.cudo.authoring.label.dto.Sam2SegmentResponse;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.label.dto.Sam2TrackResponseDto;
import kr.co.cudo.authoring.label.dto.YoloTrackRequest;
import kr.co.cudo.authoring.label.dto.YoloTrackResponseDto;
import kr.co.cudo.authoring.label.service.LabelService;
import kr.co.cudo.authoring.label.service.Sam2SegmentService;
import kr.co.cudo.authoring.label.service.Sam2TrackService;
import kr.co.cudo.authoring.label.service.YoloTrackService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Label", description = "라벨 CRUD 및 SAM2 Track / YOLO Track 추론 — REVIEWER/WORKER. 본인 배정 프레임 검증(IDOR 방어) 적용.")
@RestController
@RequestMapping("/v1/frames")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class LabelController {

    private final LabelService labelService;
    private final Sam2TrackService sam2TrackService;
    private final Sam2SegmentService sam2SegmentService;
    private final YoloTrackService yoloTrackService;

    @Operation(
            summary = "프레임 라벨 조회",
            description = "프레임에 부여된 모든 라벨(BBox/Polygon/Segmentation/Track) 반환. WORKER는 본인 배정 프레임만 접근 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 (CWE-639 방어)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음")
    })
    @GetMapping("/{srcSn}/labels")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<LabelResponse> getLabels(
            @Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
            @Parameter(description = "REVIEWER 한정 — true 면 원본(RAW) 프레임 응답. WORKER 는 무시되고 DEID 강제.")
            @RequestParam(name = "raw", defaultValue = "false") boolean raw,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(labelService.getByFrame(srcSn, actor, raw));
    }

    @Operation(
            summary = "프레임 라벨 일괄 저장 (임시저장)",
            description = "프레임 라벨을 일괄 upsert (전체 교체 의미론). 작업본 저장만 수행하며 버전 스냅샷은 생성하지 않는다. "
                    + "학습데이터 버전 스냅샷은 검수 승인(APPROVED) 시점에만 생성된다(SFR-08)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음")
    })
    @PutMapping("/{srcSn}/labels")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<LabelResponse> bulkUpsert(@Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
                                                  @Valid @RequestBody LabelBulkUpsertRequest req,
                                                  @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(labelService.bulkUpsert(srcSn, req, actor));
    }

    @Operation(
            summary = "SAM2 Track 추론",
            description = "프레임의 시드 마스크/박스를 ai-server로 송신하여 SAM2 트래킹 결과를 받는다. " +
                    "path srcSn과 body srcSn 불일치 시 400 (CWE-345)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패 / srcSn 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "ai-server 연동 실패 / 잘못된 응답")
    })
    @PostMapping("/{srcSn}/sam2-track")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<Sam2TrackResponseDto> sam2Track(@Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
                                                       @Valid @RequestBody Sam2TrackRequest req,
                                                       @AuthenticationPrincipal TokenClaims actor) {
        // path srcSn 과 body srcSn 불일치 시 거부 (CWE-345)
        if (!srcSn.equals(req.srcSn())) {
            throw new kr.co.cudo.authoring.common.exception.CustomException(
                    kr.co.cudo.authoring.common.exception.ErrorCode.INVALID_INPUT,
                    "path 의 srcSn 과 body 의 srcSn 이 다릅니다.");
        }
        return ApiResponse.ok(sam2TrackService.track(req, actor));
    }

    @Operation(
            summary = "SAM2 클릭/박스 분할",
            description = "프레임에 대해 클릭(포인트) 또는 드래그(박스) 프롬프트를 ai-server SAM2 로 프록시하여 "
                    + "폴리곤 + 신뢰도를 받는다. points/box 는 정확히 하나만 제공해야 한다(400). "
                    + "path srcSn 과 body srcSn 불일치 시 400 (CWE-345). 정책상 원본 이미지에만 실행."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패 / points·box 배타 위반 / srcSn 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 (CWE-639 방어)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "이미지 크기 상한 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "ai-server 연동 실패 / 잘못된 응답")
    })
    @PostMapping("/{srcSn}/sam2-segment")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<Sam2SegmentResponse> sam2Segment(@Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
                                                        @Valid @RequestBody Sam2SegmentRequest req,
                                                        @AuthenticationPrincipal TokenClaims actor) {
        // path 의 srcSn 과 body 의 srcSn 불일치 시 거부 (CWE-345).
        if (!srcSn.equals(req.srcSn())) {
            throw new kr.co.cudo.authoring.common.exception.CustomException(
                    kr.co.cudo.authoring.common.exception.ErrorCode.INVALID_INPUT,
                    "path 의 srcSn 과 body 의 srcSn 이 다릅니다.");
        }
        // 내부 mock(모델 미로드) 시 서비스가 빈 폴리곤을 반환한다 → 안내 message 세팅(자동적용 차단 신호).
        Sam2SegmentResponse res = sam2SegmentService.segment(req, actor);
        return res.isEmpty()
                ? ApiResponse.ok(res, Sam2SegmentResponse.MOCK_UNAVAILABLE_MESSAGE)
                : ApiResponse.ok(res);
    }

    @Operation(
            summary = "YOLO 객체 트랙 추론",
            description = "정렬된 프레임 시퀀스를 ai-server YOLO 트래커로 프록시하여 프레임별 객체 검출+trackId 를 받는다. "
                    + "DB 저장은 하지 않으며(FE 가 PUT /labels 로 저장), 배치 자동라벨링과 별개의 온디맨드 경로다. "
                    + "path srcSn 과 body srcSn 불일치 시 400 (CWE-345)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패 / srcSn 불일치 (CWE-345)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 (CWE-639 방어)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "ai-server 연동 실패 / 잘못된 응답")
    })
    @PostMapping("/{srcSn}/yolo-track")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<YoloTrackResponseDto> yoloTrack(@Parameter(description = "시작 프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
                                                       @Valid @RequestBody YoloTrackRequest req,
                                                       @AuthenticationPrincipal TokenClaims actor) {
        // path 의 srcSn 과 body 의 srcSn 불일치 시 거부 (CWE-345).
        if (!srcSn.equals(req.srcSn())) {
            throw new kr.co.cudo.authoring.common.exception.CustomException(
                    kr.co.cudo.authoring.common.exception.ErrorCode.INVALID_INPUT,
                    "path 의 srcSn 과 body 의 srcSn 이 다릅니다.");
        }
        return ApiResponse.ok(yoloTrackService.track(req, actor));
    }
}
