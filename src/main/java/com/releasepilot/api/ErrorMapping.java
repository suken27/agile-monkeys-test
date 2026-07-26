package com.releasepilot.api;

import com.releasepilot.application.PromotionNotFoundException;
import com.releasepilot.domain.promotion.errors.DomainError;
import com.releasepilot.domain.promotion.errors.DuplicatePromotionInProgressError;
import com.releasepilot.domain.promotion.errors.EnvironmentSkippedError;
import com.releasepilot.domain.promotion.errors.InvalidTransitionError;
import com.releasepilot.domain.promotion.errors.PromotionAlreadyTerminalError;
import com.releasepilot.domain.promotion.errors.UnauthorizedApproverError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps every documented business-rule violation (SPECS §3.2/§10) and the two application-layer
 * "not found"/"bad input" cases to an explicit 4xx status — a domain-error violation never falls
 * through to an uncaught exception (never 500). The switch over the sealed {@link DomainError}
 * hierarchy is exhaustive at compile time: adding a new {@code DomainError} subtype without
 * updating this mapping fails the build, not a request at runtime.
 */
@RestControllerAdvice
public class ErrorMapping {

	@ExceptionHandler(DomainError.class)
	public ResponseEntity<ErrorResponse> handleDomainError(DomainError error) {
		HttpStatus status = switch (error) {
			case EnvironmentSkippedError e -> HttpStatus.UNPROCESSABLE_CONTENT;
			case UnauthorizedApproverError e -> HttpStatus.FORBIDDEN;
			case InvalidTransitionError e -> HttpStatus.CONFLICT;
			case PromotionAlreadyTerminalError e -> HttpStatus.CONFLICT;
			case DuplicatePromotionInProgressError e -> HttpStatus.CONFLICT;
		};
		return responseOf(status, error.getMessage());
	}

	@ExceptionHandler(PromotionNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleNotFound(PromotionNotFoundException error) {
		return responseOf(HttpStatus.NOT_FOUND, error.getMessage());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException error) {
		return responseOf(HttpStatus.BAD_REQUEST, error.getMessage());
	}

	private static ResponseEntity<ErrorResponse> responseOf(HttpStatus status, String message) {
		return ResponseEntity.status(status).body(new ErrorResponse(status.getReasonPhrase(), message));
	}

	public record ErrorResponse(String error, String message) {
	}
}
