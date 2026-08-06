package kr.co.cudo.authoring.upload.service;

import java.nio.file.Path;

/**
 * 내부 업로드(관리 화면 TUS) 전용 미디어 측정 포트.
 *
 * <p>업로드 완료 시점에 영상 파일을 <b>1회</b> 측정해 인입 원장({@code LS_DATA_INGEST}) 의
 * 기술메타 컬럼을 채우는 데 필요한 값을 얻는다. 측정값의 해석·파생·검증은 이 포트가 하지 않고
 * {@link InternalUploadMetaResolver} 가 단독으로 판정한다(측정과 정책의 분리).
 *
 * <p><b>왜 {@code video.service.port.VideoProbe} 를 재사용하지 않는가</b> — 소비처가 다르다.
 * {@code VideoProbe} 는 {@code LS_DATA_META} 의 {@code video.*} 축(비트레이트·파일크기)을 채우는
 * 포트이고 {@code VideoDurationResolver}·{@code AsyncVideoMetaRunner} 등 13개 호출부가
 * {@code VideoMeta} 레코드 시그니처에 묶여 있다. 이번에 필요한 {@code nb_frames}·
 * {@code display_aspect_ratio} 를 그 레코드에 추가하면 그 호출부가 전부 깨진다(레코드 생성자
 * 시그니처 변경). 같은 이유로 {@code TusUploadService.DurationProbe} 도 건드리지 않는다 —
 * {@code DevAutolabelTestService} 가 함께 쓰는 별개 소비자다.
 *
 * <p>운영 구현은 {@link UploadMediaProbeFfprobe}. 단위 테스트는 stub 을 직접 주입해
 * ffprobe 바이너리 의존을 격리한다.
 */
public interface UploadMediaProbe {

    /**
     * 업로드 영상의 원시 측정값.
     *
     * <p>미상/파싱불가/미제공 필드는 {@code null} 로 둔다(원시 정수인 width/height 는 비디오
     * 스트림 없음 시 {@code 0}) — "값 0"과 "미상"을 구분하기 위한 의도적 null 허용이며
     * {@code VideoProbe.VideoMeta} 와 동일 규약이다. <b>여기서는 아무것도 거르지 않는다</b>;
     * DDL 길이·범위 검증은 전적으로 {@link InternalUploadMetaResolver} 책임이다.
     *
     * @param width               가로 해상도(px). 비디오 스트림 없음 시 0.
     * @param height              세로 해상도(px). 비디오 스트림 없음 시 0.
     * @param codecName           코덱명(예: h264). 미상 시 null.
     * @param fps                 초당 프레임 수. {@code r_frame_rate} 분수 계산 결과이며 <b>유한
     *                            양수만</b> 유효하다(그 외는 null). 구현은 {@code NaN}/{@code Infinity}
     *                            를 반출하지 않는다 — 컨테이너가 {@code NaN/1} 을 신고해도 null 이다.
     *                            {@code Double.parseDouble} 이 그 문자열을 정상 파싱하고 {@code NaN} 은
     *                            모든 부호 비교를 통과하므로, 구현은 부호가 아니라
     *                            {@link Double#isFinite} 로 거른다(CWE-20/681).
     * @param durationMs          길이(ms). {@code format.duration} 우선, 없으면 스트림 값. <b>양수만</b>
     *                            유효하며(0·음수·비유한·long 포화 범위는 null) 반올림 결과가 0 이 되는
     *                            미세값도 null 이다.
     * @param nbFrames            컨테이너가 신고한 프레임 수. 미제공 컨테이너에서는 null.
     * @param displayAspectRatio  표시 종횡비 원문(예: {@code 16:9}). 미상 시 null.
     */
    record MediaMeta(int width, int height, String codecName, Double fps,
                     Long durationMs, Long nbFrames, String displayAspectRatio) {
    }

    /**
     * 영상 파일에서 업로드 인입용 기술메타를 측정한다.
     *
     * @param filePath 측정 대상 영상 파일 경로
     * @return 원시 측정값. 개별 필드 누락은 예외를 던지지 않고 null(width/height 는 0)로 둔다.
     * @req R1
     */
    MediaMeta probe(Path filePath);
}
