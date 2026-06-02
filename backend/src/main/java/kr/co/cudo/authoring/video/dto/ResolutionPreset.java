package kr.co.cudo.authoring.video.dto;

/**
 * 해상도 변경 배율 프리셋 — Phase 3 (RQ-SFR-07-02 해상도 변경 증강).
 *
 * <p>종횡비 보존 단일 배율(factor)만 제공한다. enum 바인딩으로 화이트리스트를 강제하여
 * P75/P50/P25 외의 값은 Jackson 역직렬화 단계에서 400(INVALID_INPUT) 으로 거부된다
 * (CWE-20 입력 검증 — 자유 입력 배율 차단).
 *
 * <p>확장 여지: 향후 P10(0.1) 등 추가가 필요하면 enum 상수만 추가한다. 자유 배율(double)
 * 입력은 의도적으로 허용하지 않는다(라벨 좌표 스케일 정합·과소 축소 방어를 단순화하기 위함).
 */
public enum ResolutionPreset {

    P75(0.75),
    P50(0.50),
    P25(0.25);

    private final double factor;

    ResolutionPreset(double factor) {
        this.factor = factor;
    }

    /** 종횡비 보존 단일 배율 (scaleX == scaleY). */
    public double factor() {
        return factor;
    }
}
