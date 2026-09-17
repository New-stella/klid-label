package kr.co.cudo.authoring.review.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 일괄 승인 건수 상한의 <b>단일 소유자</b>.
 *
 * <p>이 값을 읽는 곳이 둘이다 — 실제로 강제하는 일괄 승인 창구와, 화면이 미리 제한하도록 값을 내려
 * 주는 검수 목록 조회. 양쪽이 각자 설정을 읽으면 <b>강제하는 값과 안내하는 값이 갈릴 수 있다</b>.
 * 그래서 한 곳이 읽고 둘이 주입받는다.
 *
 * <h3>왜 상한이 필요한가</h3>
 * 승인 한 건이 라벨 전량 스냅샷 동결·메타 동결·산출 폴더 전량 재생성(이미지 두 벌)·외부 통지를 연쇄로
 * 일으킨다. 한 요청에 담기는 건수를 열어 두면 그 연쇄가 한꺼번에 쏟아진다(CWE-770).
 *
 * @design API-250
 * @design API-008
 * @design AC-1114
 */
@Component
public class ReviewBatchApprovePolicy {

    /** 상한 설정 키 — 배포 설정값이며 코드·화면에 숫자를 박지 않는다. */
    public static final String LIMIT_PROPERTY = "authoring.review.batch-approve.limit";

    private final int limit;

    public ReviewBatchApprovePolicy(@Value("${" + LIMIT_PROPERTY + ":20}") int limit) {
        if (limit <= 0) {
            // fail-fast — 0·음수면 일괄 승인이 항상 400 이 되어 기능이 조용히 사라진다.
            throw new IllegalArgumentException(LIMIT_PROPERTY + " 는 1 이상이어야 합니다: " + limit);
        }
        this.limit = limit;
    }

    /** 한 요청에 담을 수 있는 최대 건수. */
    public int limit() {
        return limit;
    }
}
