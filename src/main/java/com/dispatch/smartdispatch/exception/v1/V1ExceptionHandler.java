package com.dispatch.smartdispatch.exception.v1;

import com.dispatch.smartdispatch.dto.v1.V1Dtos.ErrorBody;
import com.dispatch.smartdispatch.dto.v1.V1Dtos.ErrorDetail;
import com.dispatch.smartdispatch.dto.v1.V1Dtos.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.ArrayList;
import java.util.List;

@RestControllerAdvice(basePackages = "com.dispatch.smartdispatch.controller.v1")
public class V1ExceptionHandler {
    @ExceptionHandler(V1ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(V1ApiException ex) {
        return response(ex.getStatus(), ex.getCode(), ex.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ErrorDetail> details = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.add(new ErrorDetail(fieldError.getField(), fieldError.getDefaultMessage()));
        }
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Request validation failed", details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleUnreadable(Exception ex) {
        return response(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "Request body or path parameter is malformed or contains invalid field values", List.of());
    }

    private ResponseEntity<ErrorResponse> response(
            HttpStatus status, String code, String message, List<ErrorDetail> details) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(new ErrorBody(code, message, details)));
    }
}
