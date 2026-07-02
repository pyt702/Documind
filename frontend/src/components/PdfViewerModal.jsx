import { useState, useEffect } from 'react';
import { Document, Page, pdfjs } from 'react-pdf';
import 'react-pdf/dist/Page/AnnotationLayer.css';
import 'react-pdf/dist/Page/TextLayer.css';

// Set up worker
pdfjs.GlobalWorkerOptions.workerSrc = `//unpkg.com/pdfjs-dist@${pdfjs.version}/build/pdf.worker.min.js`;

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
          className="absolute bg-yellow-400 mix-blend-multiply rounded-sm"
          style={{
            left: `${box.x * scale}px`,
            top: `${box.y * scale}px`,
            width: `${box.width * scale}px`,
            height: `${box.height * scale}px`,
            opacity: 0.4,
            pointerEvents: 'none', // Allow clicking through
            zIndex: 10
          }}
        />
      ));
  };

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/80 backdrop-blur-sm p-4">
      <div className="bg-gray-900 rounded-xl shadow-2xl flex flex-col w-full max-w-5xl h-full max-h-[90vh] overflow-hidden border border-gray-700">
        
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 bg-gray-800 border-b border-gray-700 shrink-0">
          <div className="flex items-center gap-4">
            <h3 className="text-white font-bold text-sm">Source Document Viewer</h3>
            <div className="flex items-center gap-2 bg-gray-900 rounded-md px-2 py-1">
              <button 
                onClick={() => setPageNumber(p => Math.max(1, p - 1))}
                disabled={pageNumber <= 1}
                className="text-gray-400 hover:text-white disabled:opacity-50"
              >
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
              </button>
              <span className="text-xs text-gray-300 font-medium w-16 text-center">
                Page {pageNumber} of {numPages || '--'}
              </span>
              <button 
                onClick={() => setPageNumber(p => Math.min(numPages || p, p + 1))}
                disabled={pageNumber >= (numPages || 1)}
                className="text-gray-400 hover:text-white disabled:opacity-50"
              >
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" /></svg>
              </button>
            </div>
            
            <div className="flex items-center gap-2 bg-gray-900 rounded-md px-2 py-1 ml-4">
              <button onClick={() => setScale(s => Math.max(0.5, s - 0.2))} className="text-gray-400 hover:text-white"><svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20 12H4" /></svg></button>
              <span className="text-xs text-gray-300 w-12 text-center">{Math.round(scale * 100)}%</span>
              <button onClick={() => setScale(s => Math.min(3.0, s + 0.2))} className="text-gray-400 hover:text-white"><svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" /></svg></button>
            </div>
          </div>

          <button onClick={onClose} className="p-1.5 hover:bg-gray-700 rounded-lg text-gray-400 hover:text-white transition-colors">
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
          </button>
        </div>

        {/* Viewer Body */}
        <div className="flex-1 overflow-auto bg-gray-950 p-6 flex justify-center custom-scrollbar">
          <div className="relative shadow-2xl bg-white" style={{ minHeight: '800px' }}>
            <Document
              file={url}
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
