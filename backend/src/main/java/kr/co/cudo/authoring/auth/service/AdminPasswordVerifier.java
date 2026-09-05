package kr.co.cudo.authoring.auth.service;

import kr.co.cudo.authoring.auth.entity.LsMngrPswd;
import kr.co.cudo.authoring.auth.repository.LsMngrPswdRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 관리자 공유 패스워드의 <b>단일 판정 지점</b>이자 <b>유일한 보유자</b>. [@design ADR-046] [@design AC-122]
 *
 * <p>해시를 읽는 곳과 BCrypt 비교를 하는 곳이 둘로 갈리면, 한쪽만 강화·수정되어 다른 쪽이 조용히
 * 약해진다. 그래서 <b>이 클래스만</b> 해시를 들고 있고 {@link RoleClaimService}(권한 자가부여)와
 * 관리자 유효창 발급·교체가 <b>같은 판정</b>을 쓴다.
 *
 * <h3>자격이 어디에 있는가 — 저장소 우선, 배포 설정 폴백, 둘 다 없으면 거부</h3>
 * <ol>
 *   <li><b>저장소({@code LS_MNGR_PSWD})에 행이 있으면 그 값</b>이 판정을 맡는다. 한 번이라도 교체에
 *       성공하면 그 뒤로는 배포 설정값으로 되돌아가지 않는다.</li>
 *   <li><b>행이 없으면 배포 설정값</b>({@code authoring.auth.admin-claim-password-hash} ←
 *       환경변수 {@code ADMIN_CLAIM_PASSWORD_HASH})으로 판정한다. 이 폴백이 없으면 자격이 저장소로
 *       옮겨간 <b>직후의 최초 상태에서 관리 기능이 통째로 잠긴다</b> — 들어갈 수 없으니 패스워드를
 *       넣을 수도 없는 잠금이다.</li>
 *   <li><b>둘 다 없으면 어떤 패스워드도 통과하지 않는다.</b> 자격이 없는 상태가 곧 무제한 접근이 되면
 *       안 되므로 막는 쪽으로 어긋나게 한다.</li>
 * </ol>
 *
 * <p>⚠ <b>저장소를 읽지 못하는 장애도 3번과 같다</b> — 배포 설정값으로 되돌아가지 <b>않는다</b>.
 * 되돌아가면 저장소를 못 읽게 만들 수 있는 자가 이미 교체된 자격을 <b>옛 배포 설정값으로 되돌리는</b>
 * 것과 같아진다. 방향은 막히는 쪽이라 안전하다(ADR-046 잔여 위험으로 인지·수용).
 *
 * <h3>이 판정에 유효창 서명이 매달려 있다</h3>
 * <p>{@link AdminSessionTokenService} 가 서명 키를 유도할 때 {@link #currentHash()} 를 섞는다. 그래서
 * 자격이 바뀌면 그 전에 발급된 유효창이 <b>별도의 무효화 장부 없이</b> 자동으로 검증에 실패한다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>CWE-256</b> — 평문은 어디에도 저장되지 않는다. 설정에도 저장소에도 BCrypt 해시만 있다.</li>
 *   <li><b>CWE-203</b> — {@link BCryptPasswordEncoder#matches} 가 상수시간 비교를 보장한다.</li>
 *   <li><b>CWE-532</b> — 이 클래스는 패스워드·해시를 <b>로그에 출력하지 않는다</b>(성공 여부만 반환).
 *       저장소 조회 실패 로그에도 값이 실리지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class AdminPasswordVerifier {

    /**
     * 새 자격을 만들 때 쓰는 BCrypt 비용. 비교는 해시에 적힌 비용을 따르므로 이 값은
     * <b>새로 만드는 해시에만</b> 적용된다.
     */
    public static final int BCRYPT_COST = 12;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(BCRYPT_COST);

    /** 없을 수 있다 — 저장소 없이 배포 설정값만으로 판정하는 구성(단위 테스트)에서 {@code null}. */
    private final LsMngrPswdRepository repository;

    /** 배포 설정값 폴백. 저장소에 행이 없을 때만 쓰인다. */
    private final String fallbackHash;

    @Autowired
    public AdminPasswordVerifier(
            LsMngrPswdRepository repository,
            @Value("${authoring.auth.admin-claim-password-hash:}") String fallbackHash) {
        this.repository = repository;
        this.fallbackHash = normalizeHash(fallbackHash);
    }

    /** 저장소 없이 배포 설정값만으로 판정하는 구성 — 단위 테스트용. */
    public AdminPasswordVerifier(String fallbackHash) {
        this(null, fallbackHash);
    }

    /**
     * 배포 설정값을 검증해 보관한다. 평문이 설정에 들어오는 사고를 <b>기동 시점에</b> 막는다.
     *
     * <p>미설정(빈 값)은 기동을 막지 않는다 — 관련 기능만 항상 거절되면 되고, 이 값이 없다고 앱 전체가
     * 못 뜨면 이 기능과 무관한 배포까지 함께 막힌다.
     */
    private static String normalizeHash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (!isBcryptHash(trimmed)) {
            throw new IllegalStateException(
                    "authoring.auth.admin-claim-password-hash 는 BCrypt 해시여야 합니다 (시작 prefix $2a$/$2b$/$2y$).");
        }
        return trimmed;
    }

    private static boolean isBcryptHash(String value) {
        return value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$");
    }

    /**
     * 지금 판정을 맡는 해시 — 저장소 → 배포 설정 → 없음(빈 문자열) 순.
     *
     * <p>유효창 서명 키 유도가 이 값을 쓴다. 값이 없으면 빈 문자열을 돌려주며, 그때 유효창은
     * 발급도 검증도 되지 않는다.
     */
    public String currentHash() {
        String stored = storedHash();
        if (stored != null) {
            return stored;
        }
        return fallbackHash;
    }

    /**
     * 저장소의 해시.
     *
     * @return 행이 있으면 그 해시 / 행이 없으면 {@code null}(배포 설정 폴백 대상) /
     *         <b>조회에 실패하면 빈 문자열</b>(폴백하지 않고 거부 — 위 클래스 주석의 ⚠ 참조)
     */
    private String storedHash() {
        if (repository == null) {
            return null;
        }
        try {
            return repository.findById(LsMngrPswd.SINGLE_ROW_SN)
                    .map(LsMngrPswd::getPswdHash)
                    .filter(hash -> hash != null && !hash.isBlank())
                    .orElse(null);
        } catch (RuntimeException e) {
            // 값·해시는 남기지 않는다. 읽지 못했다는 사실과 예외 종류만.
            log.error("[AdminPassword] 자격 저장소 조회 실패 — 배포 설정값으로 되돌아가지 않고 거부합니다. cause={}",
                    e.getClass().getSimpleName());
            return "";
        }
    }

    /** 자격이 하나라도 있는가. 없으면 {@link #matches}가 항상 false 다. */
    public boolean isConfigured() {
        return !currentHash().isEmpty();
    }

    /**
     * 평문이 현재 관리자 공유 패스워드와 일치하는가 (상수시간 비교).
     *
     * @return 자격이 없거나 불일치면 {@code false}
     */
    public boolean matches(String rawPassword) {
        String hash = currentHash();
        if (hash.isEmpty() || rawPassword == null) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, hash);
    }

    /**
     * 자격을 교체한다 — 저장소의 <b>단일 행</b>을 만들거나 갱신한다. [@design AC-121]
     *
     * <p>이 클래스가 쓰기를 함께 갖는 이유는 "해시를 아는 유일한 곳"이라는 성질을 유지하기
     * 위해서다. 인코딩과 비교가 다른 클래스로 갈리면 비용·알고리즘이 한쪽만 바뀔 수 있다.
     *
     * <p>호출 즉시 {@link #currentHash()} 가 새 값을 돌려주므로, 그 값에 매달린 <b>기존 유효창은
     * 전부 무효</b>가 된다(방금 이 교체에 쓴 유효창도 포함된다 — 예외 갈래를 두지 않는다).
     *
     * @param rawPassword 새 평문 (★저장되지 않는다 — 해시만 저장된다)
     * @param modifierId  교체한 사람의 식별자(JWT sub)
     * @throws IllegalStateException 저장소 없이 구성된 경우(단위 테스트 구성)
     */
    public void replace(String rawPassword, String modifierId, LocalDateTime changedAt) {
        if (repository == null) {
            throw new IllegalStateException("자격 저장소가 구성되지 않아 관리자 패스워드를 교체할 수 없습니다.");
        }
        String encoded = passwordEncoder.encode(rawPassword);
        LsMngrPswd row = repository.findById(LsMngrPswd.SINGLE_ROW_SN).orElse(null);
        if (row == null) {
            repository.save(LsMngrPswd.of(encoded, modifierId, changedAt));
            return;
        }
        row.changeHash(encoded, modifierId, changedAt);
        repository.save(row);
    }
}
