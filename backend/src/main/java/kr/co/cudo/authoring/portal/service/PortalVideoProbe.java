package kr.co.cudo.authoring.portal.service;

import java.nio.file.Path;

/**
 * 포털 영상 메타 추출 추상화 — 비디오 스트림 존재/길이/fps 를 얻는다.
 *
 * <p>운영은 ffprobe 바이너리 구현({@code PortalVideoProbeFfprobe}, {@code @Profile("!test")})이고,
 * 테스트는 stub 을 생성자로 직접 주입해 바이너리 의존을 격리한다({@code TusUploadService.DurationProbe}
 * 와 동일 패턴).
 */
@FunctionalInterface
public interface PortalVideoProbe {

    /**
     * 영상 파일을 프로브한다.
     *
     * @param filePath 대상 영상 경로
     * @return 비디오 스트림 존재 여부 + 길이(초) + fps
     * @throws RuntimeException 프로브 실패/타임아웃(손상·미지원 형식 포함) — 호출자가 처리
     */
    Result probe(Path filePath);

    /**
     * 프로브 결과.
     *
     * @param hasVideoStream 비디오 스트림 존재 여부(오디오 전용 파일이면 false)
     * @param durationSec    길이(초)
     * @param fps            프레임레이트(미상이면 0 이하 — 호출자가 폴백)
     */
    record Result(boolean hasVideoStream, double durationSec, double fps) {
    }
}
