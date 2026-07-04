import React, { memo } from 'react';
import ThinkingProcess from './ThinkingProcess.jsx';
import ImageDeck from './ImageDeck.jsx';
import Streaming from './streaming.jsx';

const ChatMessage = memo(function ChatMessage({ msg, isStreaming, setActiveCitation }) {
  const isBot = msg.role === 'ASSISTANT';

  return (
    <div className={`flex items-start gap-2.5 ${isBot ? '' : 'flex-row-reverse'}`}>
      <div className={`flex flex-col gap-1 max-w-[85%] ${isBot ? '' : 'items-end'}`}>
        <div className={`px-4 py-3 rounded-2xl text-[13px] leading-relaxed ${isBot
            ? 'text-primary'
            : 'bg-blue-600 text-white rounded-tr-sm'
          } ${msg.status === 'error' ? 'border-red-500/40' : ''}`}
          style={isBot ? {} : {}}>
          
          {isBot && msg.progressEvents && msg.progressEvents.length > 0 && (
            <ThinkingProcess
              events={msg.progressEvents}
              isComplete={msg.status !== 'streaming' || msg.text.length > 0}
            />
          )}
          
          {isBot && msg.visuals && msg.visuals.length > 0 && (
            <ImageDeck images={msg.visuals} />
          )}
          
          {isBot
            ? <Streaming
              text={msg.text}
              isStreaming={isStreaming}
              citations={msg.citations}
              onCitationClick={setActiveCitation}
            />
            : <p className="whitespace-pre-wrap">{msg.text}</p>
          }
          
          {msg.citations && msg.citations.length > 0 && (() => {
            const grouped = msg.citations.reduce((acc, cite) => {
              const existing = acc.find(c => c.sourceName === cite.sourceName);
              if (existing) {
                existing.count = (existing.count || 1) + 1;
              } else {
                acc.push({ ...cite, count: 1 });
              }
              return acc;
            }, []);
            return (
              <div className="mt-3 pt-3" style={{ borderTop: '1px solid var(--color-border)' }}>
                <div className="flex flex-wrap gap-2">
                  {grouped.map((group, idx) => (
                    <button
                      key={idx}
                      onClick={() => setActiveCitation(group)}
                      title={group.excerpt}
                      className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl border interactive cursor-pointer"
                      style={{ backgroundColor: 'var(--color-bg-subtle)', borderColor: 'var(--color-border)' }}
                    >
                      {group.isImage ? (
                        <svg className="w-3.5 h-3.5 text-blue-500 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14M14 8h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
                        </svg>
                      ) : (
                        <svg className="w-3.5 h-3.5 text-blue-500 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                        </svg>
                      )}
                      <span className="text-[12px] font-medium truncate max-w-[160px]" style={{ color: 'var(--color-text-primary)' }}>
                        {group.sourceName}
                      </span>
                      {group.count > 1 && (
                        <span className="ml-1 text-[10px] font-bold text-blue-600 dark:text-blue-400 bg-blue-500/10 px-1.5 py-0.5 rounded-md">
                          ×{group.count}
                        </span>
                      )}
                    </button>
                  ))}
                </div>
              </div>
            );
          })()}
        </div>
        {msg.status === 'error' && (
          <span className="text-[11px] text-red-400">Failed to send</span>
        )}
      </div>
    </div>
  );
}, (prevProps, nextProps) => {
  // If either was or is streaming, re-render
  if (prevProps.isStreaming || nextProps.isStreaming) return false;
  
  // Custom deep comparison for properties that change
  return (
    prevProps.msg.id === nextProps.msg.id &&
    prevProps.msg.text === nextProps.msg.text &&
    prevProps.msg.status === nextProps.msg.status &&
    // Simple length check for complex objects since they only append in this architecture
    (prevProps.msg.progressEvents?.length || 0) === (nextProps.msg.progressEvents?.length || 0) &&
    (prevProps.msg.citations?.length || 0) === (nextProps.msg.citations?.length || 0) &&
    (prevProps.msg.visuals?.length || 0) === (nextProps.msg.visuals?.length || 0)
  );
});

export default ChatMessage;
