package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 마킹 산출물 폴더 검사 결과 (API-216).
 *
 * <h3>적재 가능 여부의 판정은 {@code importable} 하나가 한다</h3>
 * <p>{@code warnings} 에는 <b>적재를 막는 것과 막지 않는 것이 섞여 있다</b>. 경고가 있는지로 판정하면
 * 「바로가기를 건너뛰었다」 같은 알림 하나 때문에 정상 항목이 적재 불가로 보인다.
 *
 * <h3>상한에 걸린 사실을 조용히 넘기지 않는다</h3>
 * <p>{@code truncated} 가 참이면 이 결과가 <b>폴더 전체가 아니다</b>. 알리지 않으면 일부만 들어온
 * 것이 전부로 보이고, 사람은 나머지가 원래 없었다고 판단한다.
 *
 * @param items               마킹 문서 하나를 한 항목으로 하는 검사 결과 목록
 * @param scannedFileCount    훑은 파일 수
 * @param matchedCount        짝을 찾은 항목 수
 * @param importableCount     지금 상태로 적재할 수 있는 항목 수
 * @param unmatchedVideoCount 어느 마킹 문서도 가리키지 않은 영상 수
 * @param unmatchedVideoNames 그 영상의 이름 — 수가 많으면 상한까지만 담기며 전체 수는 위에서 알린다
 * @param truncated           상한에 걸려 일부만 훑었는지 여부
 * @param warnings            묶음 전체에 대해 알려야 하는 사항
 * @design DOMAIN-017
 * @design API-216
 * @design AC-1032
 * @design AC-1033
 */
@Schema(description = "마킹 산출물 폴더 검사 결과")
public record MarkingImportScanResponse(
        List<Item> items,
        int scannedFileCount,
        int matchedCount,
        int importableCount,
        int unmatchedVideoCount,
        List<String> unmatchedVideoNames,
        boolean truncated,
        List<Warning> warnings) {

    /**
     * 검사 결과 한 항목.
     *
     * @param markingFileName 짝을 이루는 마킹 문서의 이름
     * @param clipId          영상 파일 이름에서 확장자를 뗀 값 — 적재 시 영상 식별자로 쓰인다.
     *                        만들 수 없으면 {@code null}
     * @param videoFileName   마킹 문서가 가리키는 영상 파일의 이름 — 파일 이름만 남도록 정제한 값
     * @param videoFound      훑은 범위 안에서 그 이름의 영상을 찾았는지 여부. 같은 이름이 둘 이상이면
     *                        <b>찾지 못한 것으로</b> 다룬다 — 짐작해 하나를 고르지 않는다
     * @param segmentCount    마킹 문서가 담은 이벤트 구간 수
     * @param markCount       구간을 합쳐 정리한 시점 수(정렬·중복 제거 후)
     * @param declaredFps     마킹 문서의 프레임 번호와 시각으로 역산한 프레임 재생 속도
     * @param probedFps       짝지은 영상 파일에서 실제로 읽은 프레임 재생 속도 —
     *                        <b>적재 시 마킹에 고정되는 값은 이쪽이다</b>
     * @param videoFrameCount 짝지은 영상 파일의 전체 프레임 수
     * @param importable      지금 상태로 적재할 수 있는지 여부 — <b>판정은 이 값 하나가 한다</b>
     * @param warnings        이 항목에 대해 알려야 하는 사항
     */
    @Schema(description = "검사 결과 한 항목")
    public record Item(
            String markingFileName,
            String clipId,
            String videoFileName,
            boolean videoFound,
            int segmentCount,
            int markCount,
            Double declaredFps,
            Double probedFps,
            Integer videoFrameCount,
            boolean importable,
            List<Warning> warnings) {
    }

    /**
     * 알려야 하는 사항 한 건.
     *
     * @param code    알림 종류 — 화면이 문구가 아니라 이 값으로 분기한다
     * @param message 사람이 읽는 설명 — 내부 경로·원문을 담지 않는다(CWE-209)
     */
    @Schema(description = "알려야 하는 사항")
    public record Warning(String code, String message) {
    }
}
