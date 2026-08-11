package kr.co.cudo.authoring.version.service;

import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.version.repository.LsOutputVerSnpshRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * VER_NO 실채번 — 산출 버전 번호({@code LS_DATASET_EXPORT.OUTPUT_VER_NO})를 승인 스냅샷
 * ({@code LS_LABEL_VERSION.VER_NO})에 찍는 <b>단일 적용 지점</b>.
 *
 * <h3>번호를 어디서 읽어 어디에 쓰나 (Critical)</h3>
 * 번호를 새로 만들지 않는다 — <b>산출 원장의 번호를 그대로 따른다</b>(두 번째 진실원 금지).
 * 검수 승인 트랜잭션({@code VersionService.commitApproved}) 시점에는 그 번호를 <b>알 수 없다</b>:
 * 채번은 {@code DatasetExportTxService.insertNextVersion}(승인 커밋 이후 {@code @Async} export 안,
 * {@code countByDataRawSn + 1} + UK 충돌 재채번)에서 비로소 일어나고, 산출은 실패·차단될 수도 있다.
 * 그래서 <b>산출 성공/부분 마감과 같은 트랜잭션</b>에서 찍어
 * "번호가 찍힌 스냅샷 ⇔ 실재하는 산출 폴더"를 원자적으로 유지한다.
 *
 * <h3>왜 ACTIVE + 미채번 행만인가</h3>
 * <ul>
 *   <li><b>ACTIVE</b> — 프레임마다 정확히 1건이며, 그 행이 곧 <b>이번 산출이 만들어낸 내용</b>이다
 *       (승인 스냅샷은 승인 시점 작업본에서 떴고 export 도 같은 작업본에서 뜬다).</li>
 *   <li><b>미채번({@code VER_NO IS NULL})</b> — 내용이 바뀌지 않아 이번 회차에 새 스냅샷이 생기지 않은
 *       프레임은 ACTIVE 행이 이전 회차 번호를 그대로 갖는다. 덮어쓰면 "그 내용이 처음 확정된 회차"를
 *       잃어, 조회 규칙({@code VER_NO <= N} 중 최대)이 <b>더 낮은 시작 버전을 복원하지 못하게</b> 된다.
 *       즉 이 컬럼의 의미는 <b>"이 스냅샷이 산출 내용이 된 첫 회차"</b> 다.</li>
 * </ul>
 *
 * <h3>번호만으로는 부족하다 — 회차↔스냅샷 매핑을 함께 기록한다 (V183)</h3>
 * 위 "미채번 행만" 규칙 때문에, <b>롤백으로 옛 스냅샷을 다시 ACTIVE 로 만든 뒤 재승인·재산출</b>하면
 * 그 회차의 실제 내용은 옛 스냅샷인데 번호는 이미 찍혀 있어 새로 찍히지 않는다. 그러면 번호 기반
 * 조회는 <b>그 사이 회차의 (비활성) 스냅샷</b>을 골라 <b>그 회차에 존재한 적 없는 내용</b>으로 되돌린다
 * — 예외도 미해결 집계도 없는 조용한 오복원이다. 회차↔스냅샷은 <b>1:N</b> 이라 단일 컬럼으로 표현할
 * 수 없으므로, 이 지점에서 <b>ACTIVE 전량</b>(이미 번호가 찍힌 행 포함)의 대응을
 * {@code LS_OUTPUT_VER_SNPSH} 에 함께 남긴다.
 *
 * <p>두 쓰기의 역할이 다르다 — <b>{@code VER_NO} = 그 내용이 처음 산출 내용이 된 회차</b>(조회·표시),
 * <b>매핑 = 어느 회차의 내용이 어느 스냅샷이었는가</b>(되돌릴 대상 판정의 <b>단일 원천</b>).
 * 둘 다 이 트랜잭션에서 함께 커밋되거나 함께 롤백된다.
 *
 * <h3>잠금 순서</h3>
 * 이 UPDATE 는 산출 마감 트랜잭션(신고 재판정으로 {@code LS_DATA_RAW} 를 잠근 상태) 안에서 돌아
 * <b>RAW → LS_LABEL_VERSION</b> 간선을 만든다. 반대 방향(VERSION 을 쥔 채 RAW 행 락을 요구)으로
 * 잠그는 경로는 없다 — 롤백·승인 스냅샷 경로는 {@code LS_DATA_RAW} 를 <b>잠그지 않고</b> 읽는다
 * ({@code findById}). 이 대칭이 깨지면 40P01 이 성립하므로 VERSION 을 잡은 뒤 RAW 를 잠그는 코드를
 * 새로 만들지 말 것.
 *
 * @design D5
 * @req R6
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutputVersionStamper {

    private final LsLabelVersionRepository labelVersionRepository;
    /** 회차↔스냅샷 매핑(V183) — 되돌릴 대상 판정의 단일 원천. */
    private final LsOutputVerSnpshRepository outputVerSnpshRepository;

    /**
     * 이 산출 회차를 승인 스냅샷에 확정한다(멱등) — ①미채번 ACTIVE 행에 번호를 찍고
     * ②그 회차의 <b>ACTIVE 전량</b>에 대해 회차↔스냅샷 매핑을 남긴다.
     *
     * <p>호출자의 트랜잭션에서 실행된다 — 산출 마감(SUCCEEDED/PARTIAL)과 <b>같이 커밋되거나 같이
     * 롤백</b>되어야 하기 때문이다. ①의 대상이 0건이어도 정상이다(이번 회차에 내용이 바뀐 프레임이
     * 없음). 그 경우에도 ②는 기록된다 — "이 회차의 내용은 그 스냅샷들이었다"는 사실은 내용 변경
     * 여부와 무관하게 참이고, 그것이 바로 번호만으로는 잃던 정보다.
     */
    public void stamp(long rawSn, int outputVerNo) {
        int stamped = labelVersionRepository.stampOutputVersionNo(
                rawSn, outputVerNo, LsLabelVersion.ACTIVE_YES);
        int mapped = outputVerSnpshRepository.recordActiveSnapshots(
                rawSn, outputVerNo, LsLabelVersion.ACTIVE_YES);
        // 식별자·건수만 남긴다(라벨 본문·경로 미출력 — CWE-359).
        log.info("[Version] stamped output version rawSn={} versionNo={} snapshots={} mapped={}",
                rawSn, outputVerNo, stamped, mapped);
    }
}
