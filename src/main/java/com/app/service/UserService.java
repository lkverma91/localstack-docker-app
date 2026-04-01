package com.app.service;

import com.app.dto.RegisterRequest;
import com.app.dto.UpdateUserRequest;
import com.app.dto.UserProfileResponse;
import com.app.entity.Role;
import com.app.entity.User;
import com.app.exception.UserAlreadyExistsException;
import com.app.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPassword(),
                user.isEnabled(),
                true, true, true,
                List.of(new SimpleGrantedAuthority(user.getRole().name()))
        );
    }

    @Transactional
    public UserProfileResponse registerUser(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new UserAlreadyExistsException(
                    "User already exists with email: " + request.getEmail());
        }

        User user = User.builder()
                .fullName(request.getFullName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.ROLE_USER)
                .build();

        User saved = userRepository.save(user);

        return toProfileResponse(saved);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));
        return toProfileResponse(user);
    }

    @Transactional(readOnly = true)
    public List<UserProfileResponse> getAllUsers() {
        return userRepository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                .map(this::toProfileResponse)
                .toList();
    }

    @Transactional
    public UserProfileResponse changeUserRole(Long userId, Role role) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with id: " + userId));
        user.setRole(role);
        User saved = userRepository.save(user);
        return toProfileResponse(saved);
    }

    @Transactional
    public UserProfileResponse updateOwnProfile(String currentEmail, UpdateUserRequest request) {
        requireAtLeastOneField(request, false);
        User user = userRepository.findByEmail(currentEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + currentEmail));
        applyUserUpdates(user, request, false);
        return toProfileResponse(userRepository.save(user));
    }

    @Transactional
    public UserProfileResponse updateUserById(Long userId, UpdateUserRequest request) {
        requireAtLeastOneField(request, true);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with id: " + userId));
        applyUserUpdates(user, request, true);
        return toProfileResponse(userRepository.save(user));
    }

    private void requireAtLeastOneField(UpdateUserRequest request, boolean admin) {
        boolean hasField = request.getFullName() != null && !request.getFullName().isBlank()
                || request.getEmail() != null && !request.getEmail().isBlank()
                || admin && request.getEnabled() != null;
        if (!hasField) {
            throw new IllegalArgumentException("At least one field must be provided to update");
        }
    }

    private void applyUserUpdates(User user, UpdateUserRequest request, boolean allowEnabled) {
        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            user.setFullName(request.getFullName().trim());
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            String newEmail = request.getEmail().trim().toLowerCase();
            if (!newEmail.equalsIgnoreCase(user.getEmail()) && userRepository.existsByEmail(newEmail)) {
                throw new UserAlreadyExistsException("User already exists with email: " + newEmail);
            }
            user.setEmail(newEmail);
        }
        if (allowEnabled && request.getEnabled() != null) {
            user.setEnabled(request.getEnabled());
        }
    }

    private UserProfileResponse toProfileResponse(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .enabled(user.isEnabled())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
