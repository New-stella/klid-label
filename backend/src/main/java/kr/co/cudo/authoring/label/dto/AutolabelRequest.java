package kr.co.cudo.authoring.label.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Phase 4 — YOLO 오토라벨 수동 트리거 요청 (선택적 클래스 필터, R3 AC3).
 *
 * <p><b>하위호환</b>: body 전체가 선택적이다. body 없이 호출하면 {@code classes=null} 로 기존 동작(전체 검출)을
 * 그대로 유지한다(무회귀). {@code classes} 를 지정하면 ai-server 가 detection.label(COCO 영문명) 이 목록에
 * 포함된 결과만 반환하도록 필터한다.
 *
 * <p><b>입력 검증(CWE-20)</b>: 화이트리스트 기반 필터이므로 리소스 소모 방지를 위해 리스트 크기(최대 100)와
 * 각 원소 길이(최대 50)를 제한한다. 빈 리스트는 서비스에서 null(전체)로 정규화한다.
 *
 * <p><b>형태(shape, R12)</b>: {@code shape} 를 {@link AutolabelShape} enum 으로 받아 잘못된 문자열을
 * Jackson 역직렬화 단계에서 400 으로 차단한다(CWE-20). {@code null}(미지정)이면 {@code BBOX}(하위호환).
 * {@code POLYGON} 이면 YOLO 검출 박스를 각각 SAM box-prompt 로 분할해 폴리곤 좌표를 반환한다(DB 미저장).
 *
 * <p><b>정밀도 per-request override(FEAT-007, 라벨링 화면 조절)</b>: {@code confThreshold}(인식 민감도)와
 * {@code simplifyTolerance}(경계 세밀함)는 <b>선택적</b>이다. null(미지정)이면 서비스가 시스템설정
 * (LS_SYSTEM_CONFIG)→상수 폴백 경로를 그대로 사용한다(무회귀). 지정 시 해당 요청에 한해 시스템설정 대신
 * 요청값을 사용한다. 범위(conf 0.25~0.80, simplify 0.0~50.0)는 {@code @DecimalMin/@DecimalMax} 로
 * 컨트롤러 {@code @Valid} 단계에서 검증되어 위반 시 400(CWE-20). {@code simplifyTolerance} 는 POLYGON 모드에만
 * 의미가 있다(BBOX 모드는 폴리곤 단순화가 없어 무시).
 *
 * @param classes           검출 대상 클래스 라벨(COCO 영문명) 화이트리스트. null/빈 → 전체(미필터)
 * @param shape             결과 형태(BBOX 기본 | POLYGON). null → BBOX(하위호환)
 * @param confThreshold     인식 민감도 override(0.25~0.80). null → 시스템설정 기본
 * @param simplifyTolerance 경계 세밀함 override(0.0~50.0, POLYGON 전용). null → 시스템설정 기본
 */
public record AutolabelRequest(
        @Size(max = 100, message = "클래스는 최대 100개까지 지정할 수 있습니다.")
        List<@Size(max = 50, message = "클래스명이 너무 깁니다.") String> classes,
        AutolabelShape shape,
        @DecimalMin(value = "0.25", message = "인식 민감도는 0.25 이상이어야 합니다.")
        @DecimalMax(value = "0.80", message = "인식 민감도는 0.80 이하여야 합니다.")
        Double confThreshold,
        @DecimalMin(value = "0.0", message = "경계 세밀함은 0.0 이상이어야 합니다.")
        @DecimalMax(value = "50.0", message = "경계 세밀함은 50.0 이하여야 합니다.")
        Double simplifyTolerance) {

    /** 빈 리스트/null 을 전체(미필터) 신호인 null 로 정규화. */
    public List<String> classesOrNull() {
        return (classes == null || classes.isEmpty()) ? null : classes;
    }

    /** null shape 를 하위호환 기본값 {@code BBOX} 로 정규화. */
    public AutolabelShape shapeOrDefault() {
        return shape == null ? AutolabelShape.BBOX : shape;
    }
}
