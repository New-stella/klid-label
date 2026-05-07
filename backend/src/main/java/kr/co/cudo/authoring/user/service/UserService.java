package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.user.dto.UserProfileResponse;
import kr.co.cudo.authoring.user.dto.UserSummaryResponse;
import kr.co.cudo.authoring.user.dto.WorkerSummaryResponse;
import kr.co.cudo.authoring.user.entity.MngAcctUser;
import kr.co.cudo.authoring.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserProfileResponse getProfile(TokenClaims claims) {
        Long userNo = parseUserNo(claims.sub());
        MngAcctUser user = userRepository.findByUserNo(userNo)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        return UserProfileResponse.of(user, claims.role().name(), claims.channel().name());
    }

    public UserProfileResponse getById(Long userNo, TokenClaims claims) {
        MngAcctUser user = userRepository.findByUserNo(userNo)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        return UserProfileResponse.of(user, claims.role().name(), claims.channel().name());
    }

    public List<WorkerSummaryResponse> listWorkersWithTaskCount() {
        return userRepository.findAllWorkersWithTaskCount().stream()
                .map(WorkerSummaryResponse::from)
                .toList();
    }

    /**
     * 사용자 마스터 페이징 검색 (/v1/users) — REVIEWER 의 사용자 관리 화면.
     */
    public Page<UserSummaryResponse> searchUsers(String keyword, Pageable pageable) {
        return userRepository.searchByKeyword(keyword, pageable)
                .map(UserSummaryResponse::from);
    }

    private Long parseUserNo(String sub) {
        try {
            return Long.parseLong(sub);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "토큰 subject 형식이 올바르지 않습니다.");
        }
    }
}
