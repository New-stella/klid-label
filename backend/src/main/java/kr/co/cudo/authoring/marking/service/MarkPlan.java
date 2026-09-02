package kr.co.cudo.authoring.marking.service;

import kr.co.cudo.authoring.marking.dto.MarkItem;

import java.util.List;

/**
 * 실제로 저장될 마킹 지점과 <b>잘리기 전 지점 수</b>.
 *
 * <p>지점 수 상한은 채널마다 다르다 — 관제 채널에는 없고(요청 본문 원소 상한이 유일한 방어선),
 * 포털 채널에는 <b>추출 장수 상한</b>이 있어 넘으면 거부가 아니라 자른다. 자른 사실이 응답에
 * 드러나야 하므로 잘리기 전 수를 함께 들고 다닌다 — 저장된 수만 돌려주면 소비자는 그 수가 자기가
 * 보낸 수인지 잘린 수인지 구분할 수 없다.
 *
 * @param marks          실제로 저장될 지점(잘린 뒤)
 * @param requestedCount 잘리기 전에 요청·산출된 지점 수
 * @design API-240
 */
public record MarkPlan(List<MarkItem> marks, int requestedCount) {

    /** 자르지 않는 채널용 — 요청 수와 저장 수가 같다. */
    public static MarkPlan unchanged(List<MarkItem> marks) {
        return new MarkPlan(marks, marks.size());
    }

    /** 상한에 걸려 지점이 잘렸는가. */
    public boolean truncated() {
        return requestedCount > marks.size();
    }
}
