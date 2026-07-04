import { useState, useEffect } from 'react';
import { Document, Page, pdfjs } from 'react-pdf';
import 'react-pdf/dist/Page/AnnotationLayer.css';
import 'react-pdf/dist/Page/TextLayer.css';

import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';

// Set up worker
pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;

export default function PdfViewerModal({ url, boundingBoxes, targetPage, onClose }) {
  const [numPages, setNumPages] = useState(null);
  const [pageNumber, setPageNumber] = useState(targetPage || 1);
  const [scale, setScale] = useState(1.2);

  useEffect(() => {
    if (targetPage) setPageNumber(targetPage);
  }, [targetPage]);

  // Prevent background scrolling
  useEffect(() => {
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = 'auto';
    };
  }, []);

  function onDocumentLoadSuccess({ numPages }) {
    setNumPages(numPages);
  }

  // Calculate overlay dimensions based on the scale and bounding box coordinates
  // Note: PDF coordinates are usually from bottom-left, but react-pdf page might be top-left depending on the stripper logic.
  // Assuming our stripper gives top-left coordinates relative to page cropbox.
  const renderHighlights = () => {
    if (!boundingBoxes || boundingBoxes.length === 0) return null;
    
    return boundingBoxes
      .filter(box => box.page === pageNumber)
      .map((box, idx) => (
        <div
          key={idx}
          className="absolute bg-yellow-300/40 rounded-sm"
          style={{
            left: `${box.x * scale}px`,
            top: `${box.y * scale}px`,
            width: `${box.width * scale}px`,
            height: `${box.height * scale}px`,
            pointerEvents: 'none', // Allow clicking through
            zIndex: 10
          }}
        />
      ));
  };

  return (
    <div className="fixed inset-0 z-[100] flex flex-col bg-gray-900 overflow-hidden">
      <div className="flex flex-col w-full h-full overflow-hidden">
        
        {/* Header */}
        <div className="flex flex-col sm:flex-row items-center justify-between px-2 sm:px-4 py-2 sm:py-3 bg-gray-800 border-b border-gray-700 shrink-0 gap-2 sm:gap-0 relative">
          
          {/* Mobile Close Button */}
          <button onClick={onClose} className="sm:hidden absolute top-1.5 right-1.5 p-1.5 hover:bg-gray-700 rounded-lg text-gray-400 hover:text-white transition-colors z-10">
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
          </button>

          <div className="flex items-center w-full sm:w-auto justify-center sm:justify-start">
            <h3 className="text-white font-bold text-sm hidden sm:block">Source Document Viewer</h3>
          </div>

          <div className="flex items-center gap-2 sm:gap-4 w-full sm:w-auto justify-center">
            
            {/* Page Controls */}
            <div className="flex items-center gap-1 sm:gap-2 bg-gray-900 rounded-md px-1 sm:px-2 py-1">
              <button 
                onClick={() => setPageNumber(p => Math.max(1, p - 1))}
                disabled={pageNumber <= 1}
                className="p-1 rounded text-gray-400 hover:text-white hover:bg-gray-700 disabled:opacity-50 disabled:hover:bg-transparent transition-colors"
              >
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
              </button>
              <span className="text-[11px] sm:text-xs text-gray-300 font-medium w-14 sm:w-16 text-center">
                Page {pageNumber} of {numPages || '--'}
              </span>
              <button 
                onClick={() => setPageNumber(p => Math.min(numPages || p, p + 1))}
                disabled={pageNumber >= (numPages || 1)}
                className="p-1 rounded text-gray-400 hover:text-white hover:bg-gray-700 disabled:opacity-50 disabled:hover:bg-transparent transition-colors"
              >
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" /></svg>
              </button>
            </div>

            {/* Zoom Controls */}
            <div className="flex items-center gap-1 sm:gap-2 bg-gray-900 rounded-md px-1 sm:px-2 py-1">
              <button onClick={() => setScale(s => Math.max(0.5, s - 0.2))} className="p-1 rounded text-gray-400 hover:text-white hover:bg-gray-700 transition-colors">
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20 12H4" /></svg>
              </button>
              <span className="text-[11px] sm:text-xs text-gray-300 font-medium w-10 sm:w-12 text-center">{Math.round(scale * 100)}%</span>
              <button onClick={() => setScale(s => Math.min(3.0, s + 0.2))} className="p-1 rounded text-gray-400 hover:text-white hover:bg-gray-700 transition-colors">
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" /></svg>
              </button>
            </div>

            {/* Desktop Close Button */}
            <button onClick={onClose} className="hidden sm:block p-1.5 hover:bg-gray-700 rounded-lg text-gray-400 hover:text-white transition-colors">
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
            </button>
          </div>
        </div>

        {/* Viewer Body */}
        <div className="flex-1 overflow-auto bg-gray-950 p-0 sm:p-6 custom-scrollbar">
          <div className="relative shadow-2xl bg-white text-gray-900 min-h-full sm:min-h-[800px] w-max mx-auto">
            <Document
              file={typeof url === 'string' && url.startsWith('http') ? { url, httpHeaders: { 'ngrok-skip-browser-warning': 'true' } } : url}
              onLoadSuccess={onDocumentLoadSuccess}
              loading={<div className="flex items-center justify-center h-full text-white">Loading PDF...</div>}
              error={<div className="flex items-center justify-center h-full text-red-400">Failed to load PDF. Cross-Origin (CORS) might be blocking the request.</div>}
            >
              <Page 
                pageNumber={pageNumber} 
                scale={scale} 
                className="relative"
                renderTextLayer={true}
                renderAnnotationLayer={false}
              >
                {renderHighlights()}
              </Page>
            </Document>
          </div>
        </div>

      </div>
    </div>
  );
}
