
export const initialChatState = {
  sessionId: null,        
  messages: [],           
  hasMoreMessages: false,
  nextCursor: null,
  streamingMessage: null, // Separated streaming state to prevent array recreation on every token
  isStreaming: false,
  messagesLoading: false,
  suggestedQuestions: [],
};

export function chatReducer(state, action) {
  if (action.sessionId !== undefined && action.sessionId !== state.sessionId) {
    return state;
  }

  switch (action.type) {
    case 'SET_SESSION':
      return {
        ...initialChatState,
        sessionId: action.payload.sessionId,
      };

    case 'SYNC_ROUTE_SESSION': {
      const paramId = action.payload.sessionId ? String(action.payload.sessionId) : null;
      const stateId = state.sessionId ? String(state.sessionId) : null;
      if (paramId === stateId) return state;
      
      return {
        ...initialChatState,
        sessionId: action.payload.sessionId,
      };
    }

    case 'MESSAGES_LOADING':
      return { ...state, messagesLoading: true };

    case 'MESSAGES_LOADED':
      return { 
        ...state, 
        messages: action.payload.messages, 
        hasMoreMessages: action.payload.hasMore,
        nextCursor: action.payload.nextCursor,
        messagesLoading: false 
      };

    case 'PREPEND_MESSAGES':
      return {
        ...state,
        messages: [...action.payload.messages, ...state.messages],
        hasMoreMessages: action.payload.hasMore,
        nextCursor: action.payload.nextCursor,
      };

    case 'MESSAGES_LOAD_FAILED':
      return { ...state, messagesLoading: false };

    case 'SEND_MESSAGE_OPTIMISTIC': {
      const { userMessage, assistantPlaceholder } = action.payload;
      const newMessages = [...state.messages];
      if (userMessage) newMessages.push(userMessage);
      
      return {
        ...state,
        messages: newMessages,
        isStreaming: !!assistantPlaceholder,
        streamingMessage: assistantPlaceholder ? { ...assistantPlaceholder, progressEvents: [] } : null,
        suggestedQuestions: [],
      };
    }

    case 'APPEND_STREAM_CHUNK':
      if (state.streamingMessage && state.streamingMessage.id === action.payload.messageId) {
        return {
          ...state,
          streamingMessage: {
            ...state.streamingMessage,
            text: state.streamingMessage.text + action.payload.chunk
          }
        };
      }
      return state;

    case 'RESET_STREAM_TEXT':
      if (state.streamingMessage && state.streamingMessage.id === action.payload.messageId) {
        return {
          ...state,
          streamingMessage: {
            ...state.streamingMessage,
            text: ''
          }
        };
      }
      return state;

    case 'UPDATE_PROGRESS':
      if (state.streamingMessage && state.streamingMessage.id === action.payload.messageId) {
        return {
          ...state,
          streamingMessage: {
            ...state.streamingMessage,
            progressEvents: [...(state.streamingMessage.progressEvents || []), action.payload.progress]
          }
        };
      }
      return state;

    case 'SET_CITATIONS':
      if (state.streamingMessage && state.streamingMessage.id === action.payload.messageId) {
        return {
          ...state,
          streamingMessage: {
            ...state.streamingMessage,
            citations: action.payload.citations
          }
        };
      }
      return state;

    case 'SET_VISUALS':
      if (state.streamingMessage && state.streamingMessage.id === action.payload.messageId) {
        return {
          ...state,
          streamingMessage: {
            ...state.streamingMessage,
            visuals: action.payload.visuals
          }
        };
      }
      return state;

    case 'STREAM_DONE':
      if (state.streamingMessage && state.streamingMessage.id === action.payload.messageId) {
        return {
          ...state,
          isStreaming: false,
          messages: [...state.messages, { ...state.streamingMessage, status: 'complete' }],
          streamingMessage: null,
        };
      }
      return {
        ...state,
        isStreaming: false,
      };

    case 'STREAM_ERROR':
      if (state.streamingMessage && state.streamingMessage.id === action.payload.messageId) {
        return {
          ...state,
          isStreaming: false,
          messages: [...state.messages, { ...state.streamingMessage, status: 'error' }],
          streamingMessage: null,
        };
      }
      return {
        ...state,
        isStreaming: false,
      };

    case 'SET_SUGGESTED_QUESTIONS':
      return {
        ...state,
        suggestedQuestions: action.payload.questions,
      };

    case 'CLEAR_SUGGESTED_QUESTIONS':
      return {
        ...state,
        suggestedQuestions: [],
      };

    default:
      return state;
  }
}