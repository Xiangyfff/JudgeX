package com.judgex.common;

import com.judgex.web.dto.Dto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Dto.ApiError> handleApi(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(new Dto.ApiError(e.code(), e.getMessage(), MDC.get("traceId")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Dto.ApiError> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("invalid request");
        return ResponseEntity.badRequest()
                .body(new Dto.ApiError("VALIDATION", msg, MDC.get("traceId")));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Dto.ApiError> handleMissingResource(NoResourceFoundException e) {
        // Static assets (favicon, stale chunk after a redeploy) are noise, not server faults.
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new Dto.ApiError("NOT_FOUND", "no such resource: " + e.getResourcePath(),
                        MDC.get("traceId")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Dto.ApiError> handleOther(Exception e) {
        log.error("unhandled error: {}", e.toString(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Dto.ApiError("INTERNAL", "internal error, traceId=" + MDC.get("traceId"),
                        MDC.get("traceId")));
    }
}
