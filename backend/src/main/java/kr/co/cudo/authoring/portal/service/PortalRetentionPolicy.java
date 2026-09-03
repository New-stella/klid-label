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
 * 포털 보존기간 만료 예정 시각의 <b>단일 판정 지점</b>. @design AC-033, DFEAT-055
 *
 * <h3>저장하지 않는다 — 조회 시점 파생값이다</h3>
 * <p>AC-033 이 이 값을 <b>조회 시점 설정값 기준 파생값</b>으로 못박았다. 보존기간을 7일에서 14일로
 * 바꾸면 <b>이미 저장된 라벨의 만료 예정도 다음 조회부터 즉시</b> 달라져야 하므로, 엔티티 컬럼으로
 * 굳히거나 계산 결과를 캐시하지 않는다(마이그레이션 대상 아님). 설정값 자체는
 * {@code SystemConfigService} 의 Caffeine 캐시(TTL 60s)를 타지만 그 캐시는 설정 갱신 시
 * {@code allEntries=true} 로 비워지므로 즉시 반영이 성립한다.
 *
 * <h3>기준점이 축마다 다르다</h3>
 * <table>
 *   <caption>보존기간 기준점</caption>
 *   <tr><th>축</th><th>기준점</th><th>설정 키</th></tr>
 *   <tr><td>데이터마트 라벨</td><td>그 (사용자, 영상) 본인 저장 라벨의 {@code MAX(REG_DT)}</td>
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
 * <h3>설정이 없으면 값을 만들지 않는다</h3>
 * <p>{@code SystemConfigService.getInt} 는 행이 없으면 <b>예외를 던진다</b>(이 3키는 폴백하지 않는다 —
 * {@link ConfigKeys#PORTAL_DATAMART_RETENTION_DAYS} javadoc). 조회 경로에서는 그 예외를 그대로 올리면
 * <b>목록 화면 전체가 500</b> 이 되므로 <b>그 필드만 {@code null}</b> 로 내린다. 삭제 배치는 반대로 그
 * 회차를 건너뛰는데, 둘 다 "설정이 없으면 아무 일도 일어나지 않는다"로 일관된다.
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
     * 데이터마트 라벨 만료 예정 시각 계산기. @design AC-033
     *
     * @param retentionDays 조회 시점 {@code portal.datamart.retention-days}(부재면 empty)
     */
    public record DatamartExpiry(OptionalInt retentionDays) {

        /**
         * @param lastLabelSavedAt 그 (사용자, 영상) 본인 저장 라벨의 {@code MAX(REG_DT)} — 저장 라벨이
         *                         없으면 {@code null}
         * @return 만료 예정 시각. 저장 라벨이 없거나 설정이 없으면 {@code null}
         */
        public LocalDateTime expiresAt(LocalDateTime lastLabelSavedAt) {
            return plusDays(lastLabelSavedAt, retentionDays);
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

    /** 설정 조회 — 부재·타입불일치·파싱실패 어느 쪽이든 {@code empty}. 조회 경로를 500 으로 깨지 않는다. */
    private OptionalInt retentionDays(String key) {
        try {
            Integer days = systemConfigService.getInt(key);
            return days == null ? OptionalInt.empty() : OptionalInt.of(days);
        } catch (RuntimeException e) {
            log.warn("[PortalRetention] 보존기간 설정을 읽지 못해 만료 예정 시각을 내리지 않는다 key={} causeType={}",
                    key, e.getClass().getSimpleName());
            return OptionalInt.empty();
        }
    }

    /** 기준점 + 보존일수. 기준점이나 설정이 없으면 {@code null}(값을 지어내지 않는다). */
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
