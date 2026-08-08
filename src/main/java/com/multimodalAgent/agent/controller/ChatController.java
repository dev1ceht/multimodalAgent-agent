package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.dto.ChatRequest;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.service.ChatService;
import com.multimodalAgent.agent.service.ConsentService;
import com.multimodalAgent.agent.service.multimodal.MultimodalInputService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Student-only chat entry points with consent enforced before any model work starts. */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ConsentService consentService;
    private final MultimodalInputService multimodalInputService;

    public ChatController(
            ChatService chatService,
            ConsentService consentService,
            MultimodalInputService multimodalInputService
    ) {
        this.chatService = chatService;
        this.consentService = consentService;
        this.multimodalInputService = multimodalInputService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> stream(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody ChatRequest request
    ) {
        consentService.requireChatConsent(currentUser);
        return chatService.streamChat(currentUser.getId(), request);
    }

    @PostMapping(
            value = "/multimodal/stream",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> streamMultimodal(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestPart(value = "sessionId", required = false) String sessionId,
            @RequestPart(value = "message", required = false) String message,
            @RequestPart(value = "audio", required = false) Mono<FilePart> audio,
            @RequestPart(value = "image", required = false) Mono<FilePart> image,
            @RequestPart(value = "video", required = false) Mono<FilePart> video
    ) {
        consentService.requireChatConsent(currentUser);
        boolean hasText = message != null && !message.isBlank();
        boolean hasAnyFile = audio != null || image != null || video != null;
        if (!hasText && !hasAnyFile) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text or a multimodal file is required");
        }
        String text = hasText ? message.trim() : "The student uploaded multimodal content and needs support.";
        ChatRequest request = new ChatRequest(sessionId, text);
        return multimodalInputService.analyze(text, monoOrEmpty(audio), monoOrEmpty(image), monoOrEmpty(video))
                .flatMapMany(analysis -> chatService.streamMultimodal(currentUser.getId(), request, analysis));
    }

    private Mono<FilePart> monoOrEmpty(Mono<FilePart> part) {
        return part == null ? Mono.empty() : part;
    }
}
