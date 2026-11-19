package com.example.docinsight.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final String maxRequestSize;

    public ApiExceptionHandler(@Value("${spring.servlet.multipart.max-request-size:512MB}")
                               String maxRequestSize) {
        this.maxRequestSize = maxRequestSize;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of(
                "message", error.getMessage() == null ? "请求参数不正确" : error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalidBody(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(errorItem -> errorItem.getDefaultMessage())
                .orElse("请求参数不正确");
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    /** 原文件缺失这类状态冲突对客户端是可读的，不应该报 500。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "message", error.getMessage() == null ? "当前状态不支持该操作" : error.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> uploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "message", "文件超过服务端上限 " + maxRequestSize));
    }
}
