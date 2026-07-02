import React, { useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import AccentureLoader from './AccentureLoader.jsx';

export default function Streaming({ text, isStreaming, citations, onCitationClick }) {
  const { completedText, streamingLine } = useMemo(() => {
    if (!text) return { completedText: '', streamingLine: '' };
    const parts = text.split(/(```[\s\S]*?```)/g);
    const processedText = parts.map((part, index) => {

      if (index % 2 === 0) {
        return part.replace(/\[CITE:\s*([\d,\s]+)\]/gi, (match, p1) => {
          const ids = p1.split(',').map(s => s.trim()).filter(Boolean);
          return ids.map(id => `[${id}](#cite-${id})`).join(' ');
        });
      }
      return part;
    }).join('');

    if (!isStreaming) return { completedText: processedText, streamingLine: '' };

    const lastNewline = processedText.lastIndexOf('\n');
    if (lastNewline === -1) {
      return { completedText: '', streamingLine: processedText };
    }

    return {
      completedText: processedText.slice(0, lastNewline + 1),
      streamingLine: processedText.slice(lastNewline + 1),
    };
  }, [text, isStreaming]);

  const components = useMemo(() => ({
    a: ({ node, ...props }) => {
      if (props.href && props.href.startsWith('#cite-')) {
        const citeIndex = parseInt(props.href.replace('#cite-', ''), 10);
        const citation = citations && citations[citeIndex - 1];
        if (citation) {
          return (
            <button
              type="button"
              onClick={(e) => { e.preventDefault(); onCitationClick(citation); }}
              className="inline-flex items-center justify-center w-[18px] h-[18px] rounded-full bg-blue-100 text-blue-700 dark:bg-indigo-500/30 dark:text-indigo-200 dark:ring-1 dark:ring-indigo-400/50 text-[10px] font-bold mx-0.5 align-text-top translate-y-[-2px] hover:bg-blue-200 dark:hover:bg-indigo-500/50 transition-all shadow-sm"
              title={`Source: ${citation.sourceName}`}
            >
              {citeIndex}
            </button>
          );
        }
      }
      return <a {...props} target="_blank" rel="noopener noreferrer" className="text-blue-500 hover:underline" />;
    }
  }), [citations, onCitationClick]);

  return (
    <div className="streaming-markdown prose prose-sm dark:prose-invert max-w-none text-[14.5px] leading-relaxed break-words">
      {completedText && (
        <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
          {completedText}
        </ReactMarkdown>
      )}
      {streamingLine && (
        <span className="streaming-line">{streamingLine}</span>
      )}
      {isStreaming && (
        <AccentureLoader className="w-7 h-7 align-middle ml-2" style={{ transform: 'translateY(-3px)' }} />
      )}
    </div>
  );
}