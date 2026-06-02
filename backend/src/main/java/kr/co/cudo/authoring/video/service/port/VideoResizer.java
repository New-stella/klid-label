package kr.co.cudo.authoring.video.service.port;

import java.nio.file.Path;

/**
 * 영상 리사이즈 포트 — Phase 3 (RQ-SFR-07-02).
 *
 * <p>ffmpeg 바이너리 의존을 인터페이스로 격리한다. 서비스 단위 테스트는 stub 으로 주입한다.
 * 운영 구현은 {@code BrampVideoResizer} (ProcessBuilder + waitFor 타임아웃).
 */
public interface VideoResizer {

    /**
     * 원본 영상을 targetW x targetH 로 리사이즈하여 dst 에 저장한다.
     *
     * @param src     원본 영상 경로
     * @param dst     출력 영상 경로 (결정론적 경로 — 재시도 시 덮어쓰기)
     * @param factor  적용 배율 (로깅/검증용)
     * @param targetW 타겟 가로 (짝수)
     * @param targetH 타겟 세로 (짝수)
     */
    void resize(Path src, Path dst, double factor, int targetW, int targetH);
}
