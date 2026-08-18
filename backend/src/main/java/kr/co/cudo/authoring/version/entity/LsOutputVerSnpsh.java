package kr.co.cudo.authoring.version.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 산출 회차 ↔ 라벨 버전 스냅샷 매핑 (V183) — <b>「산출 회차 N 의 프레임 F 내용은 이 스냅샷이었다」</b>.
 *
 * <h3>왜 컬럼 하나로는 안 되나 (이 테이블의 존재 이유)</h3>
 * {@code LS_LABEL_VERSION.VER_NO} 는 값이 하나뿐이라 <b>한 스냅샷이 여러 회차의 내용일 수 있다</b>
 * (1:N)를 표현하지 못한다. 내용이 바뀌지 않은 회차는 {@code (DATA_SRC_SN, VERSION_HASH)} UNIQUE
 * 때문에 스냅샷이 생기지 않고, 롤백으로 옛 스냅샷을 재활성하면 그 스냅샷이 <b>이후 회차의 내용</b>이
 * 되는데 번호는 처음 확정된 회차 그대로다. 그 상태에서 번호 기반으로 고르면 <b>그 회차에 존재한 적
 * 없는 내용</b>(비활성 스냅샷)으로 되돌아간다 — 예외도 미해결 집계도 없는 조용한 오복원이다.
 *
 * <h3>판정 원천은 이 테이블 하나다</h3>
 * {@code VER_NO} 는 <b>제거하지 않고</b> "그 내용이 처음 산출 내용이 된 회차"로서 조회·표시(영상 단위
 * 버전 목록 · 회차 존재 대조)에 계속 쓰인다. 그러나 <b>어느 스냅샷으로 되돌릴지</b>의 판정은 오직 이
 * 매핑이 소유한다({@code StartVersionService.resolveTargets}). 판정을 두 곳에서 유도하면 갈라진다.
 *
 * <h3>누가 언제 쓰나</h3>
 * 산출 마감 트랜잭션({@code DatasetExportTxService.finalizeUnlessUnderDeidentReport} →
 * {@code OutputVersionStamper.stamp}) 한 곳뿐이며, 그 회차의 <b>ACTIVE 스냅샷 전량</b>을 기록한다
 * (이미 번호가 찍힌 행 포함 — 그게 바로 이 테이블이 없으면 잃는 정보다). 마감과 같은 트랜잭션이라
 * "매핑이 있는 회차 ⇔ 실재하는 산출 폴더"가 원자적으로 유지된다.
 *
 * <h3>등록자를 두지 않는 이유</h3>
 * 유일한 쓰기 지점이 {@code @Async} 산출 마감이라 <b>actor 자체가 없다</b>. 지어내지 않는다 —
 * 사람의 행위(시작 버전 선택)는 {@code LS_TASK_EVNT_LOG} 가 별도로 감사한다.
 *
 * <p>행 생성은 <b>네이티브 INSERT … SELECT</b>({@code LsOutputVerSnpshRepository}) 로만 이뤄진다.
 * 회차당 프레임 수만큼의 엔티티를 힙에 올리지 않기 위함이며(CWE-770), 그래서 이 엔티티에는 생성
 * 팩토리가 없다(읽기 전용 매핑).
 *
 * @design D5
 * @req R6
 */
@Entity
@Table(name = "LS_OUTPUT_VER_SNPSH")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsOutputVerSnpsh {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "OUTPUT_VER_SNPSH_SN")
    private Long outputVerSnpshSn;

    /** 영상 ({@code LS_DATA_RAW.RAW_SN}). */
    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    /** 프레임 ({@code LS_DATA_SRC.SRC_SN}). */
    @Column(name = "DATA_SRC_SN", nullable = false)
    private Long dataSrcSn;

    /** 산출 회차 ({@code LS_DATASET_EXPORT.OUTPUT_VER_NO} = 산출 폴더 {@code v{n}}). */
    @Column(name = "OUTPUT_VER_NO", nullable = false)
    private Integer outputVerNo;

    /**
     * 그 회차의 내용이 된 승인 스냅샷 PK ({@code LS_LABEL_VERSION.LBL_VERSION_SN}).
     *
     * <p>컬럼명은 표준 약어 {@code VER}(버전)를 쓴다 — 참조 대상의 {@code VERSION} 표기는 선존
     * 드리프트이며 신규 컬럼이 이를 미러하지 않는다.
     */
    @Column(name = "LBL_VER_SN", nullable = false)
    private Long lblVerSn;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;
}
