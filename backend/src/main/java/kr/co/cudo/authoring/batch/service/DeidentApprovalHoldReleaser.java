package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 저작도구가 태운 비식별이 <b>성공으로 기록되는 지점</b>에서 검수 승인 보류를 푸는 단일 지점.
 *
 * <h3>무엇을 푸는가</h3>
 * <p>외부 산출물을 <b>원본이라고 지정해</b> 이관한 영상은 적재 시점에
 * {@code LS_RAW_DATA_STATUS.DE_IDNTF_CMPTN_YN} 이 {@code 'N'} 으로 서서 <b>검수 승인만</b> 막힌다.
 * 그 보류를 푸는 갈래는 둘인데, <b>영상 파일 없이 프레임만</b> 이관한 갈래는 외부 비식별 산출물을
 * 받아 기록하는 별도 통로가 이미 담당한다. 이 클래스는 나머지 한 갈래 —
 * <b>영상 파일을 함께 준 원본</b>이라 저작도구가 자기 비식별 단계를 태운 경우 — 를 담당한다.
 *
 * <h3>왜 별도 클래스인가</h3>
 * <p>"비식별이 성공으로 기록됐는가"라는 판정을 호출처마다 복제하지 않기 위해서다. 판정과 멱등
 * 규칙을 여기 한 곳에 두고, 성공을 기록하는 쪽은 이 메서드를 부르기만 한다.
 *
 * <h3>트랜잭션 — 자체 트랜잭션을 열지 않는다</h3>
 * <p>{@code @Transactional} 을 붙이지 않아 <b>호출자의 트랜잭션에 그대로 참여</b>한다. 성공 기록
 * ({@code DE_IDENT_YN='Y'} + 배치 단계 {@code MARKING_READY})과 <b>같은 트랜잭션</b>에서 커밋되거나
 * 함께 롤백돼야 하기 때문이다. 별도 {@code REQUIRES_NEW} 로 열면 성공 기록이 롤백된 뒤에도 보류만
 * 풀린 채 남아, 처리되지 않은 산출물이 검수 승인을 통과한다(ADR-048 이 명시한 위험).
 *
 * <h3>단방향 — 되돌리지 않는다</h3>
 * <p>{@code 'N'} → {@code 'Y'} 만 수행한다. 반대 방향({@code 'Y'} → {@code 'N'})은 이 클래스에 없다.
 * 그 방향을 만들면 이 경로와 무관한 기존 영상의 승인을 뒤늦게 막을 수 있다.
 *
 * <h3>잠금 순서</h3>
 * <p>여기서 만지는 {@code LS_RAW_DATA_STATUS} 와 호출자가 만지는 {@code LS_DATA_RAW} 는 둘 다
 * dirty checking 으로 <b>커밋 시점에</b> flush 되므로, DB 잠금 순서는 코드 순서가 아니라 Hibernate 의
 * flush 순서({@code order_updates=true} — 엔티티명 정렬이라 {@code assignment.LsRawDataStatus} 가
 * {@code video.LsDataRaw} 보다 앞선다)가 정한다. 즉 배치가 쓰는 <b>status → raw</b> 순서와 같은 방향이라
 * {@code BatchTransitionService} 와 순환 대기가 생기지 않는다. 더구나 아래 멱등 가드 때문에 이 경로가
 * 실제로 {@code LS_RAW_DATA_STATUS} 를 <b>쓰는</b> 것은 보류가 서 있는 이관 영상뿐이다.
 *
 * @design ADR-048
 * @design AC-046
 * @design ERD-015
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeidentApprovalHoldReleaser {

    /**
     * 비식별 성공을 뜻하는 유일한 값.
     *
     * <p>{@code 'N'}(미수행)·{@code 'F'}(실패 또는 비식별 누락 신고)는 성공이 아니다. 특히 {@code 'F'}
     * 에서 보류를 풀면 마스킹이 실패한 산출물이 검수 승인을 통과한다.
     */
    private static final String DEIDENT_SUCCEEDED = "Y";

    private final LsRawDataStatusRepository rawDataStatusRepository;

    /**
     * 비식별 성공 기록에 붙여 승인 보류를 푼다 — <b>멱등</b>.
     *
     * <p>다음 세 경우에는 아무것도 하지 않는다.
     * <ul>
     *   <li><b>영상이 성공 상태가 아니다</b> — {@code DE_IDENT_YN} 이 {@code 'Y'} 가 아니면 푸는 대신
     *       경고만 남긴다. 호출자가 성공 기록 <b>이전</b>에 부르거나, 산출물이 실재하지 않는데 완료로
     *       응답한 거짓 성공을 뒤늦게 태우는 경우를 이 한 줄이 막는다(fail-closed).</li>
     *   <li><b>작업 상태 행이 아직 없다</b> — 관제 인입 영상은 그 행이 배정 시점에 생기므로 비식별
     *       완료 시점에는 없을 수 있다. 나중에 생기는 행은 기본값이 완료라 보류가 서지 않는다.</li>
     *   <li><b>이미 완료로 기록돼 있다</b> — 이 경로와 무관한 <b>기존 전 영상</b>이 여기 해당한다.
     *       값을 다시 쓰지 않으므로 UPDATE 도 낙관적 잠금 버전 증가도 일어나지 않는다.</li>
     * </ul>
     *
     * @param raw 성공을 기록한 <b>바로 그</b> 영상 엔티티(호출자의 영속 컨텍스트에 관리되는 인스턴스)
     */
    public void releaseOnDeidentSuccess(LsDataRaw raw) {
        if (raw == null || raw.getRawSn() == null) {
            return;
        }
        Long rawSn = raw.getRawSn();
        if (!DEIDENT_SUCCEEDED.equals(raw.getDeIdntfYn())) {
            log.warn("[DeidentHold] approval hold kept — deident not succeeded rawSn={}", rawSn);
            return;
        }
        rawDataStatusRepository.findById(rawSn).ifPresent(status -> {
            if (status.isDeidentCompleted()) {
                return;
            }
            status.markDeidentCompleted();
            log.info("[DeidentHold] approval hold released rawSn={}", rawSn);
        });
    }
}
