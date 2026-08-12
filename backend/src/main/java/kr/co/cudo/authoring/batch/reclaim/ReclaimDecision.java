package kr.co.cudo.authoring.batch.reclaim;

import kr.co.cudo.authoring.batch.status.ReprocessClaimOrigin;

/**
 * 고착 후보 1건에 대한 <b>회수 판정 결과</b> — "회수한다 / 아직 살아 있다 / 판정 불가로 보류한다".
 *
 * <h3>왜 {@code Optional<ReprocessClaimOrigin>} 이 아닌가</h3>
 * <p>빈 값 하나로는 <b>「아직 살아 있다」</b>(정상 — 아무 조치도 필요 없다)와 <b>「판정 불가라 보류했다」</b>
 * (사람이 봐야 한다 — 애플리케이션이 스스로 풀 수 없다)가 구분되지 않는다. 두 경우에 같은 로그를 남기면
 * 정상 실행 중인 영상이 tick 마다 경고를 찍어 <b>진짜 보류가 그 잡음에 묻힌다</b>(이 리포의 "WARN 은 배포
 * 로그에 묻힌다" 관례). 판정은 그래서 세 갈래로 돌려주고, 로그의 수위와 집계는 스윕이 정한다.
 *
 * @param outcome 판정 갈래
 * @param origin  {@link Outcome#RECLAIM} 일 때만 값이 있다(복구 목표 축)
 * @param reason  {@link Outcome#WITHHELD} 일 때 보류 사유. 고정 상수만 담는다(사용자 입력·경로·PII 비포함)
 */
public record ReclaimDecision(Outcome outcome, ReprocessClaimOrigin origin, WithholdReason reason) {

    /** 판정 갈래. */
    public enum Outcome {
        /** 회수 대상 — 상태를 {@link #origin} 축으로 되돌린다. */
        RECLAIM,
        /** 아직 살아 있다(진행 중이거나 방금 선점됐다) — 정상이므로 아무 기록도 남기지 않는다. */
        ALIVE,
        /** 판정 불가 — 회수하지 않고 사람이 보게 남긴다(fail-closed). */
        WITHHELD
    }

    /**
     * 보류 사유 — 운영자가 "왜 이 영상이 회수되지 않는가"를 알 수 있어야 한다.
     *
     * <p>모두 <b>고정 상수</b>다. 사유 문자열에 rawSn 외의 가변값을 섞지 않는다(CWE-117/532).
     */
    public enum WithholdReason {
        /** 선점 표식 자체가 없다 — 선점 직전 상태를 기록하지 않는 다른 진입 경로로 고착됐다. */
        NO_CLAIM_MARKER("선점 출발 상태 미기록"),
        /** 표식은 열려 있는데 저장값이 해석되지 않는다(손상·미지 값). */
        UNREADABLE_ORIGIN("선점 출발 상태 해석 불가"),
        /** 표식이 열린 뒤 배치가 종결까지 갔다 — 이미 끝난 옛 에피소드의 잔재 표식이다. */
        STALE_EPISODE("종결된 옛 에피소드의 잔재 표식");

        private final String description;

        WithholdReason(String description) {
            this.description = description;
        }

        /** 로그에 찍는 사람 읽는 사유(고정 상수). */
        public String description() {
            return description;
        }
    }

    /** 아직 살아 있음 — 회수 대상이 아니며 기록도 남기지 않는다. */
    public static ReclaimDecision alive() {
        return new ReclaimDecision(Outcome.ALIVE, null, null);
    }

    /** 판정 불가로 보류 — 스윕이 tick 요약으로 집계한다. */
    public static ReclaimDecision withheld(WithholdReason reason) {
        return new ReclaimDecision(Outcome.WITHHELD, null, reason);
    }

    /** 회수 대상 — 복구 목표 축을 동반한다. */
    public static ReclaimDecision reclaim(ReprocessClaimOrigin origin) {
        return new ReclaimDecision(Outcome.RECLAIM, origin, null);
    }

    public boolean isReclaimable() {
        return outcome == Outcome.RECLAIM && origin != null;
    }

    public boolean isWithheld() {
        return outcome == Outcome.WITHHELD;
    }
}
