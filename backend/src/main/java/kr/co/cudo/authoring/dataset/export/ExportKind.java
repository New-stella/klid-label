package kr.co.cudo.authoring.dataset.export;

/**
 * 학습데이터 산출 종류 — 폴더 경로 세그먼트({@code orgnl}/{@code deid})로 매핑된다.
 *
 * <p>최종 폴더 구조: {@code {labeling_root}/{RAW_SN}/v{n}/{orgnl|deid}/} — 버전이 종류의 상위.
 */
public enum ExportKind {

    /** 원본 산출물. */
    ORIGINAL("orgnl"),
    /** 비식별 산출물. */
    DEIDENTIFIED("deid");

    private final String segment;

    ExportKind(String segment) {
        this.segment = segment;
    }

    /** 폴더 경로 세그먼트({@code orgnl}/{@code deid}). */
    public String segment() {
        return segment;
    }
}
