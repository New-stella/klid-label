package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.FrameContext;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.VideoExportContext;

import java.util.List;

/**
 * Phase 4 — 검수 승인 후 학습데이터 파일 산출에 필요한 로딩 산출물(불변 스냅샷).
 *
 * <p>{@code DatasetExportTxService.loadPreparation} 가 REQUIRES_NEW readonly 트랜잭션에서 DB 를
 * 재조회해 조립하고, {@code DatasetExportService} 오케스트레이터가 트랜잭션 밖에서 파일을 쓴다.
 * 엔티티는 스칼라 컬럼만 참조하므로(지연 연관 없음) detach 이후에도 안전하게 읽힌다.
 *
 * @param ctx              rawSn 단위 공통 컨텍스트(Phase 2 {@code NiaJsonBuilder#prepareContext} 산출)
 * @param frames           프레임 단위 입력(프레임 + 라벨)
 * @param contentHash      산출 시점 라벨 상태의 콘텐츠 해시(멱등 판정 키)
 * @param lastSucceededHash 직전 SUCCEEDED export 의 콘텐츠 해시(없으면 null) — 이 값과 같으면 멱등 skip
 */
public record ExportPreparation(
        VideoExportContext ctx,
        List<FrameContext> frames,
        String contentHash,
        String lastSucceededHash
) {

    /** 무수정 재승인 — 직전 성공 산출과 라벨 상태가 동일하면 재산출을 skip 한다. */
    public boolean isUnchangedFromLastSucceeded() {
        return contentHash != null && contentHash.equals(lastSucceededHash);
    }
}
