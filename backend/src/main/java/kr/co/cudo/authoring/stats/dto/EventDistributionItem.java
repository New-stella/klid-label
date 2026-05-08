package kr.co.cudo.authoring.stats.dto;

/**
 * 이벤트 코드별 누적 데이터 카운트.
 *
 * <p>FE 6종 이벤트(쓰러짐/폭력/교통사고/이상행동/침수/산불) 카드 분포 영역에 사용.
 * label 은 화면 표기용 한글 라벨.
 */
public record EventDistributionItem(String eventTypeCd, String label, long count) {
}
