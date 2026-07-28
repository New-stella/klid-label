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
 * @param ctx             rawSn 단위 공통 컨텍스트(Phase 2 {@code NiaJsonBuilder#prepareContext} 산출)
 * @param frames          프레임 단위 입력(프레임 + 라벨)
 * @param contentHash     산출 시점 라벨 상태의 콘텐츠 해시(멱등 판정 키)
 * @param lastExportedHash 직전 SUCCEEDED/PARTIAL(멱등 baseline) export 의 콘텐츠 해시(없으면 null) —
 *                        <b>재동결 경로({@code onReExport}, force=false)에서만</b> 이 값과 같으면 멱등 skip.
 *                        승인 경로({@code onReviewApproved}, force=true, R6)는 동일 해시여도 skip 없이 전량
 *                        재생성한다. PARTIAL 을 포함해, 이미지가 지속 부재한 영상의 무수정 재동결이 매번 새
 *                        버전을 채번하며 디스크를 무한 소모하는 회귀를 막는다.
 * @param rawFilePathNm   원본 영상 경로({@code LS_DATA_RAW.RAW_FILE_PATH_NM}) — co-locate 산출 base
 *                        ({@code dirname(원본)/{rawSn}/}) 도출 원천. 값이 없거나 허용 마운트 루트 밖이면
 *                        오케스트레이터가 <b>기본 루트로 폴백하지 않고</b> export 를 FAILED 로 마감한다(S1).
 */
public record ExportPreparation(
        VideoExportContext ctx,
        List<FrameContext> frames,
        String contentHash,
        String lastExportedHash,
        String rawFilePathNm
) {

    /**
     * 재동결 경로(force=false) — 직전 성공/부분 산출(멱등 baseline)과 라벨 상태가 동일하면 재산출을 skip 한다.
     * 직전이 PARTIAL 이어도 contentHash 가 같으면 재산출 결과가 동일한 PARTIAL 이라 무의미하므로 skip 한다.
     * 승인 경로(force=true, R6)는 이 판정을 무시하고 항상 전량 재생성한다({@code DatasetExportService.export}).
     */
    public boolean isUnchangedFromLastExport() {
        return contentHash != null && contentHash.equals(lastExportedHash);
    }
}
