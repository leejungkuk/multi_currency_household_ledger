package com.self.multi_currency_household_ledger.member.service;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.member.entity.Member;
import com.self.multi_currency_household_ledger.member.repository.MemberRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public MemberService(MemberRepository memberRepository, PasswordEncoder passwordEncoder) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 회원가입. 이메일 중복 시 예외, 정상 시 비밀번호를 인코딩하여 저장한다.
     */
    @Transactional
    public Member register(String email, String rawPassword, String nickname) {
        if (memberRepository.existsByEmail(email)) {
            throw new BusinessException("DUPLICATE_EMAIL", "이미 가입된 이메일입니다: " + email);
        }
        String encoded = passwordEncoder.encode(rawPassword);
        Member member = Member.of(email, encoded, nickname);
        return memberRepository.save(member);
    }
}
