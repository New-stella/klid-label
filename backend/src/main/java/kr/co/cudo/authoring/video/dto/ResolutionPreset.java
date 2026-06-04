package kr.co.cudo.authoring.video.dto;

/**
 * 해상도 변경 표준 하위 해상도 프리셋 — Phase 1 (RQ-SFR-06-03 v1.8/1.10).
 *
 * <p>표준 하위 해상도 화이트리스트(1080p/720p/480p)만 제공한다. enum 바인딩으로 화이트리스트를
 * 강제하여 그 외의 값은 Jackson 역직렬화 단계에서 400(INVALID_INPUT) 으로 거부된다
 * (CWE-20 입력 검증 — 자유 입력 해상도 차단).
 *
 * <p>각 프리셋은 목표 가로/세로(픽셀)를 보유한다. 실제 다운스케일은 원본 종횡비를 보존하므로
 * 목표 세로(height)를 기준으로 비율을 산정하고 가로는 종횡비에 맞춰 계산한다.
 */
public enum ResolutionPreset {

    RES_1080P(1920, 1080),
    RES_720P(1280, 720),
    RES_480P(854, 480);

    private final int width;
    private final int height;

    ResolutionPreset(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** 프리셋 기준 가로(px) — 16:9 기준값(참고). 실제 출력 가로는 원본 종횡비 보존으로 산정. */
    public int width() {
        return width;
    }

    /** 프리셋 기준 세로(px) — 다운스케일 비율 산정 기준. */
    public int height() {
        return height;
    }
}
