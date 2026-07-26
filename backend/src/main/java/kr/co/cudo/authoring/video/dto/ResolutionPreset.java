package kr.co.cudo.authoring.video.dto;

/**
 * 해상도 변경 표준 하위 해상도 프리셋 — Phase 1 (RQ-SFR-06-03 v1.8/1.10).
 *
 * <p>표준 하위 해상도 화이트리스트(1080p/720p/480p)만 제공한다. enum 바인딩으로 화이트리스트를
 * 강제하여 그 외의 값은 Jackson 역직렬화 단계에서 400(INVALID_INPUT) 으로 거부된다
 * (CWE-20 입력 검증 — 자유 입력 해상도 차단).
 *
 * <p>각 프리셋은 목표 가로/세로(픽셀)를 보유한다. 산출 프레임의 캔버스 크기는 항상
 * {@code width()×height()} 로 고정되며, 원본은 그 안에 <b>종횡비를 보존</b>한 채 배치된다.
 *
 * <h3>종횡비 정책 — 레터박스 보존 (확정, E-ISSUE-26/G-1)</h3>
 * <p>균일 배율 {@code min(targetW/srcW, targetH/srcH)} 로 리스케일하고 남는 영역은 검정 패딩으로 채운다
 * (레터박스/필러박스). 구 구현은 축별 독립 배율로 목표 프레임에 강제로 늘려 비-16:9 원본
 * (예: 1080×1920 세로 영상)을 <b>왜곡</b>시켰다.
 *
 * <p>배율·패딩 오프셋은 {@code LetterboxTransform} 이 <b>단일 계산</b>하며, 픽셀(리사이즈)과 라벨 좌표
 * (BBOX·POLYGON·세그멘테이션·키포인트)가 같은 값을 쓴다 — 라벨 좌표는 {@code x*scale + offsetX} 로
 * 변환되어 패딩된 그림 위에 정확히 얹힌다.
 *
 * <p><b>기존(정책 도입 이전) 파생본은 왜곡된 채 존치한다</b> — 재생성은 별도 작업이다.
 */
public enum ResolutionPreset {

    RESL_1080P(1920, 1080),
    RESL_720P(1280, 720),
    RESL_480P(854, 480);

    private final int width;
    private final int height;

    ResolutionPreset(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** 목표 캔버스 가로(px) — 원본은 이 안에 종횡비 보존 배치되고 남는 영역은 패딩된다. */
    public int width() {
        return width;
    }

    /** 목표 캔버스 세로(px) — 원본은 이 안에 종횡비 보존 배치되고 남는 영역은 패딩된다. */
    public int height() {
        return height;
    }
}
