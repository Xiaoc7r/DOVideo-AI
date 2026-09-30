package com.example.server.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerTest {
    @Test
    void multipartAndContentTypeErrorsUseActionable4xxResponsesInsteadOfRetriable500() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new UploadController())
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(multipart("/upload")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value(40000));
        mvc.perform(get("/oversized")).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("code").value(41300));
        mvc.perform(post("/json").contentType(MediaType.TEXT_PLAIN).content("text"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("code").value(41500));
    }

    @RestController
    static class UploadController {
        @PostMapping("/upload") String upload(@RequestPart("file") MultipartFile file) { return "ok"; }
        @GetMapping("/oversized") String oversized() { throw new MaxUploadSizeExceededException(10); }
        @PostMapping(value = "/json", consumes = "application/json") String json() { return "ok"; }
    }
}
