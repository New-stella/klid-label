package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 자동 라벨(LS_DATA_LBL) <b>일괄 저장</b> 공용 헬퍼 (B-ISSUE-42).
 *
 * <p>구 구현은 YOLO({@link YoloAutolabelStep})·SAM2({@link Sam2SegmentStep}) 가 검출 루프 안에서
 * detection 마다 {@code lblRepository.save()} + {@code aiInfoRepository.save()} 를 개별 호출했다
 * ({@code rules/performance.md} — "대량 처리: 1건씩 save 금지 → saveAll() 사용"). 본 헬퍼는 두 경로가
 * 프레임 단위로 모은 라벨을 <b>1회의 saveAll</b> 로 저장한다.
 *
 * <p><b>V6</b> — 생산이력이 같은 행의 컬럼이 되어 두 번째 saveAll(AI 메타)과 그에 딸린 PK 매칭 계약이
 * 통째로 사라졌다. 아래 「PK 매칭 계약」 문단이 막던 <b>조용한 오염</b>(인덱스가 어긋나 엉뚱한 라벨에
 * AI 메타가 붙는 것)은 이제 구조적으로 불가능하다 — 값을 그 라벨 인스턴스에 직접 넣기 때문이다.
 *
 * <p><b>성능 개선 폭에 대한 정직한 한계</b>: {@code LsDataLbl} 은
 * {@code GenerationType.IDENTITY} 이며 <b>본 변경은 PK 전략을 바꾸지 않는다</b>(사용자 확정 범위).
 * Hibernate 는 IDENTITY 에서 PK 를 즉시 알아야 해 JDBC 배치를 구조적으로 비활성화하므로
 * {@code hibernate.jdbc.batch_size} 는 여전히 이 엔티티들에 적용되지 않는다. 따라서 실익은
 * <b>영속성 컨텍스트/리포지토리 왕복 횟수 감소와 저장 지점 단일화</b>이지 "INSERT 문 묶음" 이 아니다.
 * 실제 JDBC 배치를 원하면 시퀀스 PK 전환이 선행돼야 하나, {@code LBL_SN} 을 참조하는 모듈이 12개 이상
 * (증강 라벨맵·해상도 파생·포털·품질검사·export 해시·버전 롤백의 LBL_SN 보존 복원 등)이라 별건이다.
 *
 */
public final class AutoLabelBatchPersister {

    private AutoLabelBatchPersister() {
    }

    /**
     * 저장 대기 자동 라벨 1건 — 라벨 엔티티 + 적재할 신뢰도.
     *
     * <p>흡수 전에는 신뢰도를 따로 들고 다녔다(엔티티는 {@code clampScore} 를 거친 값을, AI 메타는
     * 호출자가 넘긴 원본을 각각 보유). 흡수 후에는 한 컬럼이라 <b>여기서 받은 값이 곧 적재값</b>이며,
     * {@code applyAiSource} 가 같은 범위 검증을 거쳐 넣는다.
     */
    public record PendingLabel(LsDataLbl label, BigDecimal score) {
    }

    /**
     * 모아둔 자동 라벨을 라벨 → AI 메타 순서로 일괄 저장한다.
     *
     * <p>⚠ 구 시그니처의 {@code rawSn}·{@code source} 인자는 <b>받지 않는다</b>. 둘 다 AI 메타 행의
     * 컬럼({@code DATA_RAW_SN}·{@code REG_ID})을 채우기 위한 값이었는데, 영상은 라벨의 프레임으로
     * 이미 도달 가능해 흡수 대상이 아니었고 {@code REG_ID} 는 감사 컬럼이라 옮기지 않았다.
     * 쓰이지 않는 인자를 남겨두면 다음 사람이 "어딘가 기록되겠거니" 하고 믿는다.
     *
     * @param lblRepository 라벨 리포지토리 (호출자 트랜잭션/모킹 컨텍스트 유지를 위해 주입받는다)
     * @param pending       저장 대기 목록. 비어 있으면 리포지토리를 호출하지 않는다.
     * @param lblSrcCd      라벨 출처 코드 ({@code LsDataLbl.SRC_YOLO}/{@code SRC_SAM2})
     * @return 저장된 라벨 건수
     */
    public static int saveAll(LsDataLblRepository lblRepository,
                              List<PendingLabel> pending,
                              String lblSrcCd) {
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        List<LsDataLbl> entities = new ArrayList<>(pending.size());
        for (PendingLabel p : pending) {
            p.label().applyAiSource(lblSrcCd, p.score());
            entities.add(p.label());
        }
        List<LsDataLbl> saved = lblRepository.saveAll(entities);
        if (saved == null || saved.size() != pending.size()) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "자동 라벨 일괄 저장 결과 개수 불일치");
        }
        return saved.size();
    }
}
