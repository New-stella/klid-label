package kr.co.cudo.authoring.video.service.port;

import java.nio.file.Path;

/**
 * 프레임 이미지 다운스케일 포트 — Phase 1 (RQ-SFR-06-03 v1.8/1.10).
 *
 * <p>단일 프레임 이미지를 targetW x targetH <b>상한</b> 안에서 종횡비 보존 리스케일(축소·확대)하여
 * dst 에 저장한다. 산출 크기는 원본 종횡비에 따라 달라지며 패딩이 없다(@design ADR-018).
 * 영상(비디오) 리사이즈가 아니라 이미지 단위 처리다. 운영 구현은 {@code Java2DImageResizer}
 * (BufferedImage + Graphics2D BILINEAR).
 */
public interface ImageResizer {

    /**
     * 원본 프레임 이미지를 targetW x targetH <b>상한</b> 안에서 리스케일하여 dst 에 저장한다.
     *
     * <p>두 수치는 고정 캔버스가 아니다 — 원본의 짧은 변을 상한의 짧은 값에 맞추고, 그 배율로 계산한
     * 긴 변이 상한의 긴 값을 넘을 때만 배율을 낮춘다. 산출 캔버스는 그 실제 크기이므로 패딩이 없다.
     *
     * @param src     원본 프레임 이미지 경로
     * @param dst     출력 이미지 경로 (상위 디렉토리는 호출 측에서 보장)
     * @param targetW 가로 상한 (양수)
     * @param targetH 세로 상한 (양수)
     */
    void resize(Path src, Path dst, int targetW, int targetH);

    /**
     * 이미지의 실제 해상도를 읽는다(산출 크기·배율 산정을 위한 원본 실측에 사용).
     *
     * @param src 이미지 경로
     * @return [width, height]
     */
    int[] readDimensions(Path src);
}
