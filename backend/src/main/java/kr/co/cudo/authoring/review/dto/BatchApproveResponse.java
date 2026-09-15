package kr.co.cudo.authoring.review.dto;

import java.util.List;

/**
 * 일괄 승인 결과 — <b>건별</b>로 되는 것만 처리하고 안 된 건은 사유와 함께 돌려준다.
 *
 * <p>한 건도 승인되지 못해도 응답 자체는 성공이다 — 요청은 받아들여졌고 판정은 이 목록으로 한다.
 * 요청 전체가 거부되는 경우(빈 목록·건수 상한 초과)는 이 응답이 아니라 오류로 나가며 그때는 한 건도
 * 처리되지 않는다.
 *
 * <p>이 응답은 각 건의 승인이 <b>받아들여졌다</b>는 뜻이지 산출물 생성과 외부 통지가 끝났다는 뜻이
 * 아니다. 그 연쇄는 뒤에서 비동기로 이어진다 — 끝나기를 기다리면 건수만큼 시간이 누적돼 요청이 먼저
 * 끊기고 그동안 요청 스레드가 묶인다.
 *
 * @design API-250
 */
public record BatchApproveResponse(
        int successCount,
        int failureCount,
        List<Item> results
) {

    /**
     * 요청한 식별자마다 한 항목. 요청 순서와 무관하게 <b>전건</b>이 담긴다.
     *
     * @param errorCode 실패 사유 코드(성공이면 null). ★화면은 <b>이 값</b>으로 사유를 가르고
     *                  메시지 문자열로 분기하지 않는다 — 문구는 바뀔 수 있다
     * @param reason    사람이 읽는 실패 사유(성공이면 null)
     */
    public record Item(Long videoId, boolean success, String errorCode, String reason) {

        public static Item succeeded(Long videoId) {
            return new Item(videoId, true, null, null);
        }

        public static Item failed(Long videoId, String errorCode, String reason) {
            return new Item(videoId, false, errorCode, reason);
        }
    }

    /** 건별 결과를 모아 집계까지 채운다 — 개수를 호출부가 따로 세지 않게 한다. */
    public static BatchApproveResponse of(List<Item> results) {
        int success = (int) results.stream().filter(Item::success).count();
        return new BatchApproveResponse(success, results.size() - success, results);
    }
}
