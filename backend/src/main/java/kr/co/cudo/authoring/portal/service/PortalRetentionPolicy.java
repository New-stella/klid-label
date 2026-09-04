package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.OptionalInt;

/**
 * 포털 보존기간 만료 예정 시각의 <b>단일 판정 지점</b>. @design DFEAT-055, AC-1068, AC-033
 *
 * <h3>저장하지 않는다 — 조회 시점 파생값이다</h3>
 * <p>AC-033 이 이 값을 <b>조회 시점 설정값 기준 파생값</b>으로 못박았다. 보존기간을 7일에서 14일로
 * 바꾸면 <b>이미 저장된 라벨의 만료 예정도 다음 조회부터 즉시</b> 달라져야 하므로, 엔티티 컬럼으로
 * 굳히거나 계산 결과를 캐시하지 않는다(마이그레이션 대상 아님). 설정값 자체는
 * {@code SystemConfigService} 의 Caffeine 캐시(TTL 60s)를 타지만 그 캐시는 설정 갱신 시
 * {@code allEntries=true} 로 비워지므로 즉시 반영이 성립한다.
 *
 * <h3>기준점이 축마다 다르다 — 한쪽 축의 확정을 다른 축으로 옮기지 말 것</h3>
 * <table>
 *   <caption>보존기간 기준점</caption>
 *   <tr><th>축</th><th>기준점</th><th>설정 키</th></tr>
 *   <tr><td>데이터마트 라벨</td><td>그 (사용자, 영상) 본인 저장 라벨의 {@code MIN(REG_DT)} — <b>최초</b> 저장 시각</td>
 *       <td>{@code portal.datamart.retention-days}</td></tr>
 *   <tr><td>업로드 {@code READY}</td><td>자산 {@code REG_DT} 와 그 자산 라벨 {@code MAX(REG_DT)} 중 <b>늦은 쪽</b></td>
 *       <td>{@code portal.upload.retention-days}</td></tr>
 *   <tr><td>업로드 {@code FAILED}</td><td>{@code MDFCN_DT}(FAILED 전이 시각)</td>
 *       <td>{@code portal.upload.failed-retention-days}</td></tr>
 *   <tr><td>업로드 {@code PROCESSING}·{@code UPLOADED}</td><td colspan="2">만료 없음 — {@code null}</td></tr>
 * </table>
 * <p>업로드 두 축은 <b>독립 판정</b>이다(AC-037 and_examples[1]) — 같은 시각에 등록된 READY 자산과
 * FAILED 자산은 서로 다른 보존기간으로 계산된다.
 *
 * <p>★ <b>데이터마트만 「최초」이고 업로드 READY 는 「늦은 쪽」이다 — 통일하지 말 것.</b> 포털 확정
 * 회신(2026-09-03)이 못박은 것은 <b>데이터마트 채널의 기산점뿐</b>이고, 두 채널의 기산점이 같은지는
 * 언급이 없다(DFEAT-055). 「일관성」을 이유로 한쪽 축의 확정을 다른 축에 옮기면 <b>확정되지 않은
 * 사양으로 사용자 데이터를 비가역 삭제</b>하게 된다.
 *
 * <h3>설정이 없거나 비정상이면 값을 만들지 않는다 (fail-closed)</h3>
 * <p>{@code SystemConfigService.getInt} 는 행이 없으면 <b>예외를 던진다</b>(이 3키는 폴백하지 않는다 —
 * {@link ConfigKeys#PORTAL_DATAMART_RETENTION_DAYS} javadoc). 조회 경로에서는 그 예외를 그대로 올리면
 * <b>목록 화면 전체가 500</b> 이 되므로 <b>그 필드만 {@code null}</b> 로 내린다. 삭제 배치는 반대로 그
 * 회차를 건너뛰는데, 둘 다 "설정이 없으면 아무 일도 일어나지 않는다"로 일관된다.
 *
 * <p>★ <b>보존일수가 0 이하면 「설정 없음」과 같이 다룬다</b>({@link #retentionDays}). 0 을 그대로
 * 적용하면 커트라인이 <b>현재 시각</b>이 되어 방금 저장한 라벨까지 <b>전량이 즉시 삭제 대상</b>이 되고,
 * 음수면 커트라인이 미래가 되어 더 넓어진다. 어느 쪽도 복구 수단이 없다. 임의 기본값(7 등)으로 대체하지도
 * 않는다 — 파괴적 기능이 fail-open 하면 "알아서 지웠다"가 되기 때문이다(DFEAT-055 — 방치 판정 시간의
 * 「0 이하·해석 불가면 그 회차를 수행하지 않는다」와 같은 취지).
 *
 * <p>⚠ 저장 입구는 이미 하한 1 을 강제한다({@link ConfigKeys#NUMBER_RANGE}). 그런데도 <b>읽는 쪽에</b>
 * 같은 판정을 두는 이유는 이 값이 <b>기동 시점 상수가 아니라 런타임에 설정 테이블에서 읽는 값</b>이라
 * 시드 오류·DB 직접 수정으로 0 이 앉을 수 있고, 그때 기동을 실패시킬 수단이 없기 때문이다. 그래서
 * 기동 차단이 아니라 <b>그 축을 건너뛰는 쪽</b>을 골랐다 — 아무것도 지우지 않는 것이 언제나 안전하다.
 * 대신 <b>ERROR 로그</b>를 남겨 "왜 안 지워지지"로 묻히지 않게 한다.
 *
 * <h3>스냅샷을 돌려주는 이유 (설정 읽기의 N+1 회피)</h3>
 * <p>{@link #datamartExpiry()}/{@link #uploadExpiry()} 는 설정을 <b>1회</b> 읽어 굳힌 계산기를 준다.
 * 행마다 정책을 부르면 설정 부재 시 <b>예외는 캐시되지 않으므로</b> 행 수만큼 DB 왕복이 난다(목록은
 * 페이지당 최대 100행). 덤으로 한 페이지 안에서 설정이 바뀌어 행마다 다른 기준이 섞이는 것도 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalRetentionPolicy {

    private final SystemConfigService systemConfigService;

    /** 데이터마트 라벨 보존일수. 설정 부재·비정상값이면 {@code empty}(= 만료 판정 불가). */
    public OptionalInt datamartRetentionDays() {
        return retentionDays(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS);
    }

    /** 업로드 자산(READY) 보존일수. 설정 부재·비정상값이면 {@code empty}. */
    public OptionalInt uploadRetentionDays() {
        return retentionDays(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS);
    }

    /** 업로드 자산(FAILED) 보존일수. 설정 부재·비정상값이면 {@code empty}. */
    public OptionalInt uploadFailedRetentionDays() {
        return retentionDays(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS);
    }

    /** 데이터마트 축 계산기 — 조회 1회분 설정 스냅샷. */
    public DatamartExpiry datamartExpiry() {
        return new DatamartExpiry(datamartRetentionDays());
    }

    /** 업로드 축 계산기 — 조회 1회분 설정 스냅샷(READY·FAILED 두 축을 함께 굳힌다). */
    public UploadExpiry uploadExpiry() {
        return new UploadExpiry(uploadRetentionDays(), uploadFailedRetentionDays());
    }

    /**
     * 데이터마트 라벨 만료 예정 시각 계산기 — 기준점은 <b>최초</b> 저장 시각이다.
     * @design DFEAT-055, AC-1068, AC-033
     *
     * @param retentionDays 조회 시점 {@code portal.datamart.retention-days}(부재면 empty)
     */
    public record DatamartExpiry(OptionalInt retentionDays) {

        /**
         * @param firstLabelSavedAt 그 (사용자, 영상) 본인 저장 라벨의 {@code MIN(REG_DT)} —
         *                          <b>그 사용자의 저작 최초 저장 시각</b>. 저장 라벨이 없으면 {@code null}
         * @return 만료 예정 시각. 저장 라벨이 없거나 설정이 없으면 {@code null}
         */
        public LocalDateTime expiresAt(LocalDateTime firstLabelSavedAt) {
            // ★ 마지막 저장이 아니라 최초 저장이다(DFEAT-055 — 포털 확정 회신 2026-09-03).
            // 마지막 저장에서 다시 계산하면 사용자가 저장할 때마다 만료가 뒤로 밀려, 작업을 이어 가는
            // 한 만료가 영영 오지 않는다 — 이 채널의 자동 삭제가 사실상 실행되지 않고 화면이 고지한
            // 만료 예정일도 계속 어긋난다. 편의를 이유로 되돌리지 말 것.
            // ⚠ 업로드 축(UploadExpiry)은 반대로 「늦은 쪽」이 맞다 — 그쪽은 확정 대상이 아니었다.
            return plusDays(firstLabelSavedAt, retentionDays);
        }
    }

    /**
     * 업로드 자산 만료 예정 시각 계산기 — 상태별로 기준점과 보존기간이 다르다. @design AC-033, AC-037
     *
     * @param readyRetentionDays  {@code portal.upload.retention-days}(부재면 empty)
     * @param failedRetentionDays {@code portal.upload.failed-retention-days}(부재면 empty)
     */
    public record UploadExpiry(OptionalInt readyRetentionDays, OptionalInt failedRetentionDays) {

        /**
         * @param uldSttsCd        자산 상태({@code READY}/{@code FAILED}/{@code PROCESSING}/{@code UPLOADED})
         * @param regDt            자산 등록 일시
         * @param mdfcnDt          자산 최종 상태 변경 일시(FAILED 축의 기준점 = 전이 시각)
         * @param lastLabelSavedAt 그 자산 라벨의 {@code MAX(REG_DT)} — 라벨이 없으면 {@code null}
         * @return 만료 예정 시각. 삭제 대상이 아닌 상태이거나 설정이 없으면 {@code null}
         */
        public LocalDateTime expiresAt(String uldSttsCd, LocalDateTime regDt, LocalDateTime mdfcnDt,
                                       LocalDateTime lastLabelSavedAt) {
            if (PortalUploadLedger.STATUS_READY.equals(uldSttsCd)) {
                // 작업 중이면 라벨 저장이 기준점을 계속 밀어낸다 — 그래서 늦은 쪽을 쓴다(DFEAT-055).
                return plusDays(later(regDt, lastLabelSavedAt), readyRetentionDays);
            }
            if (PortalUploadLedger.STATUS_FAILED.equals(uldSttsCd)) {
                // 실패 자산은 되찾을 수 없어 더 짧게 정리한다 — READY 축과 독립(AC-037).
                return plusDays(mdfcnDt, failedRetentionDays);
            }
            // PROCESSING·UPLOADED 는 삭제 대상이 아니므로 고지할 만료도 없다.
            return null;
        }
    }

    /**
     * 보존일수 설정 조회 — <b>부재·타입불일치·파싱실패·0 이하</b> 어느 쪽이든 {@code empty}.
     * 조회 경로를 500 으로 깨지 않는다. @design DFEAT-055, AC-1068, AC-033
     *
     * <p>★ 판정은 <b>여기 한 곳</b>이다. {@code plusDays} 에 같은 검사를 겹쳐 두지 않는다 — 두 곳에
     * 두면 한쪽이 망가져도 다른 쪽이 가려 <b>가드가 조용히 죽어도 시험이 알아채지 못한다</b>.
     * 두 계산기({@link DatamartExpiry}·{@link UploadExpiry})는 전부 이 메서드가 만든 스냅샷만 받으므로
     * 이 한 곳이 두 축을 모두 덮는다.
     */
    private OptionalInt retentionDays(String key) {
        Integer days;
        try {
            days = systemConfigService.getInt(key);
        } catch (RuntimeException e) {
            log.warn("[PortalRetention] 보존기간 설정을 읽지 못해 만료 예정 시각을 내리지 않는다 key={} causeType={}",
                    key, e.getClass().getSimpleName());
            return OptionalInt.empty();
        }
        if (days == null) {
            return OptionalInt.empty();
        }
        if (days <= 0) {
            // ★ 0 이면 커트라인이 «현재 시각», 음수면 «미래»가 되어 전량이 즉시 삭제 대상이 된다.
            //   임의 기본값으로 때우지 않고 그 축을 통째로 건너뛴다(위 클래스 주석 fail-closed 절).
            //   WARN 이 아니라 ERROR 인 것은 «기능이 죽어 있다»는 사실을 운영자가 알아야 하기 때문이다.
            log.error("[PortalRetention] 보존일수 설정이 0 이하라 만료 판정을 하지 않는다"
                            + " (즉시 삭제 방지) key={} days={}", key, days);
            return OptionalInt.empty();
        }
        return OptionalInt.of(days);
    }

    /**
     * 기준점 + 보존일수. 기준점이나 설정이 없으면 {@code null}(값을 지어내지 않는다).
     *
     * <p>여기서 보존일수의 유효성을 <b>다시 판정하지 않는다</b> — 0 이하 차단은
     * {@link #retentionDays} 단일 지점이 소유한다(가드 중복 = 가드 무력화).
     */
    private static LocalDateTime plusDays(LocalDateTime base, OptionalInt days) {
        if (base == null || days.isEmpty()) {
            return null;
        }
        return base.plusDays(days.getAsInt());
    }

    /** null 안전 최댓값 — 둘 다 없으면 {@code null}. */
    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
