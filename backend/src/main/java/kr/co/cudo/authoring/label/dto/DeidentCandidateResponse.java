package kr.co.cudo.authoring.label.dto;

import kr.co.cudo.authoring.label.service.DeidentArtifactCandidateFinder;

import java.time.LocalDateTime;

/**
 * R3 — 비식별 신고 해소 시 고를 수 있는 <b>재비식별 산출물 후보</b> 1건.
 *
 * <h3>내부 저장 경로를 절대 담지 않는다 (CWE-209)</h3>
 * <p>후보 식별은 <b>파일명(basename)</b> 뿐이다. 디렉터리·마운트 구조는 응답에 나가지 않는다 —
 * 해소 거부 메시지가 내부 경로를 감추는 것({@code DeidentReportService.deidentNotVerified})과 같은 축이며,
 * 이 목록은 WORKER 도 조회할 수 있으므로 저장소 구조가 노출되면 안 된다.
 *
 * <p>선택 후 해소 요청도 이 {@code fileName} 만 보낸다 — 서버는 그 값으로 경로를 조립하지 않고
 * <b>같은 열거 결과와 대조</b>해 수락 여부를 정한다(CWE-22, {@link DeidentArtifactCandidateFinder}).
 *
 * @param fileName   파일명(basename) — 해소 요청 시 그대로 보낸다
 * @param sizeBytes  파일 크기(바이트) — 어느 것이 새 산출물인지 사람이 판단할 근거
 * @param modifiedAt 파일 수정 시각 — 위와 동일
 * @param eligible   해소에 쓸 수 있는가(무결성 + 신고 이후 생성 조건 통과). false 면 서버가 409 로 거부한다
 * @param current    현재 원장({@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM})이 가리키는 파일인가
 */
public record DeidentCandidateResponse(
        String fileName,
        long sizeBytes,
        LocalDateTime modifiedAt,
        boolean eligible,
        boolean current
) {
    /** 내부 후보(실경로 보유) → 응답 DTO. <b>경로 필드는 의도적으로 버린다</b>. */
    public static DeidentCandidateResponse from(DeidentArtifactCandidateFinder.Candidate c) {
        return new DeidentCandidateResponse(
                c.fileName(), c.sizeBytes(), c.modifiedAt(), c.eligible(), c.current());
    }
}
