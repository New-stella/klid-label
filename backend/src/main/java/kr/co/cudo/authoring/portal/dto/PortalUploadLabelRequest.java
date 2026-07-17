package kr.co.cudo.authoring.portal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Phase 4 — 포털 업로드 프레임 라벨 전체교체(PUT) 요청 1행.
 *
 * <p>원본 미수정 — {@code LS_PORTAL_ULD_LBL} 별도 적재. FE 재사용 호환을 위해 좌표는 기존 포털
 * 패턴(POINT_CN JSON — BBOX 2점, POLYGON [[x,y]...])과 동일한 {@code [[x,y], ...]} 중첩 배열로 받는다.
 *
 * <p>입력 검증(CWE-20, fail-closed):
 * <ul>
 *   <li>{@code lblTypeCd} : BBOX|POLYGON allowlist(서비스에서 확정) — 그 외 400.</li>
 *   <li>{@code label} : LBL_NM 컬럼 길이(80) 상한.</li>
 *   <li>{@code points} : null 금지. 좌표 개수/원소 형식 상한은 서비스에서 타입별로 강제
 *       (BBOX=정확히 2점, POLYGON=3~200점).</li>
 * </ul>
 */
public record PortalUploadLabelRequest(
        @NotBlank(message = "lblTypeCd 는 필수입니다.") @Size(max = 16) String lblTypeCd,
        @Size(max = 80, message = "label 은 80자 이하여야 합니다.") String label,
        @NotNull(message = "points 는 필수입니다.") List<List<Double>> points
) {}
