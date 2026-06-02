package kr.co.cudo.authoring.video.service.port;

import java.nio.file.Path;

/**
 * 영상 해상도 추출 포트 — Phase 3 (RQ-SFR-07-02).
 *
 * <p>ffprobe 바이너리 의존을 인터페이스로 격리한다. 서비스 단위 테스트는 stub 으로 주입하여
 * 바이너리 비의존으로 검증한다. 운영 구현은 {@code BrampVideoProbe}.
 */
public interface VideoProbe {

    /** 영상 가로/세로 해상도 (px). */
    record Dimensions(int width, int height) {
    }

    /**
     * 영상 파일에서 가로/세로 해상도를 추출한다.
     *
     * @param video 원본 영상 파일 경로
     * @return 가로/세로 해상도. video stream 이 없으면 width/height 가 0 으로 반환될 수 있으며,
     * 그 검증은 호출자(서비스) 책임이다.
     */
    Dimensions probe(Path video);
}
