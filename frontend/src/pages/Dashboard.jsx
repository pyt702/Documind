import { useState, useRef, useEffect } from 'react';
import { useNavigate, useOutletContext } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import { useSessions } from '../context/SessionsContext.jsx';
import { sessionService } from '../services/sessionService.js';
import { preferenceService } from '../services/preferenceService.js';
import { useToast } from '../context/ToastContext.jsx';

const SendIcon = () => (
  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M6 12L3.269 3.126A59.768 59.768 0 0121.485 12 59.77 59.77 0 013.27 20.876L5.999 12zm0 0h7.5" />
  </svg>
);

export default function Dashboard() {
  const { user, updateUser } = useAuth();
  const { addSession } = useSessions();
  const { showToast } = useToast();
  const navigate = useNavigate();
  const { openMobileSidebar } = useOutletContext();

  const [input, setInput] = useState('');
  const [creating, setCreating] = useState(false);
  const textareaRef = useRef(null);

  const [modelMenuOpen, setModelMenuOpen] = useState(false);
  const [selectedModel, setSelectedModel] = useState(() => {
    return localStorage.getItem('selectedModel') || 'GEMINI_3_1_FLASH_LITE';
  });
  const [models, setModels] = useState([]);

  useEffect(() => {
    const fetchModels = async () => {
      try {
        const availableModels = await preferenceService.getModels();
        console.log(availableModels)
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

  useEffect(() => {
    const onUpdated = (e) => { if (e?.detail) updateUser(e.detail); };
    window.addEventListener('profile-updated', onUpdated);
    return () => window.removeEventListener('profile-updated', onUpdated);
  }, [updateUser]);

  const handleSend = async (e) => {
    e.preventDefault();
    const query = input.trim();
    if (!query || creating) return;

    try {
      setCreating(true);

      const title = query.length <= 40 ? query : query.slice(0, 40) + '…';

      const session = await sessionService.create(title);

      addSession(session);

      navigate(`/chat/${session.sessionId}`, {
        state: { firstMessage: query },
      });
    } catch (err) {
      showToast(err.message || 'Failed to create session', 'error');
      setCreating(false);
    }
  };

  const onKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend(e);
    }
  };

  const onInputChange = (e) => {
    setInput(e.target.value);
    const ta = e.target;
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 160) + 'px';
  };

  const firstName = user?.name?.split(' ')[0] || 'there';

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden">

      <header className="flex items-center h-14 px-4 border-b t-border shrink-0 z-30 relative t-bg-panel">
        <div className="flex items-center gap-1.5">
          <button
            className="md:hidden p-2 -ml-1 t-text-muted hover:t-text-main rounded-xl t-hover-bg transition-all"
            onClick={openMobileSidebar}
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
              <path strokeLinecap="round" strokeLinejoin="round" d="M4 6h16M4 12h16M4 18h16" />
            </svg>
          </button>
          <div className="relative">
            <button
              onClick={() => setModelMenuOpen(prev => !prev)}
              className="flex items-center gap-1.5 px-3 py-1.5 text-[15px] font-semibold t-text-main t-hover-bg rounded-xl transition-all cursor-pointer select-none"
            >
              DocuMind <span className="t-text-faint font-medium">{displayModelName}</span>
              <svg className={`w-3.5 h-3.5 text-slate-500 transition-transform duration-200 ${modelMenuOpen ? 'rotate-180' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
              </svg>
            </button>

            {modelMenuOpen && (
              <>
                <div className="fixed inset-0 z-40" onClick={() => setModelMenuOpen(false)} />
                <div className="absolute left-0 mt-2 z-50 w-[280px] t-bg-menu t-border-soft border rounded-2xl shadow-2xl py-1.5 animate-fade-in-up" style={{ boxShadow: 'var(--shadow-elev)' }}>
                  {models.map(m => {
                    const isSelected = m.id === selectedModel;
                    return (
                      <button
                        key={m.id}
                        onClick={() => handleModelSelect(m.id)}
                        className="flex items-start w-full text-left px-4 py-2.5 t-hover-bg transition-all group cursor-pointer"
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
                            <span className="text-[13px] font-semibold t-text-main group-hover:text-blue-500 transition-colors">
                              {m.name}
                            </span>
                            {m.isNew && (
                              <span className="px-2 py-0.5 text-[9px] font-medium t-bg-hover t-text-muted rounded-full shrink-0">
                                New
                              </span>
                            )}
                          </div>
                          <p className="text-[11px] t-text-muted mt-0.5 leading-normal">
                            {m.description}
                          </p>
                        </div>
                      </button>
                    );
                  })}
                </div>
              </>
            )}
          </div>
        </div>
      </header>

      <div className="flex-1 flex flex-col items-center justify-center px-6 overflow-y-auto">
        <div className="text-center mb-10 select-none">
          <div className="w-16 h-16 rounded-3xl bg-gradient-to-br from-blue-500/15 to-indigo-500/15 border border-blue-500/20 flex items-center justify-center mx-auto mb-6 shadow-[0_0_80px_rgba(59,130,246,0.07)]">
            <svg className="w-8 h-8 text-blue-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
              <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z" />
            </svg>
          </div>
          <h1 className="text-2xl sm:text-3xl font-bold t-text-main mb-3 tracking-tight">
            Hello, {firstName} 👋
          </h1>
          <p className="t-text-faint text-[13px] sm:text-sm max-w-xs mx-auto leading-relaxed">
            Your AI-powered document assistant.<br />Type a message below to get started.
          </p>
        </div>
        <form onSubmit={handleSend} className="w-full max-w-xl">
          <div className="flex items-end gap-3 input-bg rounded-2xl px-4 py-3 transition-all focus-within:border-blue-500/40 focus-within:shadow-[0_0_0_2px_rgba(59,130,246,0.15)] shadow-xl">
            <textarea
              ref={textareaRef}
              value={input}
              onChange={onInputChange}
              onKeyDown={onKeyDown}
              placeholder="Ask anything…"
              rows={1}
              disabled={creating}
              autoFocus
              className="flex-1 bg-transparent border-0 text-[13px] t-text-main outline-none placeholder:t-text-faint resize-none max-h-40 min-h-[22px] py-0.5 leading-relaxed disabled:opacity-50"
              style={{ height: '22px' }}
            />
            <button
              type="submit"
              disabled={!input.trim() || creating}
              className="p-2 bg-blue-600 hover:bg-blue-500 disabled:opacity-25 disabled:cursor-not-allowed text-white rounded-xl transition-all active:scale-95 hover:scale-105 disabled:scale-100 shrink-0 cursor-pointer"
            >
              {creating ? (
                <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
              ) : (
                <SendIcon />
              )}
            </button>
          </div>
          <p className="text-center text-[11px] t-text-faint mt-2.5 select-none">
            Enter to send &nbsp;·&nbsp; Shift+Enter for new line
          </p>
        </form>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 mt-8 w-full max-w-xl">
          {[
            { icon: '📄', label: 'Summarize a document', prompt: 'Can you summarize the key points of a document for me?' },
            { icon: '💬', label: 'Ask questions about content', prompt: 'I have some content I want to ask questions about.' },
            { icon: '✍️', label: 'Explain a concept', prompt: 'Can you explain a concept to me in simple terms?' },
            { icon: '🔍', label: 'Analyze and compare', prompt: 'Help me analyze and compare some information.' },
          ].map(({ icon, label, prompt }) => (
            <button
              key={label}
              onClick={() => setInput(prompt)}
              className="flex items-center gap-3 text-left px-4 py-3 t-bg-hover hover:t-bg-active t-border border rounded-xl text-[13px] t-text-muted hover:t-text-main transition-all cursor-pointer group"
            >
              <span className="text-base">{icon}</span>
              <span className="group-hover:t-text-main transition-colors">{label}</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
