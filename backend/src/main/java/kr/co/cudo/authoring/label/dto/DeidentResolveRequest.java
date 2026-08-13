package kr.co.cudo.authoring.label.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * R3 — 비식별 신고 수동 해소 요청 DTO.
 *
 * <h3>{@code fileName} 은 필수다 — 서버가 기본값을 고르지 않는다</h3>
 * <p>외부 솔루션은 산출물을 <b>다른 이름</b>({@code {원본stem}-mask{ext}} 등)으로 만들 수 있어, 어느
 * 파일이 재비식별 결과인지 서버가 단정할 수 없다. 사람이 후보 목록
 * ({@code GET /v1/deident-reports/{rprtSn}/deident-candidates})에서 고르게 하고, 누락·공백이면 400 이다.
 * "기본 선택 없음"의 서버측 강제가 이 {@link NotBlank} 다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>Path Manipulation (CWE-22)</b>: 이 값으로 경로를 조립하지 않는다 — 서버가 다시 만든
 *       후보 목록에 <b>정확히 일치하는 항목이 있을 때만</b> 수락한다(목록이 곧 허용목록).
 *       상한(255자)은 파일명 최대 길이 관례를 넘는 입력을 입구에서 자르기 위한 것이다(CWE-770).</li>
 *   <li><b>Mass Assignment 방어</b>: 선택 파일명만 노출한다(상태·해소자 등 서버 전용 필드 비공개).</li>
 * </ul>
 *
 * @param fileName 후보 목록에서 고른 재비식별 산출물 파일명(basename)
 */
public record DeidentResolveRequest(
        @NotBlank(message = "재비식별 산출물 파일을 선택해 주세요.")
        @Size(max = 255, message = "파일명이 너무 깁니다.")
        String fileName
) {
}
