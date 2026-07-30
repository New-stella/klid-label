package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.entity.LsDataRaw;
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
 * <h3>★ 판정 범위 = <b>자기 rawSn 행 하나</b> (2026-07-29 사용자 확정, 구속)</h3>
 * <b>파생영상(증강 {@code WINTER/NIGHT/RAIN} · 해상도 {@code RESL_*})은 원본의 비식별 신고와 무관하게
 * 다룬다.</b> 즉 이 게이트는 {@code ORGNL_RAW_SN} 을 <b>보지 않는다</b> — 부모가 {@code 'F'} 여도 파생
 * 영상은 계속 서빙·산출되고, 파생영상에서는 신고를 접수하지 않는다
 * ({@code DeidentReportService.report} 가 파생이면 412 로 거부).
 *
 * <p><b>이 정책의 함의를 감춰서는 안 된다</b>: 부모의 마스킹 실패 픽셀은 그 시점에 복사된 파생본에도
 * 남아 있는데, 파생본은 계속 서빙된다. 사용자가 이 점을 알고 감수하기로 한 확정 사항이다
 * (파생본은 재비식별 수단이 없어 신고를 받아도 해소할 방법이 없고, 트리 전파는 아래처럼 부작용이 컸다).
 *
 * <p><b>폐기된 안 — 조상/자손 전파(2026-07-28 ~ 07-29, PR #52 및 후속 DEV_FIX)</b>: 부모 신고를 파생까지
 * 전파하려고 ①조상 체인 순회 판정 ②자손 방향 캐시 evict·복구 팬아웃 ③조상 행 부재 fail-closed
 * ④체인 잠금 정준 순서를 넣었으나, 차단↔복구 비대칭·팬아웃 상한 초과 시 정상 트리 영구 차단(DoS)·
 * 막다른 안내(파생 배정 WORKER 는 부모에 403) 같은 결함이 연쇄로 나왔다. <b>다시 시도하지 말 것</b> —
 * 되살리려면 "파생본 재비식별 수단"부터 만들어야 한다.
 *
 * <p>판정은 {@code DE_IDNTF_YN} 단일 컬럼 projection 1회 조회다(PK 인덱스 lookup).
 *
 * <p><b>호출 빈도</b>: 대부분의 호출부는 진입부에서 1회만 판정한다. 다만 <b>장시간 외부 전송</b>처럼
 * 판정 이후에 신고가 커밋될 수 있는 경로는 <b>의도적으로 재판정</b>한다 —
 * {@code AugmentJobSubmitService.submit} 은 청크(기본 100장) 단위 위탁 루프에서 청크마다 이 메서드를
 * 호출해 남은 청크의 PII 경로 전송을 끊는다(수 초~수십 초에 걸친 전송 구간의 노출량 축소).
 * 즉 "루프 밖 1회" 는 규약이 아니라 <b>기본값</b>이며, 재판정이 필요한 경로는 비용(PK lookup)을
 * 감수하고 루프 안에서 호출한다.
 */
@Component
@RequiredArgsConstructor
public class DeidentReportGate {

    /** {@code LS_DATA_RAW.DE_IDNTF_YN} — 비식별 실패/누락 신고 상태(재비식별 대기). */
    public static final String DEIDENT_FAILED = "F";

    private final VideoRepository videoRepository;

    /**
     * 이 영상이 <b>비식별 누락 신고 구간</b>(재비식별 대기)인가 — 자기 행만 본다.
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
     * <p>잠그는 행은 <b>자기 rawSn 하나</b>다(위 판정 범위와 동일) — 한 행만 잠그므로 다른 경로와의
     * 잠금 순서를 맞출 필요가 없다(교착 위험 없음).
     *
     * <p>행이 없으면 {@code false}(통과) — 무잠금 판정과 동일 규약. 조회 실패는 전파(fail-closed).
     */
    public boolean isUnderDeidentReportLocked(Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        return DEIDENT_FAILED.equals(videoRepository.findByRawSnForUpdate(rawSn)
                .map(LsDataRaw::getDeIdntfYn)
                .orElse(null));
    }
}
