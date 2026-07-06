import React, { useEffect, useState, memo } from 'react';
import mermaid from 'mermaid';
import Modal from './Modal.jsx';

// Initialize mermaid once
mermaid.initialize({
  startOnLoad: false,
  theme: 'dark',
  securityLevel: 'loose',
  fontFamily: 'inherit',
  suppressErrorRendering: true,
});

const MermaidChart = memo(({ chart, isStreaming }) => {
  const [error, setError] = useState(null);
  const [svgContent, setSvgContent] = useState('');
  const [isModalOpen, setIsModalOpen] = useState(false);

  useEffect(() => {
    let isMounted = true;
    const id = `mermaid-${Math.random().toString(36).substr(2, 9)}`;

    const renderChart = async () => {
      try {
        setError(null);
        let trimmedChart = chart
          .replace(/“/g, '"')
          .replace(/”/g, '"')
          .replace(/‘/g, "'")
          .replace(/’/g, "'")
          .trim();
        // Mermaid 11 check: validate syntax before rendering to catch errors 
        // without getting the default error SVG bomb returned.
        const isValid = await mermaid.parse(trimmedChart, { suppressErrors: false });
        if (isValid !== false) {
          const { svg } = await mermaid.render(id, trimmedChart);
          if (isMounted) {
            setSvgContent(svg);
          }
        }
      } catch (err) {
        console.error("Mermaid Parse Error:", err);
        if (isMounted) {
          setError(err.message || String(err));
        }
      }
    };

    if (chart && chart.trim() && !isStreaming) {
      renderChart();
    }

    return () => {
      isMounted = false;
    };
  }, [chart, isStreaming]);

  // If streaming, just show the raw code block gracefully without attempting to render
  if (isStreaming) {
    return (
      <div className="my-4">
        <div className="p-4 rounded-xl text-sm font-mono overflow-x-auto whitespace-pre bg-black/20 border border-white/5 text-gray-300">
          {chart}
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="my-4">
        <div className="text-red-400 text-xs mb-1 px-1 font-medium">Unable to render diagram (Invalid syntax):</div>
        <div className="p-4 rounded-xl text-[10px] font-mono overflow-x-auto whitespace-pre bg-red-900/50 border border-red-500/20 text-red-200 mb-2">
          {String(error)}
        </div>
        <div className="p-4 rounded-xl text-sm font-mono overflow-x-auto whitespace-pre bg-red-500/10 border border-red-500/20 text-red-400">
          {chart}
        </div>
      </div>
    );
  }

  if (!svgContent) {
    return <div className="animate-pulse h-32 bg-white/5 rounded-xl my-4 w-full" />;
  }

  return (
    <>
      <div className="relative group my-4">
        <div 
          className="mermaid-chart flex justify-center py-6 overflow-x-auto w-full bg-black/20 rounded-xl border border-white/5 shadow-inner"
          dangerouslySetInnerHTML={{ __html: svgContent }}
        />
        <button
          onClick={() => setIsModalOpen(true)}
          className="absolute top-2 right-2 p-2 bg-black/40 hover:bg-black/60 text-white rounded-lg opacity-0 group-hover:opacity-100 transition-opacity cursor-pointer border border-white/10 backdrop-blur-sm"
          title="Enlarge Diagram"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7" />
          </svg>
        </button>
      </div>

      <Modal isOpen={isModalOpen} onClose={() => setIsModalOpen(false)} title="Diagram Viewer" size="xl">
        <div 
          className="mermaid-chart flex justify-center p-4 overflow-auto w-full bg-black/10 rounded-xl min-h-[50vh]"
          dangerouslySetInnerHTML={{ __html: svgContent }}
        />
      </Modal>
    </>
  );
});

export default MermaidChart;
