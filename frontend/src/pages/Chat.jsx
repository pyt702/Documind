import { useReducer, useRef, useEffect, useCallback, useState } from 'react';
import { useParams, useNavigate, useOutletContext } from 'react-router-dom';
import { toast as sonnerToast } from 'sonner';
import { useAuth } from '../context/AuthContext.jsx';
import { useSessions } from '../context/SessionsContext.jsx';
import { sessionService } from '../services/sessionService.js';
import { chatService } from '../services/chatService.js';
import { attachmentService } from '../services/attachmentService.js';
import { useToast } from '../context/ToastContext.jsx';
import { preferenceService } from '../services/preferenceService.js';
import Streaming from '../components/streaming.jsx';
import CitationDrawer from '../components/CitationDrawer.jsx';
import ProgressIndicator from '../components/ProgressIndicator.jsx';
import ThinkingProcess from '../components/ThinkingProcess.jsx';
import Constellation from '../components/Constellation.jsx';
import Modal from '../components/Modal.jsx';
import { chatReducer, initialChatState } from '../state/chatReducer.js';
import { useTheme } from '../context/ThemeContext.jsx';
import AccentureLoader from '../components/AccentureLoader.jsx';
import ImageDeck from '../components/ImageDeck.jsx';
import { Virtuoso } from 'react-virtuoso';
import ChatMessage from '../components/ChatMessage.jsx';


const SendIcon = () => (
  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M6 12L3.269 3.126A59.768 59.768 0 0121.485 12 59.77 59.77 0 013.27 20.876L5.999 12zm0 0h7.5" />
  </svg>
);

const PaperclipIcon = () => (
  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13" />
  </svg>
);

const LinkIcon = () => (
  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
  </svg>
);

const XIcon = () => (
  <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
  </svg>
);

const BotAvatar = () => {
  const { theme } = useTheme();
  // Using light.png for dark mode (light pixels on dark bg) and dark.png for light mode
  const imgSrc = theme === 'dark' ? '/dark.png' : '/light.png';

  return (
    <div className="w-8 h-8 rounded-full flex items-center justify-center shrink-0 shadow-sm overflow-hidden">
      <img src={imgSrc} alt="AI Avatar" className="w-full h-full object-cover" />
    </div>
  );
};

const ChevronDownIcon = () => (
  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
  </svg>
);

const ChevronUpIcon = () => (
  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M5 15l7-7 7 7" />
  </svg>
);

const formatBytes = (bytes) => {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
};

// The backend saves a synthetic message like "[file upload: resume.pdf]" or
// "[wikipedia link: Some_Page]" purely as a row to anchor the Attachment
// record to (it needs a Message to attach to). It's plumbing, not something
// the user typed or the assistant said, so it should never show up as its own
// chat bubble — the upload itself is already visible via the pending-file
// chips while it's happening.
const UPLOAD_ANCHOR_PATTERN = /^\[(file upload|wikipedia link): .+\]$/;
const isUploadAnchorMessage = (msg) => UPLOAD_ANCHOR_PATTERN.test((msg.text || '').trim());

