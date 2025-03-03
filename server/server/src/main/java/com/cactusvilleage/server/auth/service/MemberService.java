package com.cactusvilleage.server.auth.service;

import com.cactusvilleage.server.auth.entities.Authority;
import com.cactusvilleage.server.auth.entities.Member;
import com.cactusvilleage.server.auth.entities.RefreshToken;
import com.cactusvilleage.server.auth.entities.oauth.ProviderType;
import com.cactusvilleage.server.auth.repository.MemberRepository;
import com.cactusvilleage.server.auth.repository.RefreshTokenRepository;
import com.cactusvilleage.server.auth.util.CookieUtil;
import com.cactusvilleage.server.auth.util.SecurityUtil;
import com.cactusvilleage.server.auth.web.dto.request.EditDto;
import com.cactusvilleage.server.auth.web.dto.request.PlainLoginDto;
import com.cactusvilleage.server.auth.web.dto.request.PlainSignupDto;
import com.cactusvilleage.server.auth.web.dto.request.RecoveryDto;
import com.cactusvilleage.server.auth.web.dto.response.EditResponseDto;
import com.cactusvilleage.server.auth.web.dto.response.MemberInfoResponseDto;
import com.cactusvilleage.server.challenge.entities.Challenge;
import com.cactusvilleage.server.global.exception.BusinessLogicException;
import com.cactusvilleage.server.global.infra.email.EmailSender;
import com.cactusvilleage.server.global.response.SingleResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.cactusvilleage.server.auth.entities.oauth.ProviderType.GOOGLE;
import static com.cactusvilleage.server.auth.entities.oauth.ProviderType.KAKAO;
import static com.cactusvilleage.server.challenge.entities.Status.DELETED;
import static com.cactusvilleage.server.global.exception.ExceptionCode.*;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class MemberService {

    private final MemberRepository memberRepository;
    private final RefreshTokenRepository tokenRepository;
    private final AuthenticationManagerBuilder authBuilder;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender awsSesSender;
    private final CookieUtil jwtCookieUtil;

    public void signup(PlainSignupDto signupDto) {
        Member member = signupDto.toMember(passwordEncoder);
        memberRepository.save(member);
    }

    public ResponseEntity login(PlainLoginDto loginDto, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = verifyPassword(loginDto.getEmail(), loginDto.getPassword());

        String memberId = authentication.getName();
        Member member = findMember(Long.parseLong(memberId));
        if (member.getAuthority().equals(Authority.ROLE_USER)) {
            tokenRepository.checkRefreshToken(memberId);
        }

        MemberInfoResponseDto memberInfo = setMemberInfo(member);
        jwtCookieUtil.generateTokenCookies(request, response, authentication);

        return new ResponseEntity<>(new SingleResponseDto<>(memberInfo), HttpStatus.OK);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        RefreshToken refreshToken = getRefreshToken(request);
        tokenRepository.deleteById(refreshToken.getTokenId());

        jwtCookieUtil.deleteCookie(request, response, "access_token");
        jwtCookieUtil.deleteCookie(request, response, "refresh_token");
    }

    public ResponseEntity editMember(EditDto editDto) {
        Long memberId = SecurityUtil.getCurrentMemberId();
        Member foundMember = findMember(memberId);

        if (foundMember.getProviderType().equals(ProviderType.CACTUS)) {
            verifyPassword(foundMember.getEmail(), editDto.getPrePassword());

            foundMember.setUsername(editDto.getUsername());
            Optional.ofNullable(editDto.getNewPassword())
                    .ifPresent(password -> foundMember.setPassword(passwordEncoder.encode(password)));

            memberRepository.save(foundMember);
            EditResponseDto editResponse = EditResponseDto.builder().username(foundMember.getUsername()).build();

            return new ResponseEntity<>(new SingleResponseDto<>(editResponse), HttpStatus.OK);

        } else {
            foundMember.setUsername(editDto.getUsername());
            memberRepository.save(foundMember);
            EditResponseDto editResponse = EditResponseDto.builder().username(foundMember.getUsername()).build();

            return new ResponseEntity(new SingleResponseDto<>(editResponse), HttpStatus.OK);
        }
    }

    public void recoveryMember(RecoveryDto recoveryDto) {
        Member foundMember = memberRepository.findByEmail(recoveryDto.getEmail())
                .orElseThrow(() -> new BusinessLogicException(MEMBER_NOT_FOUND));

        if (!foundMember.getUsername().equals(recoveryDto.getUsername())) {
            throw new BusinessLogicException(MEMBER_NOT_FOUND);
        }

        String email = recoveryDto.getEmail();
        String tempPassword = getTempPassword();

        Context context = new Context();
        context.setVariable("username", recoveryDto.getUsername());
        context.setVariable("tempPassword", tempPassword);

        awsSesSender.singleEmailRequest(email, "선인장 키우기의 임시 비밀번호입니다", "recovery", context);

        foundMember.setPassword(passwordEncoder.encode(tempPassword));
        memberRepository.save(foundMember);
    }

    public void deleteMember(HttpServletRequest request, HttpServletResponse response) {
        RefreshToken refreshToken = getRefreshToken(request);
        tokenRepository.deleteById(refreshToken.getTokenId());

        Long memberId = Long.parseLong(refreshToken.getMemberId());
        Member foundMember = findMember(memberId);
        Member deletedMember = deleteByType(foundMember);

        memberRepository.save(deletedMember);

        jwtCookieUtil.deleteCookie(request, response, "access_token");
        jwtCookieUtil.deleteCookie(request, response, "refresh_token");
    }

    public ResponseEntity reissueAccessToken(HttpServletRequest request, HttpServletResponse response) {
        RefreshToken refreshToken = getRefreshToken(request);
        jwtCookieUtil.generateAccessCookie(request, response, refreshToken);

        return new ResponseEntity<>(HttpStatus.OK);
    }

    public ResponseEntity getMemberInfo() {
        Long memberId = SecurityUtil.getCurrentMemberId();
        Member member = findMember(memberId);
        MemberInfoResponseDto memberInfo = setMemberInfo(member);
        return new ResponseEntity<>(new SingleResponseDto<>(memberInfo), HttpStatus.OK);
    }

    private Member deleteByType(Member member) {
        String dummy = getEncodedMemberId(member.getId());
        ProviderType providerType = member.getProviderType();

        if (providerType.equals(KAKAO) || providerType.equals(GOOGLE)) {
            if (member.getEmail() == null) {
                member.deleteMember(true, null, member.getUsername() + dummy, member.getProviderId() + dummy);
            } else {
                member.deleteMember(true, member.getEmail() + dummy, member.getUsername() + dummy, member.getProviderId() + dummy);
            }
        } else {
            member.deleteMember(true, member.getEmail() + dummy, member.getUsername() + dummy, null);
        }
        return member;
    }

    private MemberInfoResponseDto setMemberInfo(Member member) {
        Challenge challenge = getRecentChallenge(member);
        if (challenge == null || challenge.getStatus().equals(DELETED) || challenge.isNotified()) {
            return MemberInfoResponseDto.builder()
                    .email(member.getEmail())
                    .username(member.getUsername())
                    .status("none")
                    .challengeType("none")
                    .providerType(member.getProviderType().toString().toLowerCase())
                    .build();
        } else {

            int progress = (int) ((double) challenge.getHistories().size() / challenge.getTargetDate() * 100);
            int now = (int) Duration.between(challenge.getCreatedAt().toLocalDate().atStartOfDay(), LocalDate.now().atStartOfDay()).toDays() + 1;

            return MemberInfoResponseDto.builder()
                    .email(member.getEmail())
                    .username(member.getUsername())
                    .status(challenge.getStatus().toString().toLowerCase())
                    .progress(progress)
                    .challengeType(challenge.getChallengeType().toString().toLowerCase())
                    .now(now)
                    .targetDate(challenge.getTargetDate())
                    .providerType(member.getProviderType().toString().toLowerCase())
                    .build();
        }

    }

    public Challenge getRecentChallenge(Member member) {
        if (member.getChallenges().isEmpty()) {
            return null;
        }

        return member.getChallenges().stream()
                .sorted((o1, o2) -> Long.compare(o2.getId(), o1.getId()))
                .collect(Collectors.toList())
                .get(0);
    }

    private RefreshToken getRefreshToken(HttpServletRequest request) {
        Cookie refreshCookie = jwtCookieUtil.getCookie(request, "refresh_token")
                .orElseThrow(() -> new BusinessLogicException(NO_AUTHENTICATION));
        String tokenId = refreshCookie.getValue();

        return tokenRepository.findById(tokenId)
                .orElseThrow(() -> new BusinessLogicException(NO_AUTHENTICATION));
    }

    private String getTempPassword() {
        String tempPassword = UUID.randomUUID().toString().replaceAll("-", "");
        return tempPassword.substring(0, 10);
    }

    private Authentication verifyPassword(String email, String password) {
        UsernamePasswordAuthenticationToken authenticationToken = toAuthentication(email, password);
        try {
            return authBuilder.getObject().authenticate(authenticationToken);
        } catch (AuthenticationException e) {
            throw new BusinessLogicException(MEMBER_NOT_MATCH);
        }
    }

    private UsernamePasswordAuthenticationToken toAuthentication(String email, String password) {
        return new UsernamePasswordAuthenticationToken(email, password);
    }

    public Member findMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessLogicException(MEMBER_NOT_FOUND));
    }

    private String getEncodedMemberId(Long memberId) {
        return passwordEncoder.encode(memberId.toString());
    }


}
