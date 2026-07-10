package kr.co.cudo.authoring.augment.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 증강 잡 카드 응답 — FE {@code AugmentJob} 계약(frontend/src/features/augment/types.ts)과 1:1 정합.
 *
 * <p>LS_DATA_AUG 에는 요청 배치/잡 식별자가 저장되지 않으므로(AtomicLong jobId 는 응답 전용,
 * OTSD_JOB_ID 는 외부연동 전 null) <b>영상 단위</b>로 그룹핑하여 영상 1건 = 잡 카드 1개로 재구성한다.
 * 그룹 키는 영상 대표프레임 SRC_SN 이며(같은 영상의 모든 증강 유형이 동일 대표프레임을 공유), 화면 표시용
 * videoId/jobId 는 SRC_SN → 원본영상 RAW_SN 매핑으로 도출한다(매핑 부재 시 SRC_SN 폴백).
 *
 * <ul>
 *   <li>{@code jobId}      = videoId 와 동일(=원본 RAW_SN). FE 는 클릭 시 /augment/result/{jobId} 로 이동한다.</li>
 *   <li>{@code videoId}    = 원본 영상 RAW_SN (SRC_SN → RAW_SN 조회, 매핑 부재 시 SRC_SN)</li>
 *   <li>{@code cctvName}   = 영상의 CCTV 명 (RAW_SN → MNG_RESOURCE_CCTV 조인, 없으면 null)</li>
 *   <li>{@code types}      = 그룹의 distinct AUG_TYPE_CD (레거시 RESOLUTION 값도 데이터에 있으면 포함)</li>
 *   <li>{@code status}     = {@link AugmentJobStatus} 집계값의 name()</li>
 *   <li>{@code requestedAt}= 그룹 MIN(REG_DT)</li>
 *   <li>{@code completedAt}= 전부 종료 시 MAX(검수 완료 일시), 아니면 null</li>
 *   <li>{@code videoCount} = 1 (영상 단위 그룹)</li>
 * </ul>
 */
public record AugmentJobResponse(
        Long jobId,
        Long videoId,
        String cctvName,
        List<String> types,
        String status,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        int videoCount
) {
}
