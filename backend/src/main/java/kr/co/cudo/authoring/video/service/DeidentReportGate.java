package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * S7 — <b>비식별 누락 신고 구간 판정</b>(actor 무관, 데이터 상태 전용) 단일 원천.
 *
 * <h3>왜 별도 컴포넌트인가</h3>
 * 신고 구간({@code LS_DATA_RAW.DE_IDNTF_YN='F'}) 차단은 두 성격의 호출부가 쓴다.
 * <ul>
 *   <li><b>인가 경로</b>: {@code LabelAccessGuard.requireNotUnderDeidentReport} — 사용자 요청 컨텍스트라
 *       {@code TokenClaims} 인가를 먼저 통과시킨 뒤 4xx({@code PRECONDITION_FAILED})로 끝낸다.</li>
 *   <li><b>산출(export) 경로</b>: {@code DatasetExportService.export} — {@code @Async} 배치 컨텍스트라
 *       actor 자체가 없다. 인가 게이트가 아니라 <b>데이터 상태 게이트</b>다.</li>
 * </ul>
 * 두 곳이 각자 {@code "F".equals(...)} 를 재구현하면 정책이 갈라진다(Phase 6 에서 실제로 export 경로만
 * 누락돼 신고된 영상의 프레임 이미지가 새 버전 폴더로 산출됐다 — CWE-359). 그래서 <b>상태 판정만</b>
 * 여기로 분리하고, 예외/응답 규약은 각 호출부가 자기 컨텍스트에 맞게 결정한다.
 *
 * <p>판정은 {@code DE_IDNTF_YN} 단일 컬럼 projection 1회 조회다(PK 인덱스 lookup). 호출부는 루프 밖에서
 * 1회만 호출한다.
 */
@Component
@RequiredArgsConstructor
public class DeidentReportGate {

    /** {@code LS_DATA_RAW.DE_IDNTF_YN} — 비식별 실패/누락 신고 상태(재비식별 대기). */
    public static final String DEIDENT_FAILED = "F";

    private final VideoRepository videoRepository;

    /**
     * 이 영상이 <b>비식별 누락 신고 구간</b>(재비식별 대기)인가.
     *
     * <p>{@code 'Y'}(정상)·{@code 'N'}(미수행)·NULL·행 부재는 모두 {@code false}(통과) — 일반 영상 흐름에
     * 영향이 없다. {@code rawSn} 이 null 이면 판정 대상이 아니므로 {@code false}.
     *
     * <p>조회 자체가 실패(DB 오류)하면 예외를 그대로 전파한다. 호출부가 "판정 불가 = 진행 금지"
     * (fail-closed)로 다루게 하기 위해서다 — 여기서 삼켜 {@code false}(통과)로 만들지 않는다.
     */
    public boolean isUnderDeidentReport(Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        return DEIDENT_FAILED.equals(videoRepository.findDeIdntfYnByRawSn(rawSn).orElse(null));
    }

    /**
     * 같은 판정을 <b>RAW 행 잠금(SELECT … FOR UPDATE) 하에</b> 수행한다 — 판정 후 커밋까지 신고
     * ({@code DeidentReportService.report} 의 {@code findByRawSnForUpdate} + {@code markDeidentified("F")})
     * 와 <b>직렬화</b>되어야 하는 호출부용.
     *
     * <h3>왜 무잠금 판정으로 부족한가 (H1 · CWE-359/367)</h3>
     * export 는 프레임 N개 × 2벌 파일 복사라 수 분이 걸린다. 진입부 게이트가 {@code 'Y'} 를 읽고 통과한
     * 뒤 쓰기 도중에 신고가 커밋되면, <b>비식별 누락이 확인된 그 프레임</b>이 새 버전 폴더에 전량 기록되고
     * 성공 마감 → 통지까지 나간다. 그래서 <b>성공/부분 마감 직전</b>에 이 잠금 판정으로 재확인한다.
     * 이는 {@code ResolutionSnapshotService.snapshot} · {@code ResolutionReservationPersister} ·
     * {@code AugmentResultService.createAugmentedVideo} 가 이미 쓰는 확립된 프로토콜과 동일하다.
     *
     * <p><b>호출 규약</b>: 반드시 쓰기 트랜잭션 안에서 호출한다(잠금은 그 트랜잭션 커밋까지 유지된다).
     * 판정 결과에 따른 상태 변경도 <b>같은 트랜잭션</b>에서 끝내야 창이 닫힌다.
     *
     * <p>행이 없으면 {@code false}(통과) — 무잠금 판정과 동일 규약. 조회 실패는 전파(fail-closed).
     */
    public boolean isUnderDeidentReportLocked(Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        return DEIDENT_FAILED.equals(videoRepository.findByRawSnForUpdate(rawSn)
                .map(kr.co.cudo.authoring.video.entity.LsDataRaw::getDeIdntfYn)
                .orElse(null));
    }
}
