package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobResultsResponse;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobStatusResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.webhook.service.AugmentJobSuccessApplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <b>웹훅 유실 회수</b> — 외부 상태조회(§4.4)가 종결을 알려주면 결과조회(§4.5, INT-030)로 산출물을
 * 회수해 로컬을 종결시킨다.
 *
 * <h3>왜 필요한가</h3>
 * <p>결과의 정상 인계 경로는 웹훅이다. 그런데 웹훅은 유실될 수 있고(네트워크·재시도 소진·수신측
 * 400 거부 후 벤더 포기), 그러면 그 job 은 비종결로 남아 증강 1건이 {@code PENDING} 에 머문다.
 * 만료 스윕이 결국 회수하지만 그것은 <b>실패로 종결</b>하는 것이라 <b>이미 만들어진 증강 산출물이
 * 버려진다</b>. 조회로 회수하면 성공은 성공으로 인계된다.
 *
 * <h3>진실원은 여전히 DB 다</h3>
 * <p>조회 결과로 DB 를 <b>덮어쓰지 않는다</b> — 비종결 job 에 대해서만 전이시킨다. 조회가
 * {@code RUNNING} 인데 DB 가 {@code SUCCEEDED} 인 불일치는 정상이며(웹훅이 먼저 도착), 그 경우
 * 회수는 아무 일도 하지 않는다({@code AugmentJobRecoveryTxService} 의 {@code isTerminal} 재확인).
 *
 * <h3>트랜잭션 경계</h3>
 * <p>이 빈은 <b>트랜잭션을 열지 않는다</b>. 외부 HTTP 왕복 동안 커넥션을 붙잡지 않기 위함이며,
 * DB 반영은 전부 {@link AugmentJobRecoveryTxService}(별도 빈 · {@code @Transactional})에 위임한다.
 *
 * <h3>실패는 조용히 삼키지 않되, 화면을 깨지 않는다</h3>
 * <p>회수는 <b>부가 기능</b>이므로 어떤 실패도 진행상태 조회 응답을 500 으로 만들지 않는다. 실패는
 * WARN 으로 남기고 {@code false} 를 돌려준다 — 그 job 은 다음 폴링에서 다시 시도되고, 끝내 안 되면
 * 만료 스윕이 회수한다(무한 대기 없음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentResultRecoveryService {

    private final ExternalAugmentClient externalClient;
    private final AugmentExternalProbe probe;
    /** 산출 경로 허용루트 검증 — 웹훅 경로와 <b>같은 코드</b>를 쓴다(S15, CWE-22). */
    private final AugmentJobSuccessApplier successApplier;
    private final AugmentJobRecoveryTxService recoveryTxService;

    /**
     * 외부가 종결로 보고한 job 1건을 회수한다.
     *
     * @param dataAugSn 증강 PK
     * @param job       비종결 로컬 job
     * @param status    외부 상태조회 응답(§4.4) — 종결 상태여야 한다
     * @param budget    요청 1회의 외부 호출 시간 예산 — 결과조회(§4.5)도 이 예산을 쓴다(HIGH-1)
     * @return true = 이번 호출이 회수함
     */
    boolean recover(Long dataAugSn, LsDataAugJob job, GenAiJobStatusResponse status,
                    AugmentRequestBudget budget) {
        String externalStatus = status.status();
        if (LsDataAugJob.STTS_SUCCEEDED.equals(externalStatus)) {
            return recoverSucceeded(dataAugSn, job, status, budget);
        }
        if (LsDataAugJob.STTS_FAILED.equals(externalStatus)
                || LsDataAugJob.STTS_CANCELED.equals(externalStatus)) {
            return recoveryTxService.recover(dataAugSn, job.getAugJobSn(), job.getExternalJobId(),
                    externalStatus, List.of(), status.errorCode(), status.errorMessage());
        }
        return false;
    }

    /**
     * 성공 회수 — 결과조회(§4.5)로 산출 경로를 받아 <b>허용루트 검증을 통과시킨 뒤에만</b> 반영한다.
     *
     * <p>검증은 {@link AugmentJobSuccessApplier#verifyOutputPaths} 하나만 쓴다. 새 검증 로직을 여기서
     * 만들면 정적 가드({@code ExternalAugmentClientContractGuardTest})가 초록인 채 경로 순회(CWE-22)가
     * 열린다 — 그 가드는 파일 단위라 <b>파일을 나누면 침묵</b>하기 때문이다.
     */
    private boolean recoverSucceeded(Long dataAugSn, LsDataAugJob job, GenAiJobStatusResponse status,
                                     AugmentRequestBudget budget) {
        AugmentExternalProbe.Probe<GenAiJobResultsResponse> results =
                probe.query(externalClient.fetchJobResults(job.getExternalJobId()), "결과조회", dataAugSn,
                        budget.remaining());
        if (!results.isSuccess()) {
            log.warn("[Augment][Recovery] 결과조회 실패 — 회수 보류 dataAugSn={} jobSeq={} reason={}",
                    dataAugSn, job.getJobSeq(), results.reason());
            return false;
        }
        List<String> outputs;
        try {
            outputs = successApplier.verifyOutputPaths(outputFilePathsOf(results.payload()), "recovery");
        } catch (CustomException e) {
            // 허용 루트 밖 경로 — 회수하지 않는다. job 은 비종결로 남고 만료 스윕이 회수한다.
            log.warn("[Augment][Recovery] 산출 경로 거부 — 회수 중단 dataAugSn={} jobSeq={} code={}",
                    dataAugSn, job.getJobSeq(), e.getErrorCode());
            return false;
        }
        return recoveryTxService.recover(dataAugSn, job.getAugJobSn(), job.getExternalJobId(),
                LsDataAugJob.STTS_SUCCEEDED, outputs, status.errorCode(), status.errorMessage());
    }

    /** §4.5 {@code results[]} → 산출 경로 목록(순서 보존). 검증은 applier 가 수행한다. */
    private static List<String> outputFilePathsOf(GenAiJobResultsResponse response) {
        return response.results().stream()
                .map(GenAiJobResultsResponse.ResultItem::outputFilePath)
                .toList();
    }
}
