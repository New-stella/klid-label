package kr.co.cudo.authoring.video.dto;

import java.util.List;

/**
 * 해상도 파생 산출물 <b>비식별 저장소 이관 백필</b> 결과 (E-ISSUE-21/41 후속 데이터 정정).
 *
 * <p>내부 파일 경로는 담지 않는다(CWE-209 정보 노출 방어) — 식별자와 건수·사유코드만 반환한다.
 *
 * @param targetCount   대상(확정 ACCEPTED 해상도 파생) 영상 수
 * @param migratedRawSns 이번 실행에서 실제 이관된 파생 RAW_SN
 * @param skippedRawSns 이미 이관 완료라 건너뛴 파생 RAW_SN(멱등 재실행)
 * @param failures      이관 실패 항목 — 해당 RAW_SN 은 <b>기존 경로를 유지</b>한다(재실행 가능)
 * @param unmatchedRawSns 감사에서 비식별 산출물로 인정되지 않은 프레임을 가졌으나 대상 조인에 잡히지 않은
 *                        RAW_SN (수동 확인 대상)
 * @param auditScannedFrames 감사에서 실제 판정한 프레임 행 수
 * @param auditViolations 감사 위반 집계 — 서빙({@code FrameSource})과 <b>동일한 판정기</b>로 판정한다(H-4)
 * @param auditTruncated <b>A-2</b> — 감사가 1회 실행 상한(자원 고갈 방지)에 걸려 <b>부분 스캔</b>으로
 *                       끝났는가. {@code true} 면 {@code auditViolations} 가 비어 있어도 "영향 없음"의
 *                       근거로 쓸 수 없다(상한 이후 구간은 판정되지 않았다). 사업 목표 규모(이미지 10만장
 *                       + 영상 5,000건)에서 상한 초과는 확정적이므로 이 플래그 없이는 증거가 조용히 잘린다.
 * @param unresolvedPresetRawSns <b>A-4</b> — 파생으로 발견됐으나 대응 {@code LS_DATA_AUG}(RESL_*, ACCEPTED)
 *                       행이 없어 <b>프리셋을 확정하지 못한</b> 파생 RAW_SN. 프레임 이관은 수행되지만 영상
 *                       파일 경로는 손대지 않는다(경로 규약상 프리셋이 필요) — 수동 확인 대상으로 노출한다.
 * @param viewExcludedRawSns {@code V_COMPLETED_FRAME} 게이트로 뷰에서 제외되는 영상 목록(G-2)
 * @param staleDeletedCount 이번 실행에서 <b>유예가 지나 실제 삭제</b>된 구 산출물 수
 * @param stalePendingCount <b>유예 대기 중</b>인 구 산출물 수 — 이관은 끝났지만 2노드 stale 캐시 보호를 위해
 *                          아직 지우지 않은(또는 DB 참조가 남은) 파일 수다. 0 이 아니어도 정상이며, 유예
 *                          ({@code authoring.resolution-backfill.stale-grace-minutes}) 경과 후 주기 스윕이
 *                          정리한다. 값이 줄지 않고 누적되면 스윕 잡 비활성/참조 잔존을 의심한다.
 */
public record ResolutionBackfillResponse(
        int targetCount,
        List<Long> migratedRawSns,
        List<Long> skippedRawSns,
        List<Failure> failures,
        List<Long> unmatchedRawSns,
        long auditScannedFrames,
        List<AuditViolation> auditViolations,
        boolean auditTruncated,
        List<Long> unresolvedPresetRawSns,
        List<ViewExclusion> viewExcludedRawSns,
        int staleDeletedCount,
        int stalePendingCount
) {

    /** 실패 1건 — 사유는 예외 클래스명 등 추상 코드만(경로·PII 미노출). */
    public record Failure(Long rawSn, String reasonCode) {
    }

    /**
     * 감사 위반 1건 — 영상 단위 집계.
     *
     * @param rawSn      영상 PK
     * @param verdict    판정 코드({@code StorageSubtreePolicy.Verdict}) — 경로 원문 미노출
     * @param frameCount 해당 사유로 비식별 산출물 판정에 실패한 프레임 수
     */
    public record AuditViolation(Long rawSn, String verdict, long frameCount) {
    }

    /**
     * 뷰 제외 1건 (G-2) — 원본 경로와 비식별 경로가 <b>동일</b>한 결함 프레임을 가진 영상.
     *
     * @param rawSn      영상 PK
     * @param frameCount 결함 프레임 수
     */
    public record ViewExclusion(Long rawSn, long frameCount) {
    }
}
