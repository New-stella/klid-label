package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.portal.repository.PortalUserWorkRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 포털 보존기간 만료 자동 삭제의 <b>트랜잭션 경계 전용</b> 서비스. @design DFEAT-055, AC-1068, AC-036, AC-037
 *
 * <p>{@link kr.co.cudo.authoring.portal.scheduler.PortalRetentionSweepJob}(스케줄 오케스트레이션)이
 * 조건부 벌크 DELETE 를 별 빈의 실 트랜잭션으로 위임하도록 분리했다. 스윕 잡과 같은 클래스에 두면
 * {@code @Scheduled} 프록시 내부 self-invocation 으로 {@code @Transactional} 이 무효화되어
 * ({@code TransactionRequiredException}) 벌크 쿼리가 활성 트랜잭션 없이 실행된다
 * ({@link PortalUploadSweepTxService} 와 동일 패턴).
 *
 * <h3>★ 설정이 없으면 폴백하지 않고 그 축을 건너뛴다 (의도적 관례 이탈)</h3>
 * <p>이 저장소의 다른 설정 소비자({@code PortalFrameExtractRunner} 의 스냅샷 간격 등)는 조회 실패 시
 * <b>상수로 폴백</b>한다. 삭제 배치는 그 관례를 <b>따르지 않는다</b> — 파괴적 기능이 fail-open 하면
 * "설정을 못 읽어 아무도 지시하지 않은 기본값으로 사용자 데이터를 지운다"가 성립하고, 삭제에는
 * 복구 경로가 없기 때문이다. 값이 없으면 <b>ERROR 로그 + 그 축 skip</b> 이며 삭제를 시도조차 하지 않는다.
 * 그 대가로 시드({@code V11__seed_portal_retention_config.sql})가 필수다 — 폴백을 없앤 채 시드까지
 * 없으면 기능이 죽은 채 배포된다. 「폴백 금지」와 「시드」는 세트다.
 *
 * <h3>커트라인은 정책 한 곳에서만 온다</h3>
 * <p>보존일수뿐 아니라 <b>커트라인 산술까지</b> {@link PortalRetentionPolicy} 가 소유한다 — 이 클래스는
 * {@code datamartCutoff()}/{@code uploadCutoffs()} 가 준 값을 <b>받아 쓰기만</b> 하고 자기 {@code now()}
 * 를 뜨거나 {@code minusDays} 를 다시 쓰지 않는다. 커트라인({@code 지금 − 보존일수})은 화면이 고지하는
 * 만료 예정 시각({@code 기준점 + 보존일수})의 <b>역함수</b>라, 두 산술이 서로 다른 클래스에서 독립
 * 유도되면 고지한 날과 실제 삭제일이 <b>따로 논다</b>. 값이 없으면(설정 부재·0 이하) 그 축을 건너뛴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalRetentionSweepTxService {

    private final PortalUserWorkRepository userWorkRepository;
    private final PortalUploadAssetRepository assetRepository;
    private final PortalRetentionPolicy retentionPolicy;

    // ==================== 축 A — 데이터마트 저작물(라벨·메타·어노테이션) ====================

    /**
     * 데이터마트 채널 저작물 스윕. @design DFEAT-055, AC-1068
     *
     * <p>★ <b>지우는 것은 저장 라벨만이 아니다</b> — 같은 (사용자, 영상)의 <b>메타 오버레이와
     * 이벤트 어노테이션 오버레이도 한 벌로 함께</b> 지운다. 라벨만 지우면 그 사용자가 고친 메타와
     * 어노테이션이 영원히 남는다. 후보를 찾는 축도 같은 폭이라 <b>라벨 없이 메타만 고친 (사용자,
     * 영상)</b>도 후보가 된다 — 삭제 대상만 넓히고 찾는 축을 라벨에 두면 그 구멍이 닫히지 않는다.
     *
     * <p>커트라인은 「그룹의 <b>최초</b> 저장 시각 + 보존일수」이며 그 최초는 <b>세 저작물을
     * 통틀어</b> 가장 이른 저장 시각이다(DFEAT-055 — 포털 확정 회신 2026-09-03). 후보 조회와
     * 조건부 삭제가 <b>같은 축</b>을 쓰며, 그 축은 조회 경로가 화면에 고지하는 만료 예정 시각과도
     * 같다 — 갈리면 고지한 날과 실제 삭제일이 어긋난다. ⚠ 업로드 축(아래)은 여전히 「늦은 쪽」이라
     * <b>두 축을 통일하지 않는다</b>.
     *
     * <p>파일이 없으므로 클레임 단계가 필요 없다 — (사용자, 영상) 그룹마다 <b>조건부 DELETE 1회</b>로
     * 끝난다. 2노드 Active-Active 에서 중복 실행돼도 두 번째 노드는 0행이 되어 멱등하다.
     *
     * <p>⚠ <b>연쇄 삭제와 다른 축이다</b> — 원천 영상이 지워질 때 세 표가 함께 정리되는 것은 외래키가
     * 이미 하고 있다. 여기서 닫는 것은 <b>영상은 남아 있고 보존기간만 지난</b> 경우다.
     *
     * @return 실제로 저작물이 삭제된 그룹 수(설정 부재로 건너뛰면 0)
     */
    @Transactional("controlTransactionManager")
    public int sweepDatamartWorks() {
        Optional<LocalDateTime> maybeCutoff = retentionPolicy.datamartCutoff();
        if (maybeCutoff.isEmpty()) {
            // 폴백하지 않는다 — 위 클래스 주석 참조. 지우지 않는 쪽이 항상 안전하다.
            log.error("[PortalRetention] 데이터마트 보존기간 설정을 읽지 못해 이번 회차를 건너뛴다"
                    + " key=portal.datamart.retention-days");
            return 0;
        }
        LocalDateTime cutoff = maybeCutoff.get();
        int deletedGroups = 0;
        for (PortalUserWorkRepository.WorkGroup group
                : userWorkRepository.findExpiredWorkGroups(cutoff)) {
            int removed = userWorkRepository.deleteExpiredWorkGroup(
                    group.portalUserNo(), group.srcRawSn(), cutoff);
            if (removed > 0) {
                deletedGroups++;
            }
        }
        return deletedGroups;
    }

    // ======================== 축 B — 업로드 자산 ========================

    /**
     * 삭제 대상 업로드 자산 후보 스캔(읽기 전용). @design DFEAT-055, AC-1070, AC-036, AC-037
     *
     * <p><b>세 축을 각자의 기산점으로 독립 판정</b>한다(AC-1070) — 마킹 대기(등록일) · 준비 완료
     * (등록일과 라벨 마지막 저장일 중 늦은 쪽) · 처리 실패(실패 전이 시각). 설정이 없으면 그 축만
     * 건너뛰고 다른 축은 정상 동작한다. <b>{@code PROCESSING} 만</b> 어느 쿼리에도 등장하지 않아
     * 구조적으로 후보가 될 수 없다 — 프레임 추출과 경쟁하면 파일과 원장이 어긋나며, 그 상태는
     * 방치 판정이 따로 회수한다.
     *
     * <p>★ 마킹 대기 축을 <b>방치 판정과 혼동하지 말 것</b> — 2026-09-02 에 닫은 것은 「분 단위 방치
     * 타이머가 마킹 대기를 <b>실패로 마감</b>하던 것」이고, 여기 있는 것은 「일 단위 보존기간으로
     * <b>정상 만료</b>시키는 것」이다. 이 축이 없으면 마킹하지 않은 자산은 <b>파일째 영구히</b> 남는다.
     *
     * <p>커트라인을 후보에 실어 보내는 것은 의도다 — 뒤이은 조건부 DELETE 가 <b>스캔과 같은 커트라인</b>
     * 으로 재판정해야 한 회차 안에서 판정 기준이 흔들리지 않는다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<ExpiredUpload> findExpiredUploads() {
        List<ExpiredUpload> candidates = new ArrayList<>();
        // ★ 한 회차의 기준시각은 하나다 — 축마다 now() 를 뜨면 판정 기준이 갈린다. 그 보장은 여기
        //   규율이 아니라 스냅샷 자료구조(UploadCutoffs.capturedAt)가 진다 — 축이 늘어도 그대로다.
        //   그래서 이 창구는 한 회차에 <한 번만> 부른다.
        PortalRetentionPolicy.UploadCutoffs cutoffs = retentionPolicy.uploadCutoffs();

        // 마킹 대기·준비 완료는 같은 설정을 공유하므로 진단 로그도 한 번만 남긴다(같은 키가 두 줄
        // 찍히면 운영자가 서로 다른 설정 둘이 비었다고 읽는다).
        if (cutoffs.ready().isEmpty()) {
            log.error("[PortalRetention] 업로드(마킹 대기·준비 완료) 보존기간 설정을 읽지 못해"
                    + " 이번 회차를 건너뛴다 key=portal.upload.retention-days");
        }
        if (cutoffs.failed().isEmpty()) {
            log.error("[PortalRetention] 업로드(처리 실패) 보존기간 설정을 읽지 못해 이번 회차를 건너뛴다"
                    + " key=portal.upload.failed-retention-days");
        }

        collectExpired(candidates, Axis.UPLOADED, cutoffs.uploaded());
        collectExpired(candidates, Axis.READY, cutoffs.ready());
        collectExpired(candidates, Axis.FAILED, cutoffs.failed());
        return candidates;
    }

    /** 축 1개분 후보 수집 — 커트라인이 없으면(설정 부재·0 이하) 조회조차 하지 않는다. */
    private void collectExpired(List<ExpiredUpload> into, Axis axis, Optional<LocalDateTime> cutoff) {
        if (cutoff.isEmpty()) {
            return;
        }
        LocalDateTime at = cutoff.get();
        for (Long uldSn : assetRepository.findExpired(repoAxis(axis), at)) {
            into.add(toCandidate(uldSn, axis, at));
        }
    }

    /**
     * 만료 업로드 자산 1건의 DB 행을 <b>조건부</b> 삭제한다 — 잡이 파일을 먼저 지운 뒤에만 호출한다.
     * @design AC-036, AC-037
     *
     * <p>★ <b>흡수 뒤에는 한 문장으로 끝나지 않는다</b>(ADR-058). 프레임·메타는 여전히 외래키 연쇄로
     * 정리되지만 라벨·라벨 속성값·라벨 이력·증강·증강라벨매핑은 <b>부모 외래키가 없거나 연쇄가 아니라</b>
     * 명시적으로 지우지 않으면 오류 없이 조용히 고아가 된다. 순서표는
     * {@link PortalUploadAssetRepository#deleteExpired} 한 곳이 소유하며, 그 실행문마다
     * <b>출처 판별자 + 소유자 보유 + 보존기간 경과</b> 셋이 다시 걸린다 — 하나만 빠지면 관제 영상을 지운다.
     *
     * @return 삭제된 행 수(0 이면 타 노드 선점 또는 조건 해제)
     */
    @Transactional("controlTransactionManager")
    public int deleteExpiredUpload(ExpiredUpload target) {
        return assetRepository.deleteExpired(repoAxis(target.axis()), target.uldSn(), target.cutoff());
    }

    /**
     * 축 매핑 <b>단일 지점</b> — 후보 조회와 삭제가 같은 표를 쓴다.
     *
     * <p>축이 곧 삭제 술어의 상태 조건이라, 조회와 삭제가 서로 다르게 매핑하면 <b>다른 상태의 자산을
     * 지운다</b>. 삼항 연산으로 두면 축이 늘 때 조용히 「나머지는 전부 FAILED」가 되므로 열거를 다 적는다.
     */
    private static PortalUploadAssetRepository.RetentionAxis repoAxis(Axis axis) {
        return switch (axis) {
            case UPLOADED -> PortalUploadAssetRepository.RetentionAxis.UPLOADED;
            case READY -> PortalUploadAssetRepository.RetentionAxis.READY;
            case FAILED -> PortalUploadAssetRepository.RetentionAxis.FAILED;
        };
    }

    /**
     * 자산 행이 아직 남아 있는지 확인(0행 삭제의 원인 구분용).
     *
     * <p>조건부 DELETE 가 0행일 때 원인은 둘이다 — <b>타 노드가 이미 지웠다</b>(정상)와 <b>조건이
     * 풀렸다</b>(파일은 지웠는데 행이 남는 비정상). 행 존재 여부로만 그 둘이 갈린다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public boolean exists(Long uldSn) {
        return assetRepository.exists(uldSn);
    }

    /** 자산 1건의 삭제 대상 파일 경로를 모은다(프레임 + 원본, 중복 제거·순서 보존). */
    private ExpiredUpload toCandidate(Long uldSn, Axis axis, LocalDateTime cutoff) {
        Set<String> paths = new LinkedHashSet<>(assetRepository.findFilePaths(uldSn));
        return new ExpiredUpload(uldSn, axis, cutoff, List.copyOf(paths));
    }

    /**
     * 업로드 보존기간 축 — 기준점이 서로 다르며 독립 판정된다. @design AC-1070, AC-037
     *
     * <p>{@code PROCESSING} 은 여기 없다 — 프레임 추출과 경쟁하기 때문이며 방치 판정이 따로 회수한다.
     */
    public enum Axis {
        /** 마킹 대기 자산 — 기준점 = 등록일. 설정은 {@link #READY} 축과 공유한다. */
        UPLOADED,
        /** 정상 처리 자산 — 기준점 = 등록일·라벨 최종 저장일 중 늦은 쪽. */
        READY,
        /** 처리 실패 자산 — 기준점 = FAILED 전이 시각. */
        FAILED
    }

    /**
     * 삭제 후보 스냅샷.
     *
     * @param uldSn     자산 식별자
     * @param axis      판정 축
     * @param cutoff    이 후보를 뽑은 커트라인 — <b>삭제문이 같은 값으로 재판정</b>한다
     * @param filePaths 삭제 대상 파일 경로(프레임 + 원본). 잡이 <b>DB 삭제보다 먼저</b> 지운다
     */
    public record ExpiredUpload(Long uldSn, Axis axis, LocalDateTime cutoff, List<String> filePaths) {
    }
}
