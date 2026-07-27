package kr.co.cudo.authoring.dataset.export;

/**
 * 검수 승인 산출물(export) 프레임 파일명 규칙 <b>단일 지점</b>.
 *
 * <h3>규칙 (사용자 확정)</h3>
 * <ul>
 *   <li>이미지 — {@code {FRM_NO 4자리 zero-pad}.jpg} (예: {@code 0000.jpg}, {@code 0007.jpg}, {@code 0338.jpg})</li>
 *   <li>JSON  — {@code {FRM_NO 4자리 zero-pad}.json}</li>
 * </ul>
 *
 * <p>{@code String.format("%04d", frmNo)} — <b>최소</b> 폭 4의 0 패딩이라 10000 이상은 잘리지 않고
 * 자연 확장된다({@code 10000.jpg}). 상한을 코드에 박지 않는다.
 *
 * <p><b>파일명 기준 값은 {@code LS_DATA_SRC.FRM_NO}</b>(추출 순번, <b>0-base</b>)다. DB 의 {@code FRM_NO}
 * 와 그대로 일치해야 하므로 1-based 로 재해석하지 말 것.
 *
 * <p><b>export JSON 의 {@code frame_num} 과는 서로 다른 값이다(A-6)</b> — {@code frame_num} 은
 * {@code VDO_FRM_NO}(실제 영상 내 디코더 프레임 위치)이고, 파일명은 {@code FRM_NO}(추출 순번)이다.
 * 예: 30프레임 간격으로 뽑은 두 번째 프레임 → 파일명 {@code 0001.jpg}, JSON {@code frame_num=30}.
 * 관제가 참조하는 계약이므로 "파일명 = frame_num" 으로 되돌리지 말 것(위치 정보가 소실된다).
 *
 * <p><b>이 클래스가 존재하는 이유</b>: 관제 수정 통지({@code changed_items})가 싣는 파일명과 export
 * writer 가 실제로 쓰는 파일명이 어긋나면 관제 워커가 존재하지 않는 파일을 픽업한다. 규칙을 여기 한
 * 곳에만 두고 양쪽이 참조한다. (구 {@code frame-{n}.jpg} 접두사 형식은 폐기)
 */
public final class ExportFileNaming {

    /** zero-pad 최소 폭 — 초과 자릿수는 절단하지 않고 확장된다. */
    private static final String FRAME_NO_FORMAT = "%04d";

    private ExportFileNaming() {
    }

    /**
     * FRM_NO → 파일명 stem (예: 0 → {@code "0000"}, 10000 → {@code "10000"}).
     *
     * <p><b>음수는 fail-closed</b>: {@code String.format("%04d", -1)} 은 {@code "-001"} 을 만들어
     * 4자리 규칙이 깨지고, export writer 와 통지가 서로 다른 이름을 계산할 여지를 남긴다. FRM_NO 는
     * 0-base 추출 순번이라 음수가 나올 수 없으므로, 발생 시 조용히 이상한 파일명을 만들지 않고 즉시 실패한다.
     *
     * @throws IllegalArgumentException {@code frameNo < 0}
     */
    public static String frameStem(long frameNo) {
        if (frameNo < 0) {
            throw new IllegalArgumentException("FRM_NO 는 음수일 수 없습니다: " + frameNo);
        }
        return String.format(FRAME_NO_FORMAT, frameNo);
    }

    /** FRM_NO → 프레임 이미지 파일명. */
    public static String imageFileName(long frameNo) {
        return frameStem(frameNo) + ".jpg";
    }

    /** FRM_NO → 프레임 라벨/메타 JSON 파일명. */
    public static String jsonFileName(long frameNo) {
        return frameStem(frameNo) + ".json";
    }
}
