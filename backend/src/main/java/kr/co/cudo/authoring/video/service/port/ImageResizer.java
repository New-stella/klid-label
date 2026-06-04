package kr.co.cudo.authoring.video.service.port;

import java.nio.file.Path;

/**
 * 프레임 이미지 다운스케일 포트 — Phase 1 (RQ-SFR-06-03 v1.8/1.10).
 *
 * <p>단일 프레임 이미지를 targetW x targetH 로 종횡비 보존 다운스케일하여 dst 에 저장한다.
 * 영상(비디오) 리사이즈가 아니라 이미지 단위 처리다. 운영 구현은 {@code Java2DImageResizer}
 * (BufferedImage + Graphics2D BILINEAR).
 */
public interface ImageResizer {

    /**
     * 원본 프레임 이미지를 targetW x targetH 로 다운스케일하여 dst 에 저장한다.
     *
     * @param src     원본 프레임 이미지 경로
     * @param dst     출력 이미지 경로 (상위 디렉토리는 호출 측에서 보장)
     * @param targetW 타겟 가로 (양수)
     * @param targetH 타겟 세로 (양수)
     */
    void resize(Path src, Path dst, int targetW, int targetH);

    /**
     * 이미지의 실제 해상도를 읽는다(업스케일 거부용 실측 비교에 사용).
     *
     * @param src 이미지 경로
     * @return [width, height]
     */
    int[] readDimensions(Path src);
}
