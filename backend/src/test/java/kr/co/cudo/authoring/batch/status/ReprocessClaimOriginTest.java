package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선점 출발 축 ↔ 복구 목표 매핑의 <b>단일 원천</b> 고정.
 *
 * <p>여기가 틀리면 회수가 데이터를 지운다 — 완주 영상을 {@code FAILED} 로 되돌리면 전체 재기동
 * (실패 영상 전용) 경로가 열리고, 그 경로는 파이프라인을 통째로 순회해 사람이 손댄 보간 라벨을 전량
 * 삭제·재생성한다(삭제 이력도 승인 스냅샷도 없어 복구 지점 0).
 */
class ReprocessClaimOriginTest {

    @Test
    @DisplayName("★완주_출발은_COMPLETED와_ASSIGNED로_복구된다_FAILED로_강등되지_않는다")
    void completedOriginRestoresToCompletedAndAssigned() {
        assertThat(ReprocessClaimOrigin.COMPLETED.stageStatus())
                .isEqualTo(LsDataRaw.DATA_STTS_COMPLETED)
                .isNotEqualTo(LsDataRaw.DATA_STTS_FAILED);
        // 작업 상태는 완주 성공 경로(markRawDataCompleted)와 같은 값이어야 사람이 이어서 검수 제출을 한다.
        assertThat(ReprocessClaimOrigin.COMPLETED.workStatus())
                .isEqualTo(LsRawDataStatus.STTS_ASSIGNED)
                .isNotEqualTo(LsRawDataStatus.STTS_FAILED);
    }

    @Test
    @DisplayName("실패_출발은_두_컬럼_모두_FAILED로_복구된다")
    void failedOriginRestoresBothColumnsToFailed() {
        assertThat(ReprocessClaimOrigin.FAILED.stageStatus()).isEqualTo(LsDataRaw.DATA_STTS_FAILED);
        assertThat(ReprocessClaimOrigin.FAILED.workStatus()).isEqualTo(LsRawDataStatus.STTS_FAILED);
    }

    @Test
    @DisplayName("선점_직전_배치단계_코드로_출발축을_고른다")
    void resolvesAxisFromStageStatusCode() {
        assertThat(ReprocessClaimOrigin.fromStageStatus(LsDataRaw.DATA_STTS_FAILED))
                .contains(ReprocessClaimOrigin.FAILED);
        assertThat(ReprocessClaimOrigin.fromStageStatus(LsDataRaw.DATA_STTS_COMPLETED))
                .contains(ReprocessClaimOrigin.COMPLETED);
    }

    @Test
    @DisplayName("★모르는_출발상태는_비어있다_추측해서_되돌리지_않는다")
    void unknownOriginIsEmptyNotGuessed() {
        // 회수는 "알 수 없으면 하지 않는다" 가 계약이다. 예외를 던지면 스윕 스레드가 죽고,
        // 임의 기본값을 고르면 완주 영상이 실패로 강등될 수 있다.
        assertThat(ReprocessClaimOrigin.fromStageStatus(LsDataRaw.DATA_STTS_PROCESSING)).isEmpty();
        assertThat(ReprocessClaimOrigin.fromStageStatus(LsDataRaw.DATA_STTS_MARKING_READY)).isEmpty();
        assertThat(ReprocessClaimOrigin.fromStageStatus(null)).isEmpty();

        assertThat(ReprocessClaimOrigin.parse(null)).isEmpty();
        assertThat(ReprocessClaimOrigin.parse("")).isEmpty();
        assertThat(ReprocessClaimOrigin.parse("   ")).isEmpty();
        assertThat(ReprocessClaimOrigin.parse("MARKING_READY")).isEmpty();
        assertThat(ReprocessClaimOrigin.parse("completed")).isEmpty(); // 대소문자 관대 해석도 하지 않는다
    }

    @Test
    @DisplayName("표식에_적재한_문자열은_그대로_되읽힌다_왕복")
    void roundTripsThroughStoredString() {
        for (ReprocessClaimOrigin origin : ReprocessClaimOrigin.values()) {
            assertThat(ReprocessClaimOrigin.parse(origin.name())).contains(origin);
        }
    }
}
