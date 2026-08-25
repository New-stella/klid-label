package kr.co.cudo.authoring.preset.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.preset.dto.PresetCodeView;
import kr.co.cudo.authoring.preset.dto.PresetView;
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

import kr.co.cudo.authoring.preset.service.PresetService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 라벨링 프리셋 관리 — 프리셋은 <b>이벤트유형 1건 + 라벨 목록</b> 둘로 이루어진다.
 *
 * <p>프리셋은 이름·설명을 갖지 않는다(V17). 이벤트유형코드에 UNIQUE 가 걸려 이벤트 1건에 프리셋
 * 1건이 대응하므로 이름은 이벤트명의 중복이었고, 설명은 읽는 화면이 없었다. 사람이 읽는 이름은
 * 응답의 {@code eventTypeNm}(이벤트유형 마스터 표시명)이 제공한다.
 *
 * <p>프리셋 코드는 라벨 마스터(LS_LABEL) PK({@code labelId})로 연결되며, 라벨명·형태는 스냅샷 없이
 * 조회 시 마스터를 실시간 join 하여 파생한다.
 *
 * <p>보안:
 * <ul>
 *   <li>REVIEWER 전용 — SecurityConfig {@code /v1/manage/**} 매처 + {@code @PreAuthorize}.</li>
 *   <li>RequestBody 는 DTO ({@link PresetRequest}) 로 강제 — Mass Assignment 방어(Entity 직접 바인딩 금지).</li>
 *   <li>입력 검증: eventTypeCd 필수·20자 이하(서비스가 등록 여부를 동적 검증, 미등록 400),
 *       labelIds 1~20개(각 @NotNull @Positive). 마스터에 없는/soft delete labelId 는 서비스가
 *       400(INVALID_INPUT)으로 거부한다.</li>
 *   <li>JSON unknown 필드는 ignore — 클라이언트 호환성.</li>
 * </ul>
 *
 * <p>형태(BBOX/POLYGON)는 마스터 {@code LBL_TYPE_CD} 가 소유하므로 요청에서 받지 않는다. 응답의
 * 형태 토글은 마스터에서 파생된 읽기 전용 값이다.
 *
 * @design API-037
 * @design API-038
 * @design API-039
 * @design UC-032
 */
@Tag(name = "Preset", description = "라벨링 프리셋 관리 — REVIEWER 전용.")
@RestController
@RequestMapping("/v1/manage/presets")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
public class PresetController {

    /** 프리셋 라벨 코드 목록의 최대 개수. */
    private static final int MAX_LABEL_CODES = 20;

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
        PresetView saved = presetService.create(request.labelIds(), request.eventTypeCd());
        return ApiResponse.ok(toResponse(saved));
    }

    @Operation(summary = "프리셋 수정 (REVIEWER)")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<PresetResponse> update(@PathVariable long id, @Valid @RequestBody PresetRequest request) {
        PresetView updated = presetService.update(id, request.labelIds(), request.eventTypeCd());
        return ApiResponse.ok(toResponse(updated));
    }

    @Operation(summary = "프리셋 삭제 (REVIEWER)")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        presetService.delete(id);
    }

    /** View → Response 변환. timestamp 는 ISO-8601 UTC 문자열로 직렬화한다. */
    private static PresetResponse toResponse(PresetView view) {
        List<LabelCodeOptionResponse> opts = view.codes().stream()
                .map(PresetController::toCodeResponse)
                .toList();
        List<String> codes = view.codes().stream()
                .map(PresetCodeView::labelName)
                .toList();
        return new PresetResponse(
                view.presetId(),
                codes,
                opts,
                view.eventTypeCd(),
                view.eventTypeNm(),
                formatTimestamp(view.regDt()),
                formatTimestamp(view.mdfcnDt())
        );
    }

    private static LabelCodeOptionResponse toCodeResponse(PresetCodeView code) {
        return new LabelCodeOptionResponse(
                code.labelId(),
                code.code(),
                code.labelName(),
                code.labelType(),
                code.linked(),
                code.bboxEnabled(),
                code.polygonEnabled()
        );
    }

    private static String formatTimestamp(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.atOffset(ZoneOffset.UTC).toInstant().toString();
    }

    /**
     * 프리셋 생성/수정 요청 DTO — <b>이벤트유형코드 + 라벨 목록</b> 둘뿐이다.
     *
     * <p>프리셋 코드는 labelId 로 지정한다(형태는 마스터가 소유하므로 요청에서 받지 않음). 각 labelId 는
     * 활성 마스터에 존재해야 하며 아니면 서비스가 400 으로 거부한다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PresetRequest(
            // 이벤트유형은 필수다 — 이벤트에 걸리지 않은 프리셋은 어느 영상에도 매칭되지 않아
            //   오토라벨에 아무 기여를 하지 않는 죽은 행이 된다.
            @NotBlank(message = "이벤트유형은 필수입니다")
            // 상한 20 = 코드값 표준도메인(VARCHAR(20)) — 실제 컬럼 LS_LABEL_PRESET.EVNT_TYPE_CD 와 동일하다.
            //   최초 정의(V15)는 VARCHAR(32) 였으나 V107 이 코드값 표준도메인으로 정합(→20)했고 엔티티
            //   LsLabelPreset 도 length=20 이다. 이 DTO 만 32 로 남아 있어, 21~32자 입력이 검증을 통과한 뒤
            //   INSERT 시점에 DB 오류(500)로 새는 드리프트였다. 입구에서 400 으로 거부한다.
            //   ★상한을 넓혀 맞추지 말 것 — 표준도메인이 진실원이고 컬럼이 20 이다.
            @Size(max = 20, message = "이벤트 타입 코드는 20자 이하여야 합니다")
            String eventTypeCd,
            @NotNull(message = "라벨은 최소 1개 이상이어야 합니다")
            @Size(min = 1, max = MAX_LABEL_CODES, message = "라벨은 1~" + MAX_LABEL_CODES + "개까지 허용합니다")
            List<@NotNull(message = "labelId 는 필수입니다") @Positive(message = "labelId 는 양수여야 합니다") Long> labelIds
    ) {
    }

    /**
     * 응답 — 프리셋 코드 1건. 라벨명·형태는 마스터 실시간 join 결과다.
     *
     * <ul>
     *   <li>{@code labelId} : 마스터 PK (미연결 레거시 행이면 null).</li>
     *   <li>{@code code}    : 미연결 레거시 코드 문자열 (연결 행이면 null).</li>
     *   <li>{@code labelName} : 연결 시 마스터 라벨명(실시간), 미연결 시 legacy 코드.</li>
     *   <li>{@code labelType} : 마스터 {@code LBL_TYPE_CD} (미연결이면 null).</li>
     *   <li>{@code linked}  : 활성 마스터 연결 여부.</li>
     *   <li>{@code bboxEnabled}/{@code polygonEnabled} : 마스터 형태 파생(읽기 전용).</li>
     * </ul>
     */
    public record LabelCodeOptionResponse(
            Long labelId,
            String code,
            String labelName,
            String labelType,
            boolean linked,
            boolean bboxEnabled,
            boolean polygonEnabled
    ) {
    }

    /**
     * 응답 — 대상 이벤트({@code eventTypeCd} + 표시명 {@code eventTypeNm})와 라벨 목록.
     *
     * <p>{@code eventTypeNm} 은 서버가 표시명 해석 규칙(운영자 표시명 → 관제 수신 유형명 → 카테고리명
     * → 유형코드 4단 폴백)으로 채운다. <b>필터 옵션에서 제외된 대분류·비수집 유형이어도 채워진다</b> —
     * 화면이 이벤트 목록으로 이름을 역해석하면 그 목록에 없는 코드가 코드 그대로 노출된다.
     *
     * <p>{@code labelCodes} 는 라벨명 목록(레거시 호환), {@code labelCodeOptions} 는 상세다.
     */
    public record PresetResponse(
            long id,
            List<String> labelCodes,
            List<LabelCodeOptionResponse> labelCodeOptions,
            String eventTypeCd,
            String eventTypeNm,
            String createdAt,
            String updatedAt
    ) {
    }
}
