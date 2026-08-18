package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;
import kr.co.cudo.authoring.portal.repository.LsPortalUldFrmeRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUserLabelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 포털 보존기간 만료 자동 삭제의 <b>트랜잭션 경계 전용</b> 서비스. @design DFEAT-055, AC-032, AC-036, AC-037
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
 * <p>보존일수는 {@link PortalRetentionPolicy} 가 단독으로 판정한다. 여기서 설정 키를 직접 읽거나
 * 계산식을 재유도하면 화면이 고지하는 만료 예정 시각과 실제 삭제 시점이 <b>따로 논다</b>.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalRetentionSweepTxService {

    private final LsPortalUserLabelRepository userLabelRepository;
    private final LsPortalUldRepository uldRepository;
    private final LsPortalUldFrmeRepository frmeRepository;
    private final PortalRetentionPolicy retentionPolicy;

    // ======================== 축 A — 데이터마트 라벨 ========================

    /**
     * 데이터마트 저장 라벨 축 스윕. @design AC-032
     *
     * <p>파일이 없으므로 클레임 단계가 필요 없다 — (사용자, 영상) 그룹마다 <b>조건부 DELETE 1회</b>로
     * 끝난다. 2노드 Active-Active 에서 중복 실행돼도 두 번째 노드는 0행이 되어 멱등하다.
     *
     * @return 실제로 라벨이 삭제된 그룹 수(설정 부재로 건너뛰면 0)
     */
    @Transactional("controlTransactionManager")
    public int sweepDatamartLabels() {
        OptionalInt days = retentionPolicy.datamartRetentionDays();
        if (days.isEmpty()) {
            // 폴백하지 않는다 — 위 클래스 주석 참조. 지우지 않는 쪽이 항상 안전하다.
            log.error("[PortalRetention] 데이터마트 보존기간 설정을 읽지 못해 이번 회차를 건너뛴다"
                    + " key=portal.datamart.retention-days");
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days.getAsInt());
        int deletedGroups = 0;
        for (Object[] row : userLabelRepository.findExpiredLabelGroups(cutoff)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) {
                continue;
            }
            String portalUserNo = (String) row[0];
            Long srcRawSn = ((Number) row[1]).longValue();
            int removed = userLabelRepository.deleteExpiredLabelGroup(portalUserNo, srcRawSn, cutoff);
            if (removed > 0) {
                deletedGroups++;
            }
        }
        return deletedGroups;
    }

    // ======================== 축 B — 업로드 자산 ========================

    /**
     * 삭제 대상 업로드 자산 후보 스캔(읽기 전용). @design AC-036, AC-037
     *
     * <p>READY·FAILED <b>두 축을 각자의 설정으로 독립 판정</b>한다(AC-037 and_examples[1]) — 한쪽 설정이
     * 없으면 그 축만 건너뛰고 다른 축은 정상 동작한다. {@code PROCESSING}·{@code UPLOADED} 는 어느
     * 쿼리에도 등장하지 않으므로 구조적으로 후보가 될 수 없다(AC-036).
     *
     * <p>커트라인을 후보에 실어 보내는 것은 의도다 — 뒤이은 조건부 DELETE 가 <b>스캔과 같은 커트라인</b>
     * 으로 재판정해야 한 회차 안에서 판정 기준이 흔들리지 않는다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<ExpiredUpload> findExpiredUploads() {
        List<ExpiredUpload> candidates = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        OptionalInt readyDays = retentionPolicy.uploadRetentionDays();
        if (readyDays.isEmpty()) {
            log.error("[PortalRetention] 업로드(READY) 보존기간 설정을 읽지 못해 이번 회차를 건너뛴다"
                    + " key=portal.upload.retention-days");
        } else {
            LocalDateTime cutoff = now.minusDays(readyDays.getAsInt());
            for (LsPortalUld uld : uldRepository.findExpiredReady(cutoff)) {
                candidates.add(toCandidate(uld, Axis.READY, cutoff));
            }
        }

        OptionalInt failedDays = retentionPolicy.uploadFailedRetentionDays();
        if (failedDays.isEmpty()) {
            log.error("[PortalRetention] 업로드(FAILED) 보존기간 설정을 읽지 못해 이번 회차를 건너뛴다"
                    + " key=portal.upload.failed-retention-days");
        } else {
            LocalDateTime cutoff = now.minusDays(failedDays.getAsInt());
            for (LsPortalUld uld : uldRepository.findExpiredFailed(cutoff)) {
                candidates.add(toCandidate(uld, Axis.FAILED, cutoff));
            }
        }
        return candidates;
    }

    /**
     * 만료 업로드 자산 1건의 DB 행을 <b>조건부</b> 삭제한다 — 잡이 파일을 먼저 지운 뒤에만 호출한다.
     * @design AC-036, AC-037
     *
     * <p>{@code LS_PORTAL_ULD_FRME}·{@code LS_PORTAL_ULD_LBL} 은 DB FK 가
     * {@code ON DELETE CASCADE} 라 이 한 문장으로 함께 정리된다(수기 삭제 순서표를 두지 않는다 —
     * CASCADE 가 없는 축의 관례를 여기에 복제하면 실제와 어긋난 이중 진실원이 된다).
     *
     * @return 삭제된 행 수(0 이면 타 노드 선점 또는 조건 해제)
     */
    @Transactional("controlTransactionManager")
    public int deleteExpiredUpload(ExpiredUpload target) {
        return target.axis() == Axis.READY
                ? uldRepository.deleteExpiredReady(target.uldSn(), target.cutoff())
                : uldRepository.deleteExpiredFailed(target.uldSn(), target.cutoff());
    }

    /**
     * 자산 행이 아직 남아 있는지 확인(0행 삭제의 원인 구분용).
     *
     * <p>조건부 DELETE 가 0행일 때 원인은 둘이다 — <b>타 노드가 이미 지웠다</b>(정상)와 <b>조건이
     * 풀렸다</b>(파일은 지웠는데 행이 남는 비정상). 행 존재 여부로만 그 둘이 갈린다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public boolean exists(Long uldSn) {
        return uldRepository.existsById(uldSn);
    }

    /** 자산 1건의 삭제 대상 파일 경로를 모은다(프레임 + 원본, 중복 제거·순서 보존). */
    private ExpiredUpload toCandidate(LsPortalUld uld, Axis axis, LocalDateTime cutoff) {
        Set<String> paths = new LinkedHashSet<>();
        for (LsPortalUldFrme frme : frmeRepository.findAllByUldSnOrderByFrmeNo(uld.getUldSn())) {
            if (frme.getFilePathNm() != null && !frme.getFilePathNm().isBlank()) {
                paths.add(frme.getFilePathNm());
            }
        }
        if (uld.getFilePathNm() != null && !uld.getFilePathNm().isBlank()) {
            paths.add(uld.getFilePathNm());
        }
        return new ExpiredUpload(uld.getUldSn(), axis, cutoff, List.copyOf(paths));
    }

    /** 업로드 보존기간 축 — 기준점과 설정 키가 서로 다르며 독립 판정된다. @design AC-037 */
    public enum Axis {
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
