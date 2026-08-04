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
 *   <li>{@code cctvName}   = 영상의 CCTV 명 (RAW_SN → 관제 인입 평면값, 없으면 null)</li>
 *   <li>{@code types}          = 그룹의 검수 대상 증강 유형 distinct AUG_TYPE_CD(WINTER/NIGHT/RAIN 등, RESL_ 접두 제외)</li>
 *   <li>{@code resolutionTypes} = 그룹의 해상도 파생(RESL_ 접두) distinct AUG_TYPE_CD. 저작도구 내부 생성물로
 *       <b>증강 라벨검수 대상이 아니다</b> — FE 는 이 목록을 비-검수("파생 생성됨")로 렌더링한다.
 *       {@code types} 와 상호배타(같은 코드가 양쪽에 동시 등장하지 않음)라 FE 의 검수 액션 오노출을 차단한다.</li>
 *   <li>{@code status}     = {@link AugmentJobStatus} 집계값의 name(). 그룹의 <b>전체 row(RESL_ 해상도 파생 포함)</b>
 *       를 집계하며, 해상도 파생의 상태는 파생 생성 라이프사이클과 일치한다 — 예약~확정 사이 in-flight 는
 *       PENDING(생성 중, non-terminal), finalize 성공 확정 후에만 ACCEPTED(생성 완료, terminal)다. 따라서
 *       in-flight 창에서는 COMPLETED 로 오표기되지 않는다.</li>
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
        List<String> resolutionTypes,
        String status,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        int videoCount
) {
}
