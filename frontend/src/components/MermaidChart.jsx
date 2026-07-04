import React, { useEffect, useState, memo } from 'react';
import mermaid from 'mermaid';

// Initialize mermaid once
mermaid.initialize({
  startOnLoad: false,
  theme: 'dark',
  securityLevel: 'loose',
  fontFamily: 'inherit',
  suppressErrorRendering: true,
});

const MermaidChart = memo(({ chart, isStreaming }) => {
  const [error, setError] = useState(false);
  const [svgContent, setSvgContent] = useState('');

  useEffect(() => {
    let isMounted = true;
    const id = `mermaid-${Math.random().toString(36).substr(2, 9)}`;

    const renderChart = async () => {
      try {
        setError(false);
        const { svg } = await mermaid.render(id, chart);
        if (isMounted) {
          setSvgContent(svg);
        }
      } catch (err) {
        if (isMounted) {
          setError(true);
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
    <div 
      className="mermaid-chart flex justify-center py-6 overflow-x-auto w-full my-4 bg-black/20 rounded-xl border border-white/5 shadow-inner"
      dangerouslySetInnerHTML={{ __html: svgContent }}
    />
  );
});

export default MermaidChart;
