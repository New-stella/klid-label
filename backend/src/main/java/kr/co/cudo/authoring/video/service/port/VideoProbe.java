package kr.co.cudo.authoring.video.service.port;

import java.nio.file.Path;

/**
 * 영상 기술메타 추출 포트 — Phase 3 (RQ-SFR-07-02) + NIA export Phase 1.
 *
 * <p>ffprobe 바이너리 의존을 인터페이스로 격리한다. 서비스 단위 테스트는 stub 으로 주입하여
 * 바이너리 비의존으로 검증한다. 운영 구현은 {@code BrampVideoProbe}.
 *
 * <p>초기에는 해상도(width/height)만 추출했으나 NIA export 를 위해 codec/fps/bit_rate/
 * duration/filesize 를 1회 ffprobe 호출로 함께 추출하도록 확장했다. 기존 해상도 사용처는
 * {@link VideoMeta#dimensions()} 또는 width/height 접근으로 그대로 동작한다(하위호환).
 */
public interface VideoProbe {

    /** 영상 가로/세로 해상도 (px). */
    record Dimensions(int width, int height) {
    }

    /**
     * 영상 기술메타.
     *
     * <p>미상/파싱불가/미제공 필드는 {@code null}(원시 정수인 width/height 는 스트림 없음 시 0)
     * 로 표현한다 — "값 0"과 "미상"을 구분하기 위한 의도적 null 허용이다.
     *
     * @param width      가로 해상도(px). 비디오 스트림 없음 시 0.
     * @param height     세로 해상도(px). 비디오 스트림 없음 시 0.
     * @param codecName  코덱명(예: h264). 미상 시 null.
     * @param fps        초당 프레임 수. r_frame_rate 분수 계산. fps 는 양수만 유효하며,
     *                   그 외(분자·분모 중 하나라도 ≤0, 파싱불가, 미상)는 null(미상).
     * @param bitRate    비트레이트(bps). 스트림 값 우선, 없으면 format 폴백. 둘 다 없음 시 null.
     * @param durationMs 길이(ms). ffprobe 초 실수값 ×1000 반올림. 미상 시 null.
     * @param fileSize   파일 크기(byte). format size. 미상 시 null.
     */
    record VideoMeta(int width, int height, String codecName, Double fps,
                     Long bitRate, Long durationMs, Long fileSize) {

        /** 하위호환: 해상도만 필요한 기존 사용처를 위한 편의 접근. */
        public Dimensions dimensions() {
            return new Dimensions(width, height);
        }
    }

    /**
     * 영상 파일에서 기술메타를 추출한다.
     *
     * @param video 원본 영상 파일 경로
     * @return 기술메타. video stream 이 없으면 width/height 가 0, 나머지 필드가 null 로 반환될 수
     * 있으며, 그 검증은 호출자(서비스) 책임이다. 개별 필드 누락은 예외를 던지지 않고 null 로 둔다.
     */
    VideoMeta probe(Path video);
}