export default function Chat() {
  const { sessionId: routeSessionId } = useParams();
  const navigate = useNavigate();
  const { user } = useAuth();
  const { sessions, addSession } = useSessions();
  const { showToast } = useToast();
  const { openMobileSidebar } = useOutletContext();

  const [state, dispatch] = useReducer(chatReducer, {
    ...initialChatState,
    sessionId: routeSessionId ?? null,
  });

  const [input, setInput] = useState('');
  const [isDragging, setIsDragging] = useState(false);
  const [pendingFiles, setPendingFiles] = useState([]);
  const [activeCitation, setActiveCitation] = useState(null);
  const [showWikiInput, setShowWikiInput] = useState(false);
  const [wikiUrl, setWikiUrl] = useState('');
  const [wikiSearchResults, setWikiSearchResults] = useState([]);
  const [isWikiSearching, setIsWikiSearching] = useState(false);
  const [inputMessage, setInputMessage] = useState('');
  const [isInputOpen, setIsInputOpen] = useState(true);
  const bottomRef = useRef(null);
  const virtuosoRef = useRef(null);
  const textareaRef = useRef(null);
  const fileInputRef = useRef(null);
  const dragCounterRef = useRef(0);

  const [greetingIndex] = useState(() => Math.floor(Math.random() * 12));
  const [modelMenuOpen, setModelMenuOpen] = useState(false);
  const [selectedModel, setSelectedModel] = useState(
    () => localStorage.getItem('selectedModel') || 'GEMINI_3_1_FLASH_LITE'
  );
  const [models, setModels] = useState([]);
  const suggestionsPollRef = useRef(null);

  const [showShareMenu, setShowShareMenu] = useState(false);
  const [showQuestionsMenu, setShowQuestionsMenu] = useState(false);
  const [showEmailModal, setShowEmailModal] = useState(false);
  const [shareEmailAddress, setShareEmailAddress] = useState('');
  const [isExportingPdf, setIsExportingPdf] = useState(false);

  const handleShareUrl = async () => {
    setShowShareMenu(false);
    const shareUrl = `${window.location.origin}/chat/${routeSessionId}`;
    try {
      await navigator.clipboard.writeText(shareUrl);
      showToast('Share link copied to clipboard!', 'success');
    } catch {
      showToast('Failed to copy link', 'error');
    }
  };

  const handleSendMail = () => {
    setShowShareMenu(false);
    setShowEmailModal(true);
    setShareEmailAddress('');
  };

  const submitShareEmail = async () => {
    if (!shareEmailAddress || !shareEmailAddress.trim()) return;

    try {
      setShowEmailModal(false);
      showToast('Email is being sent in the background!', 'info');
      await sessionService.shareViaEmail(routeSessionId, shareEmailAddress.trim());
      showToast('Email sent successfully!', 'success');
    } catch (err) {
      showToast(err.message || 'Error sending email', 'error');
    }
  };

  const handleExportMarkdown = () => {
    setShowShareMenu(false);
    window.open(`${import.meta.env.VITE_API_URL || 'http://localhost:8080'}/api/sessions/${routeSessionId}/export`, '_blank');
  };

  const handleExportPdf = async () => {
    setShowShareMenu(false);
    if (isExportingPdf) return;
    setIsExportingPdf(true);

    const toastId = sonnerToast.loading('Preparing your PDF…', {
      description: 'Summarizing the session and rendering the document.',
    });

    try {
      const { jobId } = await sessionService.requestPdfExport(routeSessionId);

      // Poll until the worker finishes (READY) or fails — same "submit job,
      // poll for result" shape used for chat generation and suggested
      // questions, just without SSE since this is a single terminal result.
      const POLL_INTERVAL_MS = 1500;
      const MAX_ATTEMPTS = 80; // ~2 minutes, generous for an LLM summary + PDF render

      let result = null;
      for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        const status = await sessionService.getPdfExportStatus(routeSessionId, jobId);
        if (status.status === 'READY') {
          result = status;
          break;
        }
        if (status.status === 'FAILED') {
          throw new Error(status.errorMessage || 'PDF export failed');
        }
        await new Promise(res => setTimeout(res, POLL_INTERVAL_MS));
      }

      if (!result) {
        throw new Error('PDF export timed out. Please try again.');
      }

      sonnerToast.success('Your PDF is ready', {
        id: toastId,
        description: result.fileName || 'session.pdf',
      });

      // Fetch the rendered PDF straight from our own backend and trigger a
      // real browser download — no cloud storage involved.
      await sessionService.downloadPdfExportFile(routeSessionId, jobId, result.fileName);
    } catch (err) {
      sonnerToast.error(err.message || 'Failed to export PDF', { id: toastId });
    } finally {
      setIsExportingPdf(false);
    }
  };

  useEffect(() => {
    const fetchModels = async () => {
      try {
        const availableModels = await preferenceService.getModels();
        setModels(availableModels);
      } catch (err) {
        console.error("Failed to load models", err);
      }
    };
    fetchModels();
  }, []);

  const handleModelSelect = (id) => {
    setSelectedModel(id);
    localStorage.setItem('selectedModel', id);
    setModelMenuOpen(false);
  };

  const selectedModelObj = models.find(m => m.id === selectedModel);
  const displayModelName = selectedModelObj ? selectedModelObj.name : 'Loading...';

  const session = sessions.find(s => String(s.sessionId) === String(state.sessionId));

  useEffect(() => {
    dispatch({ type: 'SYNC_ROUTE_SESSION', payload: { sessionId: routeSessionId ?? null } });
  }, [routeSessionId]);

  useEffect(() => {
    if (!state.sessionId) return;
    if (state.messages.length > 0) return;
    let cancelled = false;
    dispatch({ type: 'MESSAGES_LOADING', sessionId: state.sessionId });
    sessionService.getMessages(state.sessionId)
      .then(res => { 
        if (!cancelled) {
          const isArray = Array.isArray(res);
          dispatch({ 
            type: 'MESSAGES_LOADED', 
            sessionId: state.sessionId, 
            payload: { 
              messages: isArray ? res : res.messages, 
              hasMore: isArray ? false : res.hasMore, 
              nextCursor: isArray ? null : res.nextCursor 
            } 
          }); 
        }
      })
      .catch(err => { 
        if (!cancelled) { 
          dispatch({ type: 'MESSAGES_LOAD_FAILED', sessionId: state.sessionId }); 
          showToast(err.message || 'Failed to load messages', 'error'); 
        } 
      });
    return () => { cancelled = true; };
  }, [state.sessionId]);

  const loadOlderMessages = useCallback(async () => {
    if (!state.hasMoreMessages || state.messagesLoading || !state.sessionId || !state.nextCursor) return;
    dispatch({ type: 'MESSAGES_LOADING', sessionId: state.sessionId });
    try {
      const res = await sessionService.getMessages(state.sessionId, state.nextCursor);
      dispatch({
        type: 'PREPEND_MESSAGES',
        payload: {
          messages: res.messages,
          hasMore: res.hasMore,
          nextCursor: res.nextCursor,
        }
      });
    } catch (err) {
      showToast(err.message || 'Failed to load older messages', 'error');
      dispatch({ type: 'MESSAGES_LOAD_FAILED', sessionId: state.sessionId });
    }
  }, [state.hasMoreMessages, state.messagesLoading, state.sessionId, state.nextCursor, showToast]);


  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [state.messages]);

  // Polls GET /api/sessions/{id}/suggested-questions every 2s until ingestion's
  // question-generation step reports READY or FAILED, then stops. Started after
  // a successful file/Wikipedia upload (see uploadPendingFiles / handleWikiSubmit).
  const pollSuggestedQuestions = useCallback((sessionId) => {
    if (suggestionsPollRef.current) {
      clearInterval(suggestionsPollRef.current);
      suggestionsPollRef.current = null;
    }

    const poll = async () => {
      try {
        const res = await attachmentService.getSuggestedQuestions(sessionId);
        if (res.status === 'READY') {
          dispatch({ type: 'SET_SUGGESTED_QUESTIONS', sessionId, payload: { questions: res.questions || [] } });
          clearInterval(suggestionsPollRef.current);
          suggestionsPollRef.current = null;
        } else if (res.status === 'FAILED') {
          clearInterval(suggestionsPollRef.current);
          suggestionsPollRef.current = null;
        }
        // NOT_STARTED / GENERATING: keep polling
      } catch {
        // Transient network errors shouldn't kill polling permanently, but stop
        // after this interval's tick — the next tick will simply retry.
      }
    };

    poll();
    suggestionsPollRef.current = setInterval(poll, 2000);
  }, []);

  useEffect(() => {
    return () => {
      if (suggestionsPollRef.current) {
        clearInterval(suggestionsPollRef.current);
        suggestionsPollRef.current = null;
      }
    };
  }, [state.sessionId]);

  const onDragEnter = useCallback((e) => { e.preventDefault(); dragCounterRef.current += 1; if (dragCounterRef.current === 1) setIsDragging(true); }, []);
  const onDragLeave = useCallback((e) => { e.preventDefault(); dragCounterRef.current -= 1; if (dragCounterRef.current === 0) setIsDragging(false); }, []);
  const onDragOver = useCallback((e) => { e.preventDefault(); }, []);
  const onDrop = useCallback((e) => { e.preventDefault(); dragCounterRef.current = 0; setIsDragging(false); const files = Array.from(e.dataTransfer.files); if (files.length > 0) addFiles(files); }, []);

  const addFiles = useCallback((files) => {
    const validFiles = [];
    for (const f of files) {
      const isExcel = f.type === 'application/vnd.ms-excel' || 
                      f.type === 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' || 
                      f.name.toLowerCase().endsWith('.xls') || 
                      f.name.toLowerCase().endsWith('.xlsx');
      
      if (isExcel) {
        showToast('Excel files are not supported.', 'error');
        continue;
      }
      if (f.size > 10 * 1024 * 1024) {
        showToast(`File ${f.name} exceeds the 10MB limit.`, 'error');
        continue;
      }
      validFiles.push({ file: f, name: f.name, status: 'pending' });
    }
    if (validFiles.length > 0) {
      setPendingFiles(prev => [...prev, ...validFiles]);
    }
  }, [showToast]);

  const removePendingFile = useCallback((index) => {
    setPendingFiles(prev => prev.filter((_, i) => i !== index));
  }, []);

  const uploadPendingFiles = useCallback(async (sessionId) => {
    const toUpload = pendingFiles.filter(f => f.status === 'pending');
    if (toUpload.length === 0) return;
    for (let i = 0; i < pendingFiles.length; i++) {
      if (pendingFiles[i].status !== 'pending') continue;
      setPendingFiles(prev => prev.map((f, idx) => idx === i ? { ...f, status: 'uploading' } : f));
      try {
        if (pendingFiles[i].type === 'wikipedia') {
          await attachmentService.uploadWikipedia(sessionId, pendingFiles[i].wikiUrl);
        } else {
          await attachmentService.upload(sessionId, pendingFiles[i].file);
        }
        setPendingFiles(prev => prev.map((f, idx) => idx === i ? { ...f, status: 'done' } : f));
      } catch {
        setPendingFiles(prev => prev.map((f, idx) => idx === i ? { ...f, status: 'error' } : f));
        showToast(`Failed to upload ${pendingFiles[i].name}`, 'error');
      }
    }
    setTimeout(() => setPendingFiles(prev => prev.filter(f => f.status !== 'done')), 2000);
    pollSuggestedQuestions(sessionId);
  }, [pendingFiles, showToast, pollSuggestedQuestions]);

  const handleSend = useCallback(async (e, overrideQuery) => {
    if (e) e.preventDefault();
    const query = (overrideQuery ?? input).trim();
    const hasPendingFiles = pendingFiles.some(f => f.status === 'pending');
    if (!query && !hasPendingFiles) return;
    if (state.isStreaming) return;
    setInput('');
    if (textareaRef.current) textareaRef.current.style.height = 'auto';
    let activeSessionId = state.sessionId;
    if (!activeSessionId) {
      try {
        let title = query.length <= 40 ? query : query.slice(0, 40) + '…';
        if (!title) {
          title = 'New Chat - ' + new Date().toLocaleString([], {
            month: 'short',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
          });
        }
        const created = await sessionService.create(title);
        activeSessionId = created.sessionId;
        addSession(created);
        dispatch({ type: 'SET_SESSION', payload: { sessionId: activeSessionId } });
        navigate(`/chat/${activeSessionId}`, { replace: true });
      } catch (err) { showToast(err.message || 'Failed to create session', 'error'); setInput(query); return; }
    }
    if (query) {
      const userMessage = { id: crypto.randomUUID(), role: 'USER', text: query, createdAt: new Date().toISOString(), status: 'complete' };
      const assistantPlaceholder = { id: crypto.randomUUID(), role: 'ASSISTANT', text: '', createdAt: new Date().toISOString(), status: 'streaming' };
      dispatch({ type: 'SEND_MESSAGE_OPTIMISTIC', sessionId: activeSessionId, payload: { userMessage, assistantPlaceholder } });

      try {
        if (hasPendingFiles) await uploadPendingFiles(activeSessionId);

        const jobResponse = await chatService.submitMessage(activeSessionId, query, selectedModel);

        await chatService.consumeStream(
          activeSessionId, jobResponse.messageId,
          (chunk) => dispatch({ type: 'APPEND_STREAM_CHUNK', sessionId: activeSessionId, payload: { messageId: assistantPlaceholder.id, chunk } }),
          (citations) => dispatch({ type: 'SET_CITATIONS', sessionId: activeSessionId, payload: { messageId: assistantPlaceholder.id, citations } }),
          (visuals) => dispatch({ type: 'SET_VISUALS', sessionId: activeSessionId, payload: { messageId: assistantPlaceholder.id, visuals } }),
          (progress) => dispatch({ type: 'UPDATE_PROGRESS', sessionId: activeSessionId, payload: { messageId: assistantPlaceholder.id, progress } }),
          (err) => { dispatch({ type: 'STREAM_ERROR', sessionId: activeSessionId, payload: { messageId: assistantPlaceholder.id } }); showToast(err.message || 'Stream error', 'error'); },
          () => {
            dispatch({ type: 'STREAM_DONE', sessionId: activeSessionId, payload: { messageId: assistantPlaceholder.id } });
            pollSuggestedQuestions(activeSessionId);
          },
          () => dispatch({ type: 'RESET_STREAM_TEXT', sessionId: activeSessionId, payload: { messageId: assistantPlaceholder.id } })
        );
      } catch (err) {
        dispatch({ type: 'STREAM_ERROR', sessionId: activeSessionId, payload: { messageId: assistantPlaceholder.id } });
        showToast(err.message || 'Failed to send', 'error');
      }
    } else {
      if (hasPendingFiles) await uploadPendingFiles(activeSessionId);
    }
  }, [input, pendingFiles, state.sessionId, state.isStreaming, navigate, addSession, showToast, uploadPendingFiles, selectedModel, pollSuggestedQuestions]);

  const handleWikiSubmit = async (e, directUrl = null, confirmedDirectIngest = false) => {
    if (e) e.preventDefault();
    const urlToUse = directUrl || wikiUrl.trim();
    if (!urlToUse) return;

    let activeSessionId = state.sessionId;
    if (!activeSessionId) {
      try {
        const title = 'New Chat - ' + new Date().toLocaleString([], {
          month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit',
        });
        const created = await sessionService.create(title);
        activeSessionId = created.sessionId;
        addSession(created);
        dispatch({ type: 'SET_SESSION', payload: { sessionId: activeSessionId } });
        navigate(`/chat/${activeSessionId}`, { replace: true });
      } catch (err) { showToast(err.message || 'Failed to create session', 'error'); return; }
    }

    // A raw URL the user just typed/pasted is shown as a single confirmable
    // preview row rather than ingested immediately - pressing Enter or clicking
    // "Search/Add" on a URL shouldn't silently upload it with no chance to
    // back out. Only once they explicitly click that preview row (directUrl
    // set + confirmedDirectIngest true, mirroring how a search-result click
    // already works below) does the actual ingestion happen.
    if (urlToUse.startsWith('http') && !confirmedDirectIngest) {
      const pageTitle = decodeURIComponent(urlToUse.substring(urlToUse.lastIndexOf('/') + 1)).replace(/_/g, ' ');
      setWikiSearchResults([{ title: pageTitle, url: urlToUse }]);
      return;
    }

    if (urlToUse.startsWith('http')) {
      // Confirmed — queue it like a regular pending attachment. The actual
      // upload happens in uploadPendingFiles, which only runs when the user
      // hits Send, not at confirmation time.
      const pageTitle = decodeURIComponent(urlToUse.substring(urlToUse.lastIndexOf('/') + 1)).replace(/_/g, ' ');
      setPendingFiles(prev => [...prev, { name: pageTitle, wikiUrl: urlToUse, type: 'wikipedia', status: 'pending' }]);

      setShowWikiInput(false);
      setWikiUrl('');
      setWikiSearchResults([]);
    } else {
      // Just text, so search
      if (directUrl) {
        // User clicked a search result — queue it the same way, awaiting Send.
        setPendingFiles(prev => [...prev, { name: urlToUse, wikiUrl: urlToUse, type: 'wikipedia', status: 'pending' }]);

        setShowWikiInput(false);
        setWikiUrl('');
        setWikiSearchResults([]);
      } else {
        setIsWikiSearching(true);
        try {
          const results = await attachmentService.searchWikipedia(activeSessionId, urlToUse);
          setWikiSearchResults(results || []);
        } catch (err) {
          showToast(err.message || 'Failed to search Wikipedia', 'error');
        } finally {
          setIsWikiSearching(false);
        }
      }
    }
  };

  const onKeyDown = (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(e); } };
  const onInputChange = (e) => { setInput(e.target.value); const ta = e.target; ta.style.height = 'auto'; ta.style.height = Math.min(ta.scrollHeight, 160) + 'px'; };

  const firstName = user?.name?.split(' ')[0] || 'there';
  const isNewChat = !state.sessionId;
  const userQuestions = state.messages.filter(m => m.role === 'USER' && m.text && m.text.trim().length > 0);

  const greetings = [
    `Hello, ${firstName}! How can I help you today?`,
    `Hey ${firstName}, what do you have in mind?`,
    `Welcome back, ${firstName}! Ready to dive in?`,
    `Hi ${firstName}, what are we working on today?`,
    `Good to see you, ${firstName}! How can I assist?`,
    `Hey there, ${firstName}! What's the plan for today?`,
    `Greetings, ${firstName}! What can I help you discover?`,
    `Hi ${firstName}! Let's get started. What's on your mind?`,
    `Hello ${firstName}, need any help with your projects?`,
    `Hey ${firstName}! What shall we explore today?`,
    `Hi ${firstName}, how can I make your day easier?`,
    `Welcome, ${firstName}! What are we tackling next?`
  ];

  return (
    <div
      className="flex-1 flex flex-col h-full overflow-hidden relative"
      style={{ backgroundColor: 'var(--color-bg-base)' }}
      onDragEnter={onDragEnter}
      onDragLeave={onDragLeave}
      onDragOver={onDragOver}
      onDrop={onDrop}
    >
      {isDragging && (
        <div className="absolute inset-0 z-50 flex flex-col items-center justify-center backdrop-blur-sm border-2 border-dashed border-blue-500/60 pointer-events-none"
          style={{ backgroundColor: 'color-mix(in srgb, var(--color-bg-base) 85%, transparent)' }}>
          <svg className="w-12 h-12 text-blue-400 mb-3 animate-bounce" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
            <path strokeLinecap="round" strokeLinejoin="round" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5m-13.5-9L12 3m0 0l4.5 4.5M12 3v13.5" />
          </svg>
          <p className="text-blue-500 text-base font-semibold">Drop files to upload</p>
          <p className="text-secondary text-xs mt-1">All files accepted except Excel (Max 10MB)</p>
        </div>
      )}

      <input
        ref={fileInputRef}
        type="file"
        multiple
        className="hidden"
        onChange={(e) => { if (e.target.files.length) addFiles(Array.from(e.target.files)); e.target.value = ''; }}
      />

      <header className="flex items-center gap-3 h-14 px-4 shrink-0 z-30 relative divider-vert"
        style={{ borderBottom: '1px solid var(--color-border)', backgroundColor: 'var(--color-bg-surface)' }}>
        <button
          className="md:hidden p-2 -ml-1 text-secondary rounded-xl interactive"
          onClick={openMobileSidebar}
        >
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
            <path strokeLinecap="round" strokeLinejoin="round" d="M4 6h16M4 12h16M4 18h16" />
          </svg>
        </button>

        <div className="flex-1 flex items-center justify-between min-w-0 gap-2">
          <div className="relative shrink-0 max-w-[45%] sm:max-w-none">
            <button
              type="button"
              onClick={() => setModelMenuOpen(prev => !prev)}
              className="flex items-center gap-1.5 px-2 sm:px-3 py-1.5 text-[15px] font-semibold text-primary rounded-xl interactive cursor-pointer select-none max-w-full"
            >
              <span className="hidden sm:inline">DocuMind</span>
              <span className="text-tertiary font-medium truncate max-w-[150px] sm:max-w-none">{displayModelName}</span>
              <svg className={`w-3.5 h-3.5 shrink-0 text-tertiary transition-transform duration-200 ${modelMenuOpen ? 'rotate-180' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
              </svg>
            </button>

            {modelMenuOpen && (
              <>
                <div className="fixed inset-0 z-40" onClick={() => setModelMenuOpen(false)} />
                <div className="absolute left-0 mt-2 z-50 w-[280px] max-w-[calc(100vw-3rem)] menu-popup py-1.5 animate-fade-in-up">
                  {models.map(m => {
                    const isSelected = m.id === selectedModel;
                    return (
                      <button
                        key={m.id}
                        type="button"
                        onClick={() => handleModelSelect(m.id)}
                        className="flex items-start w-full text-left px-4 py-2.5 interactive group cursor-pointer"
                      >
                        <div className="w-5 shrink-0 pt-0.5">
                          {isSelected && (
                            <svg className="w-3.5 h-3.5 text-blue-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="3">
                              <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 12.75l6 6 9-13.5" />
                            </svg>
                          )}
                        </div>
                        <div className="flex-1 min-w-0 pr-2">
                          <div className="flex items-center justify-between gap-2">
                            <span className="text-[13px] font-semibold text-primary group-hover:text-blue-500 transition-colors">
                              {m.name}
                            </span>
                            {m.isNew && (
                              <span className="px-2 py-0.5 text-[9px] font-medium bg-blue-500/10 text-blue-500 rounded-full shrink-0">New</span>
                            )}
                          </div>
                          <p className="text-[11px] text-secondary mt-0.5 leading-normal">{m.description}</p>
                        </div>
                      </button>
                    );
                  })}
                </div>
              </>
            )}
          </div>

          {!isNewChat && (
            <div className="flex items-center gap-1 sm:gap-2 relative min-w-0 justify-end">
              <div className="px-2 sm:px-3 py-1 sm:py-1.5 bg-[var(--color-bg-base)] border border-[var(--color-border)] rounded-lg max-w-[120px] sm:max-w-[300px] min-w-0 shrink">
                <h1 className="text-[11px] sm:text-[13px] font-medium text-secondary truncate leading-tight">
                  {session?.title || 'Loading…'}
                </h1>
              </div>
              
              <div className="relative">
                <button
                  type="button"
                  onClick={() => setShowQuestionsMenu(prev => !prev)}
                  className={`p-1 sm:p-1.5 rounded-md transition-colors shrink-0 ${showQuestionsMenu ? 'text-blue-500 bg-blue-500/10' : 'text-secondary hover:text-primary hover:bg-[var(--color-bg-surface-hover)]'}`}
                  title="Session History"
                >
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                  </svg>
                </button>
                
                {showQuestionsMenu && (
                  <>
                    <div className="fixed inset-0 z-40" onClick={() => setShowQuestionsMenu(false)} />
                    <div className="absolute top-full right-0 mt-2 z-50 w-64 md:w-80 max-h-[300px] overflow-y-auto menu-popup py-1.5 animate-fade-in-up">
                      <div className="px-3 py-2 text-xs font-semibold text-secondary uppercase tracking-wider border-b border-[var(--color-border)] mb-1 flex items-center justify-between">
                        <span>Questions Asked</span>
                        <span className="inline-flex items-center justify-center min-w-[20px] h-5 px-1.5 rounded-full bg-blue-500/15 text-blue-500 text-[11px] font-bold">{userQuestions.length}</span>
                      </div>
                      {userQuestions.length === 0 ? (
                        <div className="px-4 py-3 text-[12px] text-tertiary">No questions asked yet.</div>
                      ) : (
                        userQuestions.map((q, idx) => (
                          <button
                            key={idx}
                            onClick={() => {
                              setShowQuestionsMenu(false);
                              const filteredMessages = state.messages.filter(msg => !isUploadAnchorMessage(msg));
                              const targetIndex = filteredMessages.findIndex(m => m.id === q.id);
                              
                              if (virtuosoRef.current && targetIndex !== -1) {
                                virtuosoRef.current.scrollToIndex({
                                  index: targetIndex,
                                  align: 'center',
                                  behavior: 'smooth'
                                });
                              }
                            }}
                            className="flex flex-col w-full text-left px-3 py-2 hover:bg-[var(--color-bg-surface-hover)] transition-colors group"
                          >
                            <span className="text-[12px] text-primary line-clamp-2 group-hover:text-blue-500 transition-colors">
                              {q.text}
                            </span>
                            <span className="text-[10px] text-tertiary mt-0.5">
                              {new Date(q.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                            </span>
                          </button>
                        ))
                      )}
                    </div>
                  </>
                )}
              </div>

              <button
                type="button"
                onClick={() => setShowShareMenu(prev => !prev)}
                className="p-1 sm:p-1.5 text-secondary hover:text-primary hover:bg-[var(--color-bg-surface-hover)] rounded-md transition-colors shrink-0"
                title="Share options"
              >
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z" />
                </svg>
              </button>
              {showShareMenu && (
                <>
                  <div className="fixed inset-0 z-40" onClick={() => setShowShareMenu(false)} />
                  <div className="absolute top-full right-0 mt-2 z-50 w-48 menu-popup py-1.5 animate-fade-in-up">
                    <button
                      onClick={handleShareUrl}
                      className="flex items-center gap-2 w-full text-left px-4 py-2 text-[13px] text-primary hover:bg-[var(--color-bg-surface-hover)] transition-colors"
                    >
                      <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2"><path strokeLinecap="round" strokeLinejoin="round" d="M13.19 8.688a4.5 4.5 0 011.242 7.244l-4.5 4.5a4.5 4.5 0 01-6.364-6.364l1.757-1.757m13.35-.622l1.757-1.757a4.5 4.5 0 00-6.364-6.364l-4.5 4.5a4.5 4.5 0 001.242 7.244" /></svg>
                      Share public URL
                    </button>
                    <button
                      onClick={handleSendMail}
                      className="flex items-center gap-2 w-full text-left px-4 py-2 text-[13px] text-primary hover:bg-[var(--color-bg-surface-hover)] transition-colors"
                    >
                      <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2"><path strokeLinecap="round" strokeLinejoin="round" d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" /></svg>
                      Send via mail
                    </button>
                    <div className="h-px bg-[var(--color-border)] my-1" />
                    <button
                      onClick={handleExportMarkdown}
                      className="flex items-center gap-2 w-full text-left px-4 py-2 text-[13px] text-primary hover:bg-[var(--color-bg-surface-hover)] transition-colors"
                    >
                      <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2"><path strokeLinecap="round" strokeLinejoin="round" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" /></svg>
                      Export markdown
                    </button>
                    <button
                      onClick={handleExportPdf}
                      disabled={isExportingPdf}
                      className="flex items-center gap-2 w-full text-left px-4 py-2 text-[13px] text-primary hover:bg-[var(--color-bg-surface-hover)] transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {isExportingPdf ? (
                        <svg className="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2"><path strokeLinecap="round" strokeLinejoin="round" d="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0l3.181 3.183a8.25 8.25 0 0013.803-3.7M4.031 9.865a8.25 8.25 0 0113.803-3.7l3.181 3.182m0-4.991v4.99" /></svg>
                      ) : (
                        <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2"><path strokeLinecap="round" strokeLinejoin="round" d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" /><path strokeLinecap="round" strokeLinejoin="round" d="M12 11v6m-3-3h6" /></svg>
                      )}
                      {isExportingPdf ? 'Preparing PDF…' : 'Export PDF'}
                    </button>
                  </div>
                </>
              )}
            </div>
          )}
        </div>
      </header>

      <div className="flex-1 overflow-y-auto px-4 sm:pl-6 sm:pr-8 py-6" style={{ backgroundColor: 'var(--color-bg-base)' }}>
        {isNewChat ? (
          <div className="h-full flex flex-col items-center justify-center text-center px-4">
            {user?.profileImageUrl ? (
              <img
                src={user.profileImageUrl}
                alt="Profile"
                className="w-16 h-16 rounded-full object-cover shadow-sm ring-2 ring-white/10"
              />
            ) : (
              <div className="w-16 h-16 rounded-full bg-blue-500/10 flex items-center justify-center text-blue-500 text-2xl font-bold shadow-sm ring-2 ring-white/10">
                {user?.name?.charAt(0)?.toUpperCase() || 'U'}
              </div>
            )}
            <h2 className="mt-4 text-2xl font-bold text-primary max-w-lg leading-tight">
              {greetings[greetingIndex]}
            </h2>
            <p className="mt-2 text-[13px] text-tertiary max-w-xs leading-relaxed">
              Your AI-powered document assistant. Type a message below to get started.
            </p>
          </div>
        ) : (state.messagesLoading && state.messages.length === 0) ? (
          <div className="h-full flex items-center justify-center">
            <div className="flex flex-col items-center gap-3 text-secondary">
              <AccentureLoader />
              <span className="text-xs">Loading messages…</span>
            </div>
          </div>
        ) : (
          <Virtuoso
            ref={virtuosoRef}
            className="w-full h-full custom-scrollbar"
            data={state.messages.filter(msg => !isUploadAnchorMessage(msg))}
            firstItemIndex={Math.max(0, 1000000 - state.messages.length)}
            initialTopMostItemIndex={Math.max(0, state.messages.length - 1)}
            startReached={loadOlderMessages}
            followOutput="smooth"
            itemContent={(index, msg) => (
              <div className="max-w-2xl mx-auto py-6">
                <ChatMessage
                  msg={msg}
                  isStreaming={false}
                  setActiveCitation={setActiveCitation}
                />
              </div>
            )}
            components={{
              Header: () => (
                state.messagesLoading ? (
                  <div className="py-4 flex justify-center">
                    <AccentureLoader />
                  </div>
                ) : null
              ),
              Footer: () => (
                <div className="max-w-2xl mx-auto py-6 pb-8 space-y-12">
                  {state.streamingMessage && (
                    <ChatMessage
                      msg={state.streamingMessage}
                      isStreaming={true}
                      setActiveCitation={setActiveCitation}
                    />
                  )}
                  {state.suggestedQuestions.length > 0 && !state.isStreaming && (
                    <div className="flex items-start gap-2.5">
                      <div className="w-7 shrink-0" />
                      <div className="flex flex-col gap-2 max-w-[85%] animate-fade-in-up">
                        <span className="text-[11px] font-medium text-tertiary px-1">You might want to ask</span>
                        <div className="flex flex-wrap gap-2">
                          {state.suggestedQuestions.map((q, i) => (
                            <button
                              key={i}
                              type="button"
                              onClick={() => handleSend(null, q)}
                              className="px-3 py-2 rounded-xl border text-[12.5px] font-medium text-left interactive cursor-pointer transition-all hover:border-blue-500/50 hover:text-blue-500"
                              style={{ backgroundColor: 'var(--color-bg-subtle)', borderColor: 'var(--color-border)', color: 'var(--color-text-primary)' }}
                            >
                              {q}
                            </button>
                          ))}
                        </div>
                      </div>
                    </div>
                  )}
                  <div ref={bottomRef} />
                </div>
              )
            }}
          />
        )}
      </div>
      <div className="shrink-0 px-4 sm:px-6 pb-5 pt-3 relative"
        style={{ borderTop: '1px solid var(--color-border)', backgroundColor: 'var(--color-bg-surface)' }}>

        <div className="max-w-2xl mx-auto relative">
          {isInputOpen && (
            <button
              type="button"
              onClick={() => setIsInputOpen(false)}
              className="absolute -top-[34px] right-2 p-1.5 rounded-t-lg border-x border-t text-tertiary hover:text-secondary cursor-pointer transition-colors shadow-sm"
              style={{ borderColor: 'var(--color-border)', backgroundColor: 'var(--color-bg-surface)' }}
              title="Close input drawer"
            >
              <ChevronDownIcon />
            </button>
          )}

          {!isInputOpen && (
            <button
              type="button"
              onClick={() => setIsInputOpen(true)}
              className="fixed bottom-6 right-6 w-14 h-14 rounded-full shadow-2xl border flex items-center justify-center cursor-pointer transition-transform hover:scale-110 active:scale-95 z-50 animate-fade-in-up"
              style={{ borderColor: 'var(--color-border)', backgroundColor: 'var(--color-text-primary)' }}
              title="Open input drawer"
            >
              <Constellation className="w-14 h-14" invert={false} />
            </button>
          )}

          {isInputOpen && (
            <form onSubmit={handleSend} className="w-full">
              {pendingFiles.length > 0 && (
                <div className="flex flex-wrap gap-1.5 mb-2 px-1">
                  {pendingFiles.map((f, i) => (
                    <div
                      key={i}
                      className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-medium border transition-all ${f.status === 'uploading' ? 'bg-blue-500/10 border-blue-500/30 text-blue-500' :
                          f.status === 'done' ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-600' :
                            f.status === 'error' ? 'bg-red-500/10 border-red-500/30 text-red-500' :
                              'bg-subtle border-t text-secondary'
                        }`}
                      style={['pending'].includes(f.status) ? { backgroundColor: 'var(--color-bg-subtle)', borderColor: 'var(--color-border)' } : {}}
                    >
                      {f.status === 'uploading' && <div className="w-2.5 h-2.5 border border-blue-400/50 border-t-blue-400 rounded-full animate-spin" />}
                      {f.status === 'done' && <span>✓</span>}
                      {f.status === 'error' && <span>✗</span>}
                      <span className="max-w-[120px] truncate">{f.name}</span>
                      {(f.status === 'pending' || f.status === 'error') && (
                        <button type="button" onClick={() => removePendingFile(i)} className="text-tertiary hover:text-secondary transition-colors ml-0.5">
                          <XIcon />
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              )}

              {showWikiInput && (
                <div className="mb-2 flex flex-col gap-2 animate-fade-in-up">
                  <div className="p-3 rounded-2xl bg-surface border shadow-lg flex items-center gap-2" style={{ borderColor: 'var(--color-border)' }}>
                    <input
                      type="text"
                      value={wikiUrl}
                      onChange={(e) => {
                        setWikiUrl(e.target.value);
                        if (!e.target.value.trim()) {
                          setWikiSearchResults([]);
                        }
                      }}
                      placeholder="Paste Wikipedia URL or search term..."
                      className="flex-1 bg-transparent border-0 text-[13px] text-primary outline-none px-2"
                      autoFocus
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') handleWikiSubmit(e);
                        if (e.key === 'Escape') { setShowWikiInput(false); setWikiSearchResults([]); }
                      }}
                    />
                    <button
                      type="button"
                      onClick={(e) => handleWikiSubmit(e)}
                      disabled={isWikiSearching}
                      className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-3 py-1.5 rounded-lg text-xs font-medium transition-colors"
                    >
                      {isWikiSearching ? '...' : 'Search/Add'}
                    </button>
                  </div>

                  {wikiSearchResults.length > 0 && (
                    <div className="p-2 rounded-2xl bg-surface border shadow-lg flex flex-col gap-1" style={{ borderColor: 'var(--color-border)' }}>
                      <div className="px-2 py-1 text-xs text-secondary font-semibold">
                        {wikiSearchResults.length === 1 && wikiSearchResults[0].url ? 'Confirm and add this page:' : 'Select an article:'}
                      </div>
                      {wikiSearchResults.map((result, idx) => {
                        const isUrlPreview = typeof result === 'object' && result.url;
                        const label = isUrlPreview ? result.title : result;
                        return (
                          <button
                            key={idx}
                            type="button"
                            onClick={(e) => isUrlPreview
                              ? handleWikiSubmit(e, result.url, true)
                              : handleWikiSubmit(e, result)}
                            className="text-left px-3 py-2 text-[13px] text-primary hover:bg-blue-500/10 hover:text-blue-500 rounded-xl transition-colors interactive"
                          >
                            {label}
                          </button>
                        );
                      })}
                    </div>
                  )}
                </div>
              )}

              <div
                className="flex items-end gap-2.5 rounded-2xl px-4 py-3 transition-all"
                style={{
                  backgroundColor: 'var(--color-bg-input)',
                  border: '1px solid var(--color-border)',
                }}
                onFocus={(e) => e.currentTarget.style.borderColor = 'var(--color-accent)'}
                onBlur={(e) => e.currentTarget.style.borderColor = 'var(--color-border)'}
              >
                <div className="flex items-center">
                  <button
                    type="button"
                    onClick={() => fileInputRef.current?.click()}
                    className="p-1.5 text-tertiary hover:text-secondary rounded-lg interactive shrink-0"
                    title="Attach file"
                  >
                    <PaperclipIcon />
                  </button>
                  <button
                    type="button"
                    onClick={() => setShowWikiInput(!showWikiInput)}
                    className={`p-1.5 rounded-lg interactive shrink-0 transition-colors ${showWikiInput ? 'text-blue-500 bg-blue-500/10' : 'text-tertiary hover:text-secondary'}`}
                    title="Attach Wikipedia page"
                  >
                    <LinkIcon />
                  </button>
                </div>

                <textarea
                  ref={textareaRef}
                  value={input}
                  onChange={onInputChange}
                  onKeyDown={onKeyDown}
                  placeholder="Ask anything…"
                  rows={1}
                  disabled={state.isStreaming || state.messagesLoading}
                  autoFocus
                  className="flex-1 bg-transparent border-0 text-[13px] text-primary outline-none resize-none max-h-40 min-h-[22px] py-0.5 leading-relaxed disabled:opacity-50"
                  style={{ height: '22px', color: 'var(--color-text-primary)', caretColor: 'var(--color-accent)' }}
                />

                <button
                  type="submit"
                  disabled={(!input.trim() && !pendingFiles.some(f => f.status === 'pending')) || state.isStreaming || state.messagesLoading}
                  className="p-2 bg-blue-600 hover:bg-blue-500 disabled:opacity-25 disabled:cursor-not-allowed text-white rounded-xl transition-all active:scale-95 hover:scale-105 disabled:scale-100 shrink-0 cursor-pointer"
                >
                  {state.isStreaming
                    ? <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                    : <SendIcon />
                  }
                </button>
              </div>

              <p className="text-center text-[11px] text-tertiary mt-2 select-none">
                Enter to send &nbsp;·&nbsp; Shift+Enter for new line &nbsp;·&nbsp; Drop files anywhere
              </p>
            </form>
          )}
        </div>
      </div>

      <CitationDrawer citations={activeCitation} onClose={() => setActiveCitation(null)} />

      <Modal
        isOpen={showEmailModal}
        title="Share Session via Email"
        onClose={() => setShowEmailModal(false)}
        footer={
          <>
            <button
              onClick={() => setShowEmailModal(false)}
              className="px-4 py-2.5 text-xs font-semibold t-text-muted hover:t-text-main t-hover-bg rounded-xl transition-all cursor-pointer"
            >
              Cancel
            </button>
            <button
              onClick={submitShareEmail}
              disabled={!shareEmailAddress.trim()}
              className="px-4 py-2.5 text-xs font-semibold text-white bg-blue-600 hover:bg-blue-500 active:scale-95 disabled:scale-100 disabled:opacity-40 rounded-xl shadow-lg shadow-blue-500/20 transition-all cursor-pointer"
            >
              Send
            </button>
          </>
        }
      >
        <div className="space-y-4">
          <div className="flex flex-col space-y-1.5">
            <label className="text-[10px] font-bold text-tertiary uppercase tracking-wider">
              Email Address
            </label>
            <input
              type="email"
              value={shareEmailAddress}
              onChange={e => setShareEmailAddress(e.target.value)}
              placeholder="user@example.com"
              className="w-full px-4 py-3 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl focus:outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500 transition-all text-sm t-text-main"
              autoFocus
              onKeyDown={e => e.key === 'Enter' && shareEmailAddress.trim() && submitShareEmail()}
            />
          </div>
        </div>
      </Modal>
    </div>
  );
}