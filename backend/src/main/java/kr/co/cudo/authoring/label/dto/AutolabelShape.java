package kr.co.cudo.authoring.label.dto;

/**
 * 오토라벨/추적 결과 형태 (R12).
 *
 * <p>요청 DTO 의 자유 문자열 대신 <b>enum</b> 으로 받아 Jackson 역직렬화 단계에서 잘못된 값을 400 으로
 * 차단한다(CWE-20 — 화이트리스트 입력 검증). null 은 각 진입점 정책의 기본값으로 정규화한다
 * (오토라벨 = {@code BBOX} 하위호환, 추적 = {@code POLYGON} 기본).
 *
 * <ul>
 *   <li>{@code BBOX}    : YOLO 검출 박스([x1,y1,x2,y2]) 또는 폴리곤 외접 bbox.</li>
 *   <li>{@code POLYGON} : SAM box-prompt 분할 폴리곤 좌표.</li>
 * </ul>
 */
public enum AutolabelShape {
    BBOX,
    POLYGON
}
