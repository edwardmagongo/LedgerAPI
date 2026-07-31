package com.edwardmagongo.ledgerapi.auth;

import com.edwardmagongo.ledgerapi.auth.dto.RegisterRequest;
import com.edwardmagongo.ledgerapi.auth.dto.UserResponse;
import com.edwardmagongo.ledgerapi.common.EmailAlreadyRegisteredException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }
        User user = userRepository.save(new User(email, passwordEncoder.encode(request.password())));
        return new UserResponse(user.getId(), user.getEmail(), user.getCreatedAt());
    }
}
