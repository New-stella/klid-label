package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.transfer.entity.LsOtsdDatstTrnsfHstry;
import kr.co.cudo.authoring.transfer.repository.LsOtsdDatstTrnsfHstryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이관 이력을 <b>적재 트랜잭션과 독립적으로</b> 남긴다.
 *
 * <h3>왜 {@code REQUIRES_NEW} 인가</h3>
 * <p>적재가 실패하면 그 트랜잭션은 통째로 롤백된다. 이력을 같은 트랜잭션에 두면 <b>실패한 이관은 아무
 * 흔적도 남지 않아</b> 왜 안 들어왔는지 되짚을 수 없다. 그래서 시작 시점에 진행중 행을 따로 커밋하고,
 * 결과에 따라 성공·실패로 마감한다.
 *
 * <h3>외부 문자열은 정제해 담는다</h3>
 * <p>폴더 이름·데이터셋 식별자는 산출물이 준 값이라 제어문자가 섞일 수 있다. 그대로 담으면 이 표를
 * 읽는 화면과 로그에 그 문자가 그대로 흐른다(CWE-117). 정제는 호출부가 이미 마친 값을 받는다.
 *
 * @design DOMAIN-017
 * @design ERD-031
 */
@Service
@RequiredArgsConstructor
public class ImportHistoryTxService {

    private static final Logger log = LoggerFactory.getLogger(ImportHistoryTxService.class);

    private final LsOtsdDatstTrnsfHstryRepository historyRepository;

    /** 진행중 행을 먼저 남긴다 — 적재가 깨져도 이 행은 남는다. */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Long start(String folderPath, String folderName, String datasetId, String actorId) {
        LsOtsdDatstTrnsfHstry history = LsOtsdDatstTrnsfHstry.start(
                folderPath, folderName, datasetId, actorId);
        return historyRepository.save(history).getTrnsfSn();
    }

    /**
     * 성공 마감 — 영상과 <b>실제 적재 건수</b>를 기록한다(문서 선언값이 아니다 — AC-047).
     *
     * <p>⚠ 이 마감은 적재가 <b>이미 커밋된 뒤</b>에 돈다. 여기서 실패하더라도 호출부는 그것을 적재
     * 실패로 다루지 않는다 — 그렇게 하면 커밋된 행이 가리키는 파일을 지우게 되고 되돌릴 수 없다.
     * 삼키는 자리는 <b>이 트랜잭션 밖</b>이다. 안에서 잡으면 그 트랜잭션에 이미 롤백 표시가 서 있어
     * 경계에서 다시 예외가 난다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void succeed(Long trnsfSn, Long rawSn, int frameCount, int labelCount) {
        historyRepository.findById(trnsfSn).ifPresent(
                history -> history.succeed(rawSn, frameCount, labelCount));
    }

    /**
     * 실패 마감 — 사유를 남긴다.
     *
     * <p>여기서 예외를 올리지 않는다. 마감 실패로 예외가 올라가면 <b>원래의 실패 원인</b>이 그 예외에
     * 가려 무엇 때문에 이관이 깨졌는지 알 수 없게 된다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void failQuietly(Long trnsfSn, String reason) {
        try {
            historyRepository.findById(trnsfSn).ifPresent(history -> history.fail(reason));
        } catch (RuntimeException e) {
            log.warn("[Import] history fail-mark skipped trnsfSn={} cause={}",
                    trnsfSn, e.getClass().getSimpleName());
        }
    }
}
