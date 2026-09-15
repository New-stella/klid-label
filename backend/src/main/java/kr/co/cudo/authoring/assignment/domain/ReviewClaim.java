package kr.co.cudo.authoring.assignment.domain;

import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * <b>검수 점유</b> — 지금 그 영상을 누가 보고 있는가.
 *
 * <p>점유는 저장된 값이 아니라 <b>작업 이력 원장을 읽어 조회 시점에 내리는 판정</b>이다. 전용 컬럼도
 * 별도 표도 두지 않으며, 「검수 시작」 이벤트 하나로 표현한다.
 *
 * <h3>판정 규칙 (단일 소유 — 다른 곳에 옮겨 적지 말 것)</h3>
 * 그 영상의 <b>마지막 점유 관련 이벤트</b>({@link #EVENT_TYPES})가
 * <ol>
 *   <li>「검수 시작」이고 <b>(= 그 뒤에 승인·반려가 없다)</b></li>
 *   <li>발생일시 + 유예가 아직 지나지 않았으면</li>
 * </ol>
 * 그 행위자가 점유 중이다. 둘 중 하나라도 어긋나면 점유가 없다.
 *
 * <h3>점유를 푸는 동작을 만들지 않는다</h3>
 * 승인·반려가 이미 자기 이벤트를 남기므로 조건 ①에 걸려 자연히 풀리고, 자리를 뜬 경우는 조건 ②의
 * 유예가 풀어 준다. 만료를 처리하는 배치도 두지 않는다 — 조회 시점 판정이라 그 전부가 불필요하고
 * 노드 간 시계 오차에도 관대하다.
 *
 * <h3>★점유는 잠금이 아니다</h3>
 * 만료가 있어 영구 잠금이 되지 않으며, <b>승인 시점의 낙관적 잠금이 실제 방어</b>다. 점유가 생겼다는
 * 이유로 그 잠금을 걷어내지 말 것 — 점유는 헛수고를 줄이고 잠금은 사고를 막는다. 둘은 서로를
 * 대체하지 않는다.
 *
 * @param rawDataId   점유된 영상
 * @param ownerUserNo 점유한 사람
 * @param startedAt   이 점유가 세워진 시각(마지막 「검수 시작」의 발생일시)
 * @param expiresAt   이 시각 <b>이후</b>부터 점유가 풀린다
 * @design ADR-067
 * @design ERD-014
 */
public record ReviewClaim(Long rawDataId, Long ownerUserNo, LocalDateTime startedAt, LocalDateTime expiresAt) {

    /**
     * 점유 판정이 읽어야 하는 이벤트 종류 — <b>시작과 두 종결을 함께</b> 본다.
     *
     * <p>종결(승인·반려)을 빼고 시작만 보면 「승인이 끝난 영상이 영원히 점유 중」으로 보인다. 반대로
     * 두 번 나눠 조회하면 그 사이에 승인이 끼어들어 판정이 어긋난다. 그래서 한 집합으로 묶어
     * <b>한 번의 조회</b>로 마지막 1건을 가져온다.
     */
    public static final List<String> EVENT_TYPES = List.of(
            LsTaskEventLog.EVENT_START_REVIEW,
            LsTaskEventLog.EVENT_APPROVE,
            LsTaskEventLog.EVENT_REJECT);

    /**
     * 마지막 점유 관련 이벤트 1건을 점유 판정으로 바꾼다.
     *
     * @param latest 그 영상의 마지막 {@link #EVENT_TYPES} 이벤트. 없으면 {@code null}
     * @param grace  유예 — <b>배포 설정값</b>이며 여기서 기본값을 정하지 않는다
     * @param now    판정 기준 시각. 호출부가 넘긴다(시험이 만료 전·후를 실제로 지나갈 수 있어야 한다)
     * @return 유효한 점유, 없으면 {@link Optional#empty()}
     */
    public static Optional<ReviewClaim> of(LsTaskEventLog latest, Duration grace, LocalDateTime now) {
        Objects.requireNonNull(grace, "grace");
        Objects.requireNonNull(now, "now");
        if (latest == null || !LsTaskEventLog.EVENT_START_REVIEW.equals(latest.getEventTypeCd())) {
            // 마지막이 승인·반려면 이미 끝난 영상이고, 아무 이벤트도 없으면 아직 아무도 열지 않았다.
            return Optional.empty();
        }
        LocalDateTime startedAt = latest.getOcrnDt();
        if (startedAt == null) {
            // 발생일시는 NOT NULL 이라 정상 경로에서는 비지 않는다. 판정 근거가 없으면 점유를 세우지
            // 않는다(fail-closed) — 만료를 계산할 수 없는 점유는 영구 잠금이 된다.
            return Optional.empty();
        }
        LocalDateTime expiresAt = startedAt.plus(grace);
        if (!now.isBefore(expiresAt)) {
            return Optional.empty();
        }
        return Optional.of(new ReviewClaim(latest.getRawDataId(), latest.getActorUserNo(), startedAt, expiresAt));
    }

    /** 이 점유의 주인이 {@code userNo} 인가. {@code null} 은 거짓(fail-closed). */
    public boolean ownedBy(Long userNo) {
        return userNo != null && userNo.equals(ownerUserNo);
    }
}
