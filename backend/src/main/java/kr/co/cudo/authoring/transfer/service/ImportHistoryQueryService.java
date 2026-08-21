package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.transfer.dto.ImportHistoryDetailResponse;
import kr.co.cudo.authoring.transfer.dto.ImportHistoryItemResponse;
import kr.co.cudo.authoring.transfer.entity.LsOtsdDatstTrnsfHstry;
import kr.co.cudo.authoring.transfer.repository.LsOtsdDatstTrnsfHstryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 이관 이력 조회 — 목록(API-207)과 상세(API-208).
 *
 * <h3>정렬은 시간순 하나다</h3>
 * <p>상태를 정렬 우선순위로 섞지 않는다. "지금 볼 것"은 정렬이 아니라 걸러내기가 맡는다(DFEAT-059,
 * 그리고 이 저장소의 목록 화면 공통 규약). 정렬 키는 <b>서버가 고정</b>하며 요청이 고르지 못한다.
 *
 * <h3>★ 승인 보류 여부는 이관 상태와 다른 축이다</h3>
 * <p>이관 상태는 가져오는 일이 어떻게 끝났는가이고, 승인 보류는 그렇게 만들어진 영상이 지금 검수
 * 승인을 받을 수 있는가이다. <b>같은 이관 상태(성공)의 두 영상이 서로 다른 보류 값을 가질 수 있다</b>
 * — 원본이라고 지정해 가져온 영상만 보류가 서기 때문이다. 이관 상태로 대신 판단하면 보류가 선 영상을
 * 골라 비식별 완료를 기록하는 자리가 화면에서 도달 불가가 된다.
 *
 * <p><b>보류가 서는 조건은 여기서 다시 정하지 않는다</b> — 판정은 검수 워크플로 상태
 * ({@link LsRawDataStatus#isDeidentCompleted()})가 소유하고 이 서비스는 그 값을 읽어 뒤집어 실을 뿐이다.
 * 조건을 이 통로에 옮겨 적으면 판정이 두 벌이 되어 한쪽만 갱신될 때 어긋난다.
 *
 * <h3>★ 항목마다 상태를 읽지 않는다 (N+1)</h3>
 * <p>한 쪽에 담긴 영상 식별번호를 <b>모아 한 번에</b> 조회한다. 항목마다 읽으면 조회 수가 목록 크기에
 * 비례해 늘어난다. 이 목록은 산출물을 가져올수록 단조 증가하므로 그 비용이 계속 커진다.
 *
 * <h3>영상이 없는 이력도 그대로 돌려준다</h3>
 * <p>실패한 이관에는 영상이 없고, 영상이 지워진 이력도 참조가 비워진 채 남는다(이력은 감사 기록이라
 * 함께 지우지 않는다). 그런 항목의 보류 값은 <b>비어 있음</b>이며 예외가 아니다 — 거기서 예외를 내면
 * 실패 경위를 되짚으려고 여는 화면이 바로 그 실패 때문에 열리지 않는다.
 *
 * @design DOMAIN-017
 * @design API-207
 * @design API-208
 * @design DFEAT-059
 * @design ERD-031
 */
@Service
@RequiredArgsConstructor
public class ImportHistoryQueryService {

    /**
     * 최근순 — 등록일시 내림차순. 같은 시각이 여러 건일 때 쪽 사이에서 순서가 흔들리지 않도록
     * 식별번호를 두 번째 기준으로 둔다(기준이 하나뿐이면 같은 항목이 두 쪽에 나오거나 빠진다).
     */
    private static final Sort RECENT_FIRST =
            Sort.by(Sort.Order.desc("regDt"), Sort.Order.desc("trnsfSn"));

    private final LsOtsdDatstTrnsfHstryRepository historyRepository;
    private final LsRawDataStatusRepository statusRepository;

    /**
     * 이관 이력을 최근순 한 쪽으로 돌려준다.
     *
     * @param status 이관 상태 필터. 비우면 전체
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Page<ImportHistoryItemResponse> list(String status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, RECENT_FIRST);
        Page<LsOtsdDatstTrnsfHstry> rows = (status == null || status.isBlank())
                ? historyRepository.findAll(pageable)
                : historyRepository.findByTrnsfSttsCd(status, pageable);

        Map<Long, Boolean> approvalHeld = approvalHeldByRawSn(rows.getContent());
        return rows.map(history -> ImportHistoryItemResponse.from(
                history, approvalHeldOf(approvalHeld, history.getRawSn())));
    }

    /**
     * 이관 한 건의 상세.
     *
     * @throws CustomException 그 이력이 없으면 NOT_FOUND
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public ImportHistoryDetailResponse detail(long trnsfSn) {
        return historyRepository.findById(trnsfSn)
                .map(ImportHistoryDetailResponse::from)
                .orElseThrow(() -> new CustomException(
                        ErrorCode.NOT_FOUND, "이관 이력을 찾을 수 없습니다."));
    }

    /**
     * 이 쪽에 담긴 영상들의 승인 보류 여부를 <b>한 번의 조회</b>로 모은다.
     *
     * <p>보류 여부는 검수 워크플로 상태가 가진 판정을 <b>뒤집은 것</b>이다 — 승인이 가능하지 않으면
     * 보류가 서 있다. 그 판정 자체(어떤 컬럼이 어떤 값일 때 승인이 가능한가)는 그 층이 소유한다.
     */
    private Map<Long, Boolean> approvalHeldByRawSn(List<LsOtsdDatstTrnsfHstry> rows) {
        Set<Long> rawSns = new LinkedHashSet<>();
        for (LsOtsdDatstTrnsfHstry history : rows) {
            if (history.getRawSn() != null) {
                rawSns.add(history.getRawSn());
            }
        }
        if (rawSns.isEmpty()) {
            return Map.of();
        }
        Map<Long, Boolean> held = new HashMap<>();
        for (LsRawDataStatus status : statusRepository.findByRawDataIdIn(rawSns)) {
            held.put(status.getRawDataId(), !status.isDeidentCompleted());
        }
        return held;
    }

    /**
     * 영상이 없거나 그 영상의 작업 상태 행이 없으면 <b>비어 있음</b>이다.
     *
     * <p>없는 것을 "보류 없음"으로 단정하지 않는다 — 그러면 화면이 보류를 풀 자리를 감춘다.
     */
    private static Boolean approvalHeldOf(Map<Long, Boolean> held, Long rawSn) {
        return rawSn == null ? null : held.get(rawSn);
    }
}
