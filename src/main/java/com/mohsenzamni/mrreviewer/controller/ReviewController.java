package com.mohsenzamni.mrreviewer.controller;

import com.mohsenzamni.mrreviewer.dto.ReviewRequest;
import com.mohsenzamni.mrreviewer.dto.ReviewResponse;
import com.mohsenzamni.mrreviewer.service.ReviewService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry point for the pre-push code review agent.
 *
 * <pre>
 * POST /review
 * {
 *   "issueUrl": "https://gitlab.example.com/group/project/-/issues/42"
 * }
 * </pre>
 */
@RestController
@RequestMapping("/review")
public class ReviewController {

    private static final Logger log = LoggerFactory.getLogger(ReviewController.class);

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping
    public ResponseEntity<ReviewResponse> review(@Valid @RequestBody ReviewRequest request) {
        log.info("Received review request for issue: {}", request.issueUrl());
        ReviewResponse response = reviewService.review(request);
        return ResponseEntity.ok(response);
    }
}
