package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.portal.dto.PortalDatasetCleanupTriggerRequest;
import kr.co.cudo.authoring.portal.repository.LsDatstArngmtTrgrRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포털 정리 삭제 트리거 접수 — <b>받아서 기산점을 남기는 데까지</b>가 이 서비스의 전부다.
 *
 * <h3>★ 여기서 지우지 않는다 (지연을 없애지 말 것)</h3>
 * <p>트리거를 받아도 <b>즉시 지우지 않는다.</b> 사용자가 그 해제본을 물고 저작하는 중일 수 있고,
 * 받자마자 지우면 그 작업이 그 자리에서 깨진다. 그래서 받은 시점을 남겨 두고 유예를 센다.
 * ⚠ 「불필요한 지연」으로 보아 없애지 말 것 — 이유가 있는 지연이다.
 *
 * <h3>★ 보존 규칙의 정본은 여기가 아니다</h3>
 * <p>유예 기간(일수)·기산점 규칙·정리 절차는 포털 작업 데이터 보존기간 만료 자동 삭제 기능
 * (DFEAT-055)이 소유한다. 이 클래스에 일수를 상수로 박지 말 것 — 정리를 실행하는 쪽이 그 규칙의
 * 소유자이고, 여기에 복제하면 한쪽만 갱신돼 어긋난다.
 *
 * <h3>★ 재수신은 성공 무처리다</h3>
 * <p>같은 (데이터셋 코드, 버전) 이 다시 오면 아무것도 하지 않는다. 기산점을 갱신하면 중복 수신이
 * 반복될 때마다 정리가 무한히 미뤄져 <b>영영 일어나지 않는다.</b> 판정은 이 서비스가 조회로 하지
 * 않고 저장소의 {@code ON CONFLICT DO NOTHING} 실행문이 한다 — 동시 도착에서도 성립해야 한다.
 *
 * <h3>★ 알지 못하는 데이터셋도 그대로 접수한다</h3>
 * <p>이 배포본에 해제된 적 없는 데이터셋인지 <b>확인하지 않는다.</b> 오류로 답하면 포털이 지울
 * 것도 없는 신호를 계속 재전송하고, 대상 유무를 응답으로 드러내면 존재 여부를 탐색하는 수단이 된다.
 * 그래서 존재 확인 조회 자체를 두지 않는다.
 *
 * @design API-244
 * @design ERD-035
 * @design AC-1102
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalDatasetCleanupTriggerService {

    private final LsDatstArngmtTrgrRepository triggerRepository;

    /**
     * 트리거를 접수한다. 첫 수신이든 재수신이든 예외 없이 정상 종료한다 — 창구는 같은 응답을 낸다.
     */
    @Transactional(transactionManager = "controlTransactionManager")
    public void receive(PortalDatasetCleanupTriggerRequest request) {
        int inserted = triggerRepository.insertIfAbsent(request.datasetCode(), request.version());

        // 진단 로그 — 첫 수신과 재수신을 구분해 남긴다. ★ 이 구분이 응답으로 새지 않는다는 것이
        //   요점이고, 운영자는 재전송이 도는지 알아야 하므로 로그에서는 구분한다.
        // ⚠ 값은 외부 입력이라 그대로 찍으면 로그 인젝션(CWE-117)이다 — 반드시 정제한다.
        //   토큰·헤더는 애초에 이 계층에 오지 않으므로 남길 것이 없다.
        if (inserted > 0) {
            log.info("[PortalDatasetCleanup] 정리 트리거 접수 datasetCode={} version={}",
                    LogSanitizer.sanitize(request.datasetCode()),
                    LogSanitizer.sanitize(request.version()));
        } else {
            log.info("[PortalDatasetCleanup] 정리 트리거 재수신 — 기산점을 갱신하지 않는다 "
                            + "datasetCode={} version={}",
                    LogSanitizer.sanitize(request.datasetCode()),
                    LogSanitizer.sanitize(request.version()));
        }
    }
}
