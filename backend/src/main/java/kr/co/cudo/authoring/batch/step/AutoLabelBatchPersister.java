package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 자동 라벨(LS_DATA_LBL) + AI 메타(LS_DATA_LBL_AI_INFO) <b>일괄 저장</b> 공용 헬퍼 (B-ISSUE-42).
 *
 * <p>구 구현은 YOLO({@link YoloAutolabelStep})·SAM2({@link Sam2SegmentStep}) 가 검출 루프 안에서
 * detection 마다 {@code lblRepository.save()} + {@code aiInfoRepository.save()} 를 개별 호출했다
 * ({@code rules/performance.md} — "대량 처리: 1건씩 save 금지 → saveAll() 사용"). 본 헬퍼는 두 경로가
 * 프레임 단위로 모은 라벨을 <b>2회의 saveAll</b>(라벨 → AI 메타)로 저장한다.
 *
 * <p><b>성능 개선 폭에 대한 정직한 한계</b>: {@code LsDataLbl}/{@code LsDataLblAiInfo} 는
 * {@code GenerationType.IDENTITY} 이며 <b>본 변경은 PK 전략을 바꾸지 않는다</b>(사용자 확정 범위).
 * Hibernate 는 IDENTITY 에서 PK 를 즉시 알아야 해 JDBC 배치를 구조적으로 비활성화하므로
 * {@code hibernate.jdbc.batch_size} 는 여전히 이 엔티티들에 적용되지 않는다. 따라서 실익은
 * <b>영속성 컨텍스트/리포지토리 왕복 횟수 감소와 저장 지점 단일화</b>이지 "INSERT 문 묶음" 이 아니다.
 * 실제 JDBC 배치를 원하면 시퀀스 PK 전환이 선행돼야 하나, {@code LBL_SN} 을 참조하는 모듈이 12개 이상
 * (증강 라벨맵·해상도 파생·포털·품질검사·export 해시·버전 롤백의 LBL_SN 보존 복원 등)이라 별건이다.
 *
 * <p><b>PK 매칭 계약 (조용한 데이터 오염 방지)</b>: AI 메타는 대응 라벨의 PK({@code LBL_SN})를 가져야
 * 한다. {@code saveAll} 반환 목록은 입력 순서를 보존하고 IDENTITY 전략에서 각 원소는 PK 가 부여된
 * <b>입력 인스턴스 그 자체</b>이므로, 본 헬퍼는 저장된 라벨을 순서대로 순회하며 그 라벨에서 직접
 * {@code lblSn}/{@code srcSn} 을 읽어 AI 메타를 만든다. 크기가 어긋나면(계약 위반) 잘못된 라벨에 AI
 * 메타가 붙는 대신 즉시 실패시킨다.
 */
public final class AutoLabelBatchPersister {

    private AutoLabelBatchPersister() {
    }

    /**
     * 저장 대기 자동 라벨 1건 — 라벨 엔티티 + AI 메타에 기록할 신뢰도.
     *
     * <p>신뢰도를 따로 들고 다니는 이유: {@code LsDataLbl} 은 생성 시 {@code clampScore} 로 값을
     * 보정하므로, 엔티티에서 되읽으면 AI 메타에 적재되던 원본 신뢰도와 달라질 수 있다(기존 동작 보존).
     */
    public record PendingLabel(LsDataLbl label, BigDecimal score) {
    }

    /**
     * 모아둔 자동 라벨을 라벨 → AI 메타 순서로 일괄 저장한다.
     *
     * @param lblRepository    라벨 리포지토리 (호출자 트랜잭션/모킹 컨텍스트 유지를 위해 주입받는다)
     * @param aiInfoRepository AI 메타 리포지토리
     * @param pending          저장 대기 목록. 비어 있으면 리포지토리를 호출하지 않는다.
     * @param rawSn            영상 PK (AI 메타 DATA_RAW_SN)
     * @param lblSrcCd         AI 메타 출처 코드 ({@code LsDataLblAiInfo.SRC_YOLO}/{@code SRC_SAM2})
     * @param source           AI 메타 REG_ID 출처 마커
     * @return 저장된 라벨 건수
     */
    public static int saveAll(LsDataLblRepository lblRepository,
                              LsDataLblAiInfoRepository aiInfoRepository,
                              List<PendingLabel> pending,
                              Long rawSn, String lblSrcCd, String source) {
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        List<LsDataLbl> entities = new ArrayList<>(pending.size());
        for (PendingLabel p : pending) {
            entities.add(p.label());
        }
        List<LsDataLbl> saved = lblRepository.saveAll(entities);
        if (saved == null || saved.size() != pending.size()) {
            // 계약 위반 — 인덱스 대응이 깨지면 AI 메타가 엉뚱한 라벨에 붙는 조용한 오염이 된다.
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "자동 라벨 일괄 저장 결과 개수 불일치");
        }
        List<LsDataLblAiInfo> infos = new ArrayList<>(saved.size());
        for (int i = 0; i < saved.size(); i++) {
            LsDataLbl label = saved.get(i);
            infos.add(LsDataLblAiInfo.create(
                    label.getLblSn(), rawSn, label.getSrcSn(), lblSrcCd, pending.get(i).score(), source));
        }
        aiInfoRepository.saveAll(infos);
        return saved.size();
    }
}
