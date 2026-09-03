package kr.co.cudo.authoring.video.dto;

/**
 * 마킹 이관 영상 1건 적재의 결과 — <b>적재됨</b>과 <b>중복이라 건너뜀</b>을 구분해 돌려준다.
 *
 * <h3>왜 중복만 값으로 구분하는가</h3>
 * <p>영상 파일 이름에서 얻은 식별자가 이미 쓰이고 있으면 <b>그 항목만 건너뛰고 나머지는 이어 간다</b>
 * (AC-1033). 조용히 덮어쓰면 검수 중이거나 승인된 내용이 사라지므로 덮어쓰기 경로는 두지 않는다.
 * 건너뛰는 판단은 호출부(이관)가 건별 사유를 기록하며 내려야 하는데, 이것이 <b>다른 실패와 섞이면</b>
 * "이미 들어와 있어서 넘어감"이 "적재 실패"로 집계된다. 그래서 중복은 예외가 아니라 <b>정상 반환값</b>
 * 이고, 그 밖의 실패는 종전대로 예외로 올라간다.
 *
 * <p>중복일 때 {@link #rawSn} 은 <b>이미 있던 영상의 식별자</b>다. 호출부가 그 영상을 가리켜 사유를
 * 남길 수 있게 함께 돌려준다(무엇과 부딪혔는지 모르면 사람이 되짚을 수 없다).
 *
 * @param rawSn             적재됐거나 이미 있던 영상의 식별자 — 어느 경우에도 {@code null} 이 아니다
 * @param duplicateClipId   이미 쓰이고 있는 식별자라 새로 적재하지 않았으면 {@code true}
 * @design DFEAT-060
 * @design AC-1033
 */
public record MarkingImportIngestResult(long rawSn, boolean duplicateClipId) {

    /** 새로 적재됐다. */
    public static MarkingImportIngestResult ingested(long rawSn) {
        return new MarkingImportIngestResult(rawSn, false);
    }

    /** 같은 식별자의 영상이 이미 있어 건너뛰었다 — 기존 내용은 그대로다. */
    public static MarkingImportIngestResult duplicate(long existingRawSn) {
        return new MarkingImportIngestResult(existingRawSn, true);
    }
}
