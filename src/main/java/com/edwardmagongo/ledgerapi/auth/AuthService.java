package com.edwardmagongo.ledgerapi.auth;

import com.edwardmagongo.ledgerapi.auth.dto.AuthResponse;
import com.edwardmagongo.ledgerapi.auth.dto.LoginRequest;
import com.edwardmagongo.ledgerapi.auth.dto.RegisterRequest;
import com.edwardmagongo.ledgerapi.auth.dto.UserResponse;
import com.edwardmagongo.ledgerapi.common.EmailAlreadyRegisteredException;
import com.edwardmagongo.ledgerapi.common.InvalidCredentialsException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {

    // Used to run a dummy BCrypt comparison when the email lookup finds no user, so the
    // unknown-email path spends comparable time to the wrong-password path (which always
    // performs a real passwordEncoder.matches call). Without this, response timing would leak
    // whether an email is registered even though both paths throw the same exception.
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5L2t7cKQzHXTe8g5VLTaBfNwFbEwm";

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
        User user;
        try {
            user = userRepository.save(new User(email, passwordEncoder.encode(request.password())));
        } catch (DataIntegrityViolationException ex) {
            // Pre-check above is a fast-path only; a concurrent registration for the same
            // email can still slip past it and hit the DB's unique constraint here. Translate
            // that race into the same 409 the pre-check throws, instead of letting a raw
            // DataIntegrityViolationException surface as a 500.
            throw new EmailAlreadyRegisteredException(email);
        }
        return new UserResponse(user.getId(), user.getEmail(), user.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            // Run a dummy comparison so this branch costs about as much as the wrong-password
            // branch below, closing the timing side-channel between "unknown email" and
            // "known email, wrong password" (see DUMMY_HASH javadoc above).
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return new AuthResponse(jwtService.generateToken(user), "Bearer", jwtService.expirySeconds());
    }
}
