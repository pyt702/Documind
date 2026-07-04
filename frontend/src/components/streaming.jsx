import React, { useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import katex from 'katex';
import 'katex/dist/katex.min.css';
import AccentureLoader from './AccentureLoader.jsx';

/**
 * Pre-renders all LaTeX math to HTML using KaTeX BEFORE the text
 * enters the markdown parser. This is the same strategy Claude and
 * ChatGPT use — the markdown parser never sees dollar signs or
 * backslashes, so remark-gfm can't strip them inside tables.
 */
function preRenderMath(text) {
  if (!text) return text;

  // 1. Block math: $$...$$
  text = text.replace(/\$\$([\s\S]+?)\$\$/g, (_, tex) => {
    try {
      const html = katex.renderToString(tex.trim(), {
        displayMode: true,
        throwOnError: false,
      });
      return `<div class="math-block">${html}</div>`;
    } catch {
      return `$$${tex}$$`;
    }
  });

  // 2. Inline math: $...$
  //    Skips currency patterns like $100 or $5.99
  text = text.replace(/(?<!\$)\$(?!\$)((?:[^$\\]|\\.)+?)\$(?!\$)/g, (match, tex) => {
    if (/^\d[\d,.]*$/.test(tex.trim())) return match;
    try {
      return katex.renderToString(tex.trim(), {
        displayMode: false,
        throwOnError: false,
      });
    } catch {
      return match;
    }
  });

  return text;
}

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
        
        // Fix LLM markdown spacing bugs (e.g., "** Nataraja:**" or "**Text **")
        replaced = replaced.replace(/\*\*\s*([^*]+?)\s*\*\*/g, '**$1**');
        
        // Fix missing spaces after headers (e.g., "###**The..." -> "### **The...")
        replaced = replaced.replace(/(^|\n)(#{1,6})(?=[^\s#])/g, '$1$2 ');

        // Pre-render math to HTML before markdown parsing
        replaced = preRenderMath(replaced);
        
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
        } else {
          return <span className="text-gray-500 dark:text-gray-400 text-[11px] font-medium mx-0.5 align-super">[{citeIndex}]</span>;
        }
      }
      return <a {...props} target="_blank" rel="noopener noreferrer" className="text-blue-500 hover:underline" />;
    }
  }), [citations, onCitationClick]);

  return (
    <div className="streaming-markdown prose prose-sm dark:prose-invert max-w-none text-[14.5px] leading-relaxed break-words">
      {completedText && (
        <ReactMarkdown 
          remarkPlugins={[remarkGfm]} 
          rehypePlugins={[rehypeRaw]}
          components={components}
        >
          {completedText}
        </ReactMarkdown>
      )}
      {streamingLine && (
        <span className="streaming-line" dangerouslySetInnerHTML={{ __html: streamingLine }} />
      )}
      {isStreaming && (
        <AccentureLoader className="w-7 h-7 align-middle ml-2" style={{ transform: 'translateY(-3px)' }} />
      )}
    </div>
  );
}