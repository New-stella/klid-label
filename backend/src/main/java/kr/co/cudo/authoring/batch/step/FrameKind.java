package kr.co.cudo.authoring.batch.step;

/**
 * 프레임 출력 종류별 서브세그먼트 (스킴 A).
 * <p>
 * 출력 디렉토리는 {@code {base}/frames/{kind}/{rawSn}} 구조다. RAW={@code frames/raw},
 * DEID={@code frames/deid} 로 분기하여, STORAGE_RAW_PATH 와 STORAGE_DEIDENTIFIED_PATH 가
 * 동일 경로로 설정돼도 원본/비식별 프레임이 서로 덮어쓰지 않도록 한다.
 * <p>
 * {@link FfmpegFrameExtractor}(초기 비식별·원본 추출)와 {@link DeidentFrameAttacher}(재비식별
 * 프레임 attach)가 이 enum 을 공유하여, 비식별 프레임 경로 스킴({@code frames/deid})을 한 곳에서
 * 정의한다 — 매직 문자열 {@code "deid"} 중복을 제거한다.
 */
public enum FrameKind {
    RAW("raw"),
    DEID("deid");

    private final String segment;

    FrameKind(String segment) {
        this.segment = segment;
    }

    /** {@code frames/{segment}} 의 종류 세그먼트 (raw / deid). */
    public String getSegment() {
        return segment;
    }
}
