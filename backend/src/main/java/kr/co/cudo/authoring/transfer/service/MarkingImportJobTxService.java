package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.transfer.entity.LsEblcUldJob;
import kr.co.cudo.authoring.transfer.entity.LsEblcUldJobArtcl;
import kr.co.cudo.authoring.transfer.repository.LsEblcUldJobArtclRepository;
import kr.co.cudo.authoring.transfer.repository.LsEblcUldJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 일괄 적재 원장의 <b>트랜잭션 경계</b> — 작업을 열고, 항목을 집고, 결과를 마감한다.
 *
 * <h3>항목마다 따로 트랜잭션을 갖는다</h3>
 * <p>백 건을 한 트랜잭션에 묶으면 한 건 때문에 전부 되돌아간다(SEQ-030). 그래서 여기 있는 메서드는
 * 하나같이 <b>작은 단위</b>이고, 일꾼은 이 메서드들을 이어 부르며 사이사이에 파일 복사 같은 오래
 * 걸리는 일을 <b>트랜잭션 밖</b>에서 한다.
 *
 * <h3>모두 {@code REQUIRES_NEW} 인 이유</h3>
 * <p>일꾼은 비트랜잭션 컨텍스트에서 돈다(별도 스레드). 호출자의 트랜잭션에 얹히는 구조를 두면 나중에
 * 그쪽이 롤백될 때 <b>이미 사람에게 보인 진행 상황</b>까지 함께 되돌아간다. 각 단계가 자기 트랜잭션을
 * 열고 곧바로 닫는다.
 *
 * <h3>집기와 마감은 조건부 UPDATE 다 (CWE-362)</h3>
 * <p>두 노드가 같은 항목을 동시에 집으면 같은 영상이 두 번 적재된다. 조회 후 변경 방식이면 둘 다
 * 통과하므로, DB 가 직렬화하는 단일 UPDATE 로 <b>영향 행수 1을 받은 쪽만</b> 소유권을 갖게 한다.
 * 집계도 마찬가지다 — 읽어서 더하면 두 일꾼이 같은 값을 써 한 건이 사라진다.
 *
 * @design DOMAIN-017
 * @design ERD-032
 * @design API-217
 * @design API-218
 * @design AC-1033
 * @design SEQ-030
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarkingImportJobTxService {

    /**
     * 후보를 한 번에 몇 개까지 보고 집기를 시도할지.
     *
     * <p>1이면 두 일꾼이 언제나 같은 항목을 두고 부딪혀 한쪽이 매번 빈손으로 돌아온다. 조금 넓게 보고
     * 앞에서부터 시도하면 서로 다른 항목을 집는다.
     */
    private static final int CLAIM_CANDIDATE_WINDOW = 16;

    private final LsEblcUldJobRepository jobRepository;
    private final LsEblcUldJobArtclRepository artclRepository;

    /**
     * 작업과 항목을 <b>한 트랜잭션에서</b> 만든다.
     *
     * <p>둘을 나누면 항목 없는 작업이 남을 수 있는데, 그 작업은 진행중인 채로 아무도 처리하지 않아
     * 영원히 끝나지 않는다.
     *
     * @param seeds 마킹 문서 경로와 짝지은 영상 경로의 쌍 — 훑기가 판정한 실경로의 문자열
     * @return 만들어진 작업 식별번호
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public long openJob(String folderPath, String dmndCn, String regId, List<ItemSeed> seeds) {
        LsEblcUldJob job = jobRepository.save(
                LsEblcUldJob.open(folderPath, seeds.size(), dmndCn, regId));
        for (ItemSeed seed : seeds) {
            artclRepository.save(LsEblcUldJobArtcl.pending(
                    job.getEblcUldJobSn(), seed.markFilePathNm(), seed.vdoFilePathNm()));
        }
        return job.getEblcUldJobSn();
    }

    /** 작업을 읽는다 — 없으면 비어 있다. */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Optional<LsEblcUldJob> findJob(long jobSn) {
        return jobRepository.findById(jobSn);
    }

    /**
     * 다음 항목을 <b>원자적으로</b> 집는다.
     *
     * <p>후보를 몇 개 보고 앞에서부터 집기를 시도해, 다른 쪽이 먼저 집은 항목은 건너뛰고 다음으로
     * 넘어간다. 후보가 모두 남에게 넘어갔으면 비어 있는 것으로 답한다 — 그때 일꾼은 한 바퀴 더 돌며,
     * 정말 남은 것이 없으면 다음 조회가 후보 자체를 주지 않는다.
     *
     * @return 소유권을 얻은 항목. 집을 것이 없으면 비어 있다
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Optional<LsEblcUldJobArtcl> claimNext(long jobSn) {
        List<LsEblcUldJobArtcl> candidates = artclRepository.findPendingCandidates(
                jobSn, PageRequest.of(0, CLAIM_CANDIDATE_WINDOW));
        LocalDateTime now = LocalDateTime.now();
        for (LsEblcUldJobArtcl candidate : candidates) {
            if (artclRepository.claim(candidate.getEblcUldJobArtclSn(), now) == 1) {
                jobRepository.markStartedIfAbsent(jobSn, now);
                return artclRepository.findById(candidate.getEblcUldJobArtclSn());
            }
        }
        return Optional.empty();
    }

    /**
     * 아직 후보가 남아 있는가 — 집기에 모두 실패했을 때 <b>한 바퀴 더 돌지</b> 판단한다.
     *
     * <p>「집지 못했다」와 「남은 것이 없다」는 다른 사실이다. 구분하지 않으면 경합이 잦은 순간에
     * 일꾼이 전부 물러나 <b>대기 항목이 남았는데 아무도 처리하지 않는</b> 작업이 생긴다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public boolean hasPending(long jobSn) {
        return !artclRepository.findPendingCandidates(jobSn, PageRequest.of(0, 1)).isEmpty();
    }

    /** 처리 중이라는 신호를 갱신한다 — 오래 걸리는 항목이 멈춘 것으로 오인되지 않게. */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void heartbeat(long artclSn) {
        artclRepository.heartbeat(artclSn, LocalDateTime.now());
    }

    /**
     * 적재 성공으로 마감한다.
     *
     * <p>집계는 <b>마감이 실제로 걸렸을 때만</b> 올린다. 되돌리기 잡이 먼저 그 항목을 대기로 되돌린
     * 뒤라면 마감이 0건이고, 그때 집계만 올리면 <b>끝나지도 않은 항목이 성공으로 세어진다</b>.
     *
     * @return 마감이 실제로 걸렸는가
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean completeSuccess(long jobSn, long artclSn, Long rawSn) {
        LocalDateTime now = LocalDateTime.now();
        if (artclRepository.markSuccess(artclSn, rawSn, now) != 1) {
            log.warn("[MarkingImport] item already closed by another actor jobSn={} artclSn={}", jobSn, artclSn);
            return false;
        }
        jobRepository.incrementSuccess(jobSn, now);
        return true;
    }

    /**
     * 끝내지 못한 채 마감한다 — 실패든 건너뜀이든.
     *
     * @param status {@link LsEblcUldJobArtcl#ARTCL_STTS_FAILED} 또는
     *               {@link LsEblcUldJobArtcl#ARTCL_STTS_SKIPPED}
     * @param rawSn  이미 들어와 있어 건너뛴 경우 <b>무엇과 부딪혔는지</b>. 그 밖에는 {@code null}
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean completeUnfinished(long jobSn, long artclSn, String status, Long rawSn, String reason) {
        LocalDateTime now = LocalDateTime.now();
        int marked = artclRepository.markUnfinished(
                artclSn, status, rawSn, LsEblcUldJobArtcl.truncateReason(reason), now);
        if (marked != 1) {
            log.warn("[MarkingImport] item already closed by another actor jobSn={} artclSn={}", jobSn, artclSn);
            return false;
        }
        jobRepository.incrementFailure(jobSn, now);
        return true;
    }

    /**
     * 남은 항목이 없으면 작업을 종결한다.
     *
     * <p>종결도 <b>진행중일 때만</b> 걸린다. 두 일꾼이 마지막 항목을 동시에 끝내도 한 번만 종결되고,
     * 그 한 번을 얻은 쪽이 이관 이력 줄까지 마감한다.
     *
     * @return 이 호출이 작업을 종결시켰으면 그 종결 상태, 아니면 비어 있다
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Optional<String> finishIfDone(long jobSn) {
        if (artclRepository.countUnfinished(jobSn) > 0) {
            return Optional.empty();
        }
        boolean anyFailed = artclRepository.countFailed(jobSn) > 0;
        String status = anyFailed ? LsEblcUldJob.JOB_STTS_FAILED : LsEblcUldJob.JOB_STTS_COMPLETED;
        if (jobRepository.finishIfRunning(jobSn, status, LocalDateTime.now()) != 1) {
            // 다른 쪽이 먼저 종결했다 — 이관 이력도 그쪽이 마감한다.
            return Optional.empty();
        }
        return Optional.of(status);
    }

    /** 만들 항목 하나 — 마킹 문서와 짝지은 영상의 위치. */
    public record ItemSeed(String markFilePathNm, String vdoFilePathNm) {
    }
}
