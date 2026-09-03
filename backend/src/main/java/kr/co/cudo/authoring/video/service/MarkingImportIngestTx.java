package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.dto.MarkingImportIngestCommand;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.event.VideoIngestedEvent;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마킹 이관 영상 <b>1건</b> 적재의 트랜잭션 경계 빈 — {@code LS_DATA_RAW} 를 만들고 비식별 선두
 * 파이프라인을 트리거한다(ADR-053 · SEQ-030).
 *
 * <h3>관제 인입 경로와 <b>섞지 않는다</b></h3>
 * <p>{@link TrainingVideoIngestTx} 는 관제가 {@code LS_DATA_INGEST} 에 INSERT 한 행을 원자 클레임해
 * 옮기는 <b>상태머신</b>이다(클레임 → 재조회 → 종결 전이). 이 경로에는 그 원장 행 자체가 없다 —
 * 인입 원장은 "관제가 무엇을 보냈는가"의 기록이라 저작도구가 자기 판단으로 행을 넣으면 관제가 보낸
 * 것과 우리가 넣은 것을 나중에 구분할 수 없다(ADR-048 이 라벨링 완료 갈래에 정한 규칙과 같다).
 * 그래서 그 클래스에 메서드를 얹지 않고 <b>빈을 나눈다</b>. 상태머신 규약(클레임이 최상단 · 종결
 * 전이 · 미도착 backoff)이 통째로 적용되지 않는 경로를 그 안에 두면 규약이 조건부가 된다.
 *
 * <h3>이 경로가 <b>비식별 선두 단계를 탄다</b></h3>
 * <p>받은 영상은 비식별되지 않은 원본이고 <b>영상 파일을 우리가 갖고 있다</b>. EVT-005 가 이관 경로를
 * 발행 제외로 열거하면서 명시한 <b>예외</b>가 정확히 이 경우다 — "원본이라고 지정하고 영상 파일을
 * 함께 가져온 산출물은 저작도구가 비식별할 대상 영상을 가지고 있으므로 본 이벤트 축의 비식별 단계를
 * 그 영상에 태운다".
 * <p>발행은 <b>이 트랜잭션 안</b>에서 한다 — 수신 배선({@code IngestDeidentifyBridge} ·
 * {@code VideoMetaExtractBridge})이 {@code AFTER_COMMIT} 이라 트랜잭션 밖에서 발행하면 아무도 받지
 * 못한다. 그 두 수신자는 서로 독립이라 기술메타 추출 실패가 비식별을 막지 않는다.
 *
 * <h3>중복 식별자는 실패가 아니라 <b>건너뜀</b>이다</h3>
 * <p>사전 조회는 {@link TrainingVideoIngestService#ingestMarkingImport} 가 <b>트랜잭션 밖에서</b>
 * 하고, 그 창을 빠져나간 동시 적재는 {@code UK_LS_DATA_RAW_VMS_CLIP} 위반으로 여기서 드러난다.
 * <b>여기서 잡지 않는다</b> — PostgreSQL 은 제약 위반 시 트랜잭션 전체를 abort 하므로 잡아서 값을
 * 돌려주면 커밋 시점에 {@code UnexpectedRollbackException} 이 대신 튀어나온다. 예외를 그대로 올려
 * 호출부가 <b>롤백된 뒤</b> 판정하게 한다.
 *
 * @design ADR-053
 * @design DFEAT-060
 * @design SEQ-030
 * @design EVT-005
 * @design AC-1032
 * @design AC-1033
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkingImportIngestTx {

    private final VideoRepository videoRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 마킹 이관 영상 1건을 독립(REQUIRES_NEW) 트랜잭션으로 적재한다.
     *
     * <p>독립 트랜잭션인 이유는 <b>부분 실패 격리</b>다 — 한 건의 제약 위반이 rollback-only 를 남겨도
     * 같은 일괄의 다른 건 커밋을 오염시키지 않는다(SEQ-030: 백 건을 한 트랜잭션에 묶으면 한 건 때문에
     * 전부 되돌아간다).
     *
     * @return 적재된 영상 식별자
     * @throws org.springframework.dao.DataIntegrityViolationException
     *         {@code VMS_CLIP_ID} 유일 제약 위반 — 호출부가 <b>트랜잭션 밖</b>에서 받아 중복 건너뜀으로
     *         마감한다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, transactionManager = "controlTransactionManager")
    public long persist(MarkingImportIngestCommand command) {
        LsDataRaw raw = LsDataRaw.createFromMarkingImport(
                command.vmsClipId(),
                command.vmsCctvId(),
                command.evntTypeCd(),
                command.lclgvCd(),
                command.prvcTypeCd(),
                command.rawFilePathNm(),
                command.shtDt());
        // ★ 유일 제약 위반을 <여기서> 드러나게 한다. 커밋까지 미루면 트랜잭션 경계에서
        //   UnexpectedRollbackException 으로 바뀌어 나와 중복과 다른 실패를 가릴 수 없다.
        LsDataRaw saved = videoRepository.saveAndFlush(raw);
        Long rawSn = saved.getRawSn();
        // ★ 비식별 선두 파이프라인 + 기술메타 추출 트리거(AFTER_COMMIT).
        //   빠지면 비식별이 아예 돌지 않아 원본 PII 가 그대로 남는다(CWE-359).
        eventPublisher.publishEvent(new VideoIngestedEvent(rawSn));
        // 식별자(수치)만 남긴다 — 외부 자유텍스트·파일경로는 로그에 넣지 않는다(CWE-117 / CWE-359).
        log.info("[MarkingImport] ingested rawSn={}", rawSn);
        return rawSn;
    }
}
