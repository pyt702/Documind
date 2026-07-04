package com.accenture.intern.docmind.dto.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaginatedMessageResponse {
    private List<MessageResponse> messages;
    private boolean hasMore;
    private String nextCursor;
}
