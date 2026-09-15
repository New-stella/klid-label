package kr.co.cudo.authoring.portal.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * 공개된 해제본 1건의 요약 — <b>해제본 옆에 함께 놓여</b> 재기동 뒤에도 남는다.
 *
 * <p>메모리에 두면 재기동으로 사라져 「준비는 완료인데 무엇이 준비됐는지 모른다」가 된다. 원장 표를
 * 두지 않기로 한 이번 범위에서 그 자리를 채우는 것이 이 파일이다.
 *
 * <p>⚠ <b>경로를 담지 않는다.</b> 이 값은 그대로 응답에 실리므로 조달처 절대경로·저장소 루트가
 * 섞이면 내부 경로가 외부로 샌다(CWE-209).
 *
 * @param code          데이터셋 코드. <b>비어 올 수 있다</b>(옛 데이터)
 * @param version       데이터셋 버전
 * @param variant       소재 구분. <b>비어 올 수 있다</b>
 * @param entryCount    압축 해제 항목 수
 * @param totalBytes    압축 해제 총 바이트
 * @param videoCount    함께 조회된 데이터셋 영상 수(이번 범위에서 내려받지는 않는다)
 * @param provisionedAt 공개 시각
 * @design INT-014
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortalMaterialsSummary(
        String code,
        String version,
        String variant,
        int entryCount,
        long totalBytes,
        int videoCount,
        Instant provisionedAt) {
}
