package kr.co.cudo.authoring.label.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 라벨 마스터 생성/수정 요청 DTO.
 *
 * <p>Mass Assignment 방어를 위해 Entity 직접 바인딩 금지 — 허용 필드만 명시.
 *
 * <p>검증:
 * <ul>
 *   <li>{@code name}: 1~64자.</li>
 *   <li>{@code color}: 대문자 hex {@code #RRGGBB} 만 (소문자 입력 시 400).</li>
 *   <li>{@code type}: BBOX / POLYGON / POINT / SKELETON.</li>
 *   <li>{@code sortNo}: 0 이상.</li>
 * </ul>
 *
 * <p>SKELETON = 17-keypoint COCO 휴먼 포즈 카테고리(신규). 기존 {@code POINT}(단일 점 라벨)
 * 예약어를 오버로드하지 않고 별도 값으로 추가한다 — 마스터 타입과 라벨 shape 타입의 어휘가
 * 다르므로(POINT vs SKELETON) POINT 를 포즈로 재해석하면 기존 단일점 라벨 계약이 깨진다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LabelMasterRequest(
        @NotBlank(message = "name 은 필수입니다.")
        @Size(max = 64, message = "name 은 64자 이하여야 합니다.")
        String name,

        @NotBlank(message = "color 는 필수입니다.")
        @Pattern(regexp = "^#[0-9A-F]{6}$", message = "color 는 대문자 hex (#RRGGBB) 형식이어야 합니다.")
        String color,

        @NotBlank(message = "type 은 필수입니다.")
        @Pattern(regexp = "^(BBOX|POLYGON|POINT|SKELETON)$", message = "type 은 BBOX/POLYGON/POINT/SKELETON 중 하나여야 합니다.")
        String type,

        @Min(value = 0, message = "sortNo 는 0 이상이어야 합니다.")
        Integer sortNo,

        /**
         * AI(COCO) 검출 클래스 매핑 — 선택(null/blank=미매핑). 지정 시 COCO 80 클래스명이어야 하며
         * ({@code CocoClasses} allowlist), 위반 시 서비스가 400 으로 거부(자유텍스트 금지, CWE-20).
         * 길이 상한은 표준도메인 코드값 VARCHAR(20)과 정합(방어적 크기 제한).
         */
        @Size(max = 20, message = "dtctTypeCd 는 20자 이하여야 합니다.")
        String dtctTypeCd
) {
}
