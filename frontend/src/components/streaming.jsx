import React, { useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';
import 'katex/dist/katex.min.css';
import AccentureLoader from './AccentureLoader.jsx';

export default function Streaming({ text, isStreaming, citations, onCitationClick }) {
  const { completedText, streamingLine } = useMemo(() => {
    if (!text) return { completedText: '', streamingLine: '' };
    const parts = text.split(/(```[\s\S]*?```)/g);
    const processedText = parts.map((part, index) => {

      if (index % 2 === 0) {
        let replaced = part.replace(/\[CITE:\s*([\d,\s]+)\]/gi, (match, p1) => {
          const ids = p1.split(',').map(s => s.trim()).filter(Boolean);
          return ids.map(id => `[${id}](#cite-${id})`).join(' ');
        });
        
        // Fix LLM markdown spacing bugs where it adds spaces inside bold tags (e.g., "** Nataraja:**")
        // This regex finds ** followed by spaces, captures the text, and removes the extra spaces.
        replaced = replaced.replace(/\*\*\s+([^*]+?)\s*\*\*/g, '**$1**');
        
        return replaced;
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
              className="inline-flex items-center justify-center text-blue-600 dark:text-indigo-400 text-[11px] font-medium mx-0.5 align-super hover:text-blue-800 dark:hover:text-indigo-300 transition-colors cursor-pointer"
              title={`Source: ${citation.sourceName}`}
            >
              [{citeIndex}]
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
        <ReactMarkdown 
          remarkPlugins={[remarkGfm, remarkMath]} 
          rehypePlugins={[rehypeKatex]}
          components={components}
        >
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