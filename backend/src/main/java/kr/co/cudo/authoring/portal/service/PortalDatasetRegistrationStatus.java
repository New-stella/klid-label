package kr.co.cudo.authoring.portal.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import kr.co.cudo.authoring.portal.dto.PortalDatasetRegistrationState;

import java.time.Duration;
import java.time.Instant;

/**
 * 해제본 옆에 놓이는 <b>등록 상태 표식</b>({@code registration.json}) — 원장 표가 아니다(ADR-068).
 *
 * <p>소재 조달 상태를 파일로 표현하는 방식과 같다. 표식과 원장이 어긋날 수 있으나 등록이 클립 식별자
 * 기준 멱등이라 <b>다시 시작하는 것으로 회복</b>한다.
 *
 * <p>⚠ 경로·파일명을 담지 않는다(CWE-209).
 *
 * @param state            등록 상태
 * @param failureReason    {@link PortalDatasetRegistrationState#FAILED} 일 때만 non-null
 * @param registeredVideos 이번 회차에 새로 등록했거나 이미 등록돼 있던 영상 수
 * @param skippedVideos    비식별 이미지 폴더가 없어 등록하지 않은 영상 수
 * @param updatedAt        표식을 마지막으로 쓴 시각 — 진행 중 표식의 오래됨 판정 기준
 * @design ADR-068
 * @design API-253
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortalDatasetRegistrationStatus(
        PortalDatasetRegistrationState state,
        PortalDatasetRegistrationFailureReason failureReason,
        int registeredVideos,
        int skippedVideos,
        Instant updatedAt) {

    public static PortalDatasetRegistrationStatus inProgress(int registered, int skipped, Instant at) {
        return new PortalDatasetRegistrationStatus(
                PortalDatasetRegistrationState.IN_PROGRESS, null, registered, skipped, at);
    }

    public static PortalDatasetRegistrationStatus done(int registered, int skipped, Instant at) {
        return new PortalDatasetRegistrationStatus(
                PortalDatasetRegistrationState.DONE, null, registered, skipped, at);
    }

    public static PortalDatasetRegistrationStatus failed(PortalDatasetRegistrationFailureReason reason,
                                                         int registered, int skipped, Instant at) {
        return new PortalDatasetRegistrationStatus(
                PortalDatasetRegistrationState.FAILED, reason, registered, skipped, at);
    }

    /**
     * 진행 중 표식이 <b>기준보다 오래됐는가</b>. 진행 중이 아니거나 시각이 없으면 — 시각이 없으면 언제
     * 썼는지 모르므로 오래된 것으로 본다(회복 쪽으로 기운다. 등록이 멱등이라 안전하다).
     */
    public boolean isStaleInProgress(Instant now, Duration threshold) {
        if (state != PortalDatasetRegistrationState.IN_PROGRESS) {
            return false;
        }
        return updatedAt == null || updatedAt.plus(threshold).isBefore(now);
    }
}
