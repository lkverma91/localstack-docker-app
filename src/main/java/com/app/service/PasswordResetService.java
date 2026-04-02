package com.app.service;

import com.app.entity.PasswordResetToken;
import com.app.entity.User;
import com.app.exception.InvalidResetTokenException;
import com.app.repository.PasswordResetTokenRepository;
import com.app.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Sends password-reset OTPs via {@link SesClient}. With Docker/LocalStack, {@link com.app.config.AwsConfig}
 * points SES to the LocalStack endpoint; {@code init-aws.sh} verifies the sender email identity in emulated SES.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    private final PasswordResetTokenRepository resetTokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SesClient sesClient;

    @Value("${app.aws.ses.from-email:noreply@authapp.local}")
    private String senderEmail;

    private static final int TOKEN_LENGTH = 6;
    private static final long TOKEN_EXPIRY_MINUTES = 15;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Transactional
    public void initiatePasswordReset(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        resetTokenRepository.deleteByUserId(user.getId());

        String otp = generateOtp();

        PasswordResetToken resetToken = PasswordResetToken.builder()
                .token(otp)
                .user(user)
                .expiryDate(Instant.now().plus(TOKEN_EXPIRY_MINUTES, ChronoUnit.MINUTES))
                .build();

        resetTokenRepository.save(resetToken);

        sendResetEmail(user.getEmail(), user.getFullName(), otp);

        log.info("Password reset OTP sent to: {}", email);
    }

    @Transactional
    public void resetPassword(String email, String token, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        PasswordResetToken resetToken = resetTokenRepository.findByTokenAndUsedFalse(token)
                .orElseThrow(() -> new InvalidResetTokenException("Invalid or already used reset token"));

        if (!resetToken.getUser().getId().equals(user.getId())) {
            throw new InvalidResetTokenException("Reset token does not belong to this user");
        }

        if (resetToken.getExpiryDate().isBefore(Instant.now())) {
            resetTokenRepository.delete(resetToken);
            throw new InvalidResetTokenException("Reset token has expired. Please request a new one.");
        }

        resetToken.setUsed(true);
        resetTokenRepository.save(resetToken);

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        log.info("Password reset successfully for: {}", email);
    }

    private String generateOtp() {
        int otp = SECURE_RANDOM.nextInt(900_000) + 100_000;
        return String.valueOf(otp);
    }

    private void sendResetEmail(String toEmail, String fullName, String otp) {
        String subject = "Password Reset - Your OTP Code";
        String body = String.format(
                """
                Hello %s,

                You have requested to reset your password.

                Your OTP code is: %s

                This code is valid for %d minutes. If you did not request this, please ignore this email.

                Regards,
                Auth App Team
                """,
                fullName, otp, TOKEN_EXPIRY_MINUTES
        );

        try {
            SendEmailRequest request = SendEmailRequest.builder()
                    .source(senderEmail)
                    .destination(Destination.builder().toAddresses(toEmail).build())
                    .message(Message.builder()
                            .subject(Content.builder().data(subject).charset("UTF-8").build())
                            .body(Body.builder()
                                    .text(Content.builder().data(body).charset("UTF-8").build())
                                    .build())
                            .build())
                    .build();

            sesClient.sendEmail(request);
            log.info("Reset email sent via SES to: {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send reset email to {}: {}", toEmail, e.getMessage());
            log.warn("OTP for {} (email delivery failed): {}", toEmail, otp);
        }
    }
}
