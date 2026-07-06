const BASE_URL = import.meta.env.VITE_API_URL || '';

export const chatService = {
  submitMessage: async (sessionId, message, model) => {
    const response = await fetch(`${BASE_URL}/api/chat/${sessionId}/message`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      credentials: 'include',
      body: JSON.stringify({ message, model }),
    });

    if (!response.ok) {
      if (response.status === 401) {
        const refreshRes = await fetch(`${BASE_URL}/auth/refresh`, {
          method: 'POST',
          credentials: 'include'
        });
        if (refreshRes.ok) {
          const retryRes = await fetch(`${BASE_URL}/api/chat/${sessionId}/message`, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
            },
            credentials: 'include',
            body: JSON.stringify({ message, model }),
          });
          if (!retryRes.ok) {
            window.dispatchEvent(new Event('auth-expired'));
            throw new Error('Session expired. Please log in again.');
          }
          return retryRes.json();
        } else {
          window.dispatchEvent(new Event('auth-expired'));
          throw new Error('Session expired. Please log in again.');
        }
      }
      throw new Error(`HTTP error! status: ${response.status}`);
    }
    return response.json();
  },

  consumeStream: async (sessionId, messageId, onChunk, onCitations, onVisuals, onProgress, onError, onComplete, onRetry) => {
    const streamUrl = `${BASE_URL}/api/chat/${sessionId}/stream/${messageId}`;
    try {
      const response = await fetch(streamUrl, {
        method: 'GET',
        headers: {
          'Accept': 'text/event-stream',
          'ngrok-skip-browser-warning': 'true',
        },
        credentials: 'include',
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';

      while (true) {
        const { value, done } = await reader.read();

        if (value) {
          buffer += decoder.decode(value, { stream: !done });
          const parts = buffer.split('\n\n');
          buffer = parts.pop() || '';

          for (const part of parts) {
            const lines = part.split('\n');
          let eventType = 'message';
          let eventData = [];
          for (const line of lines) {
            if (line.startsWith('event:')) {
              eventType = line.substring(6).trim();
            } else if (line.startsWith('data:')) {
              eventData.push(line.substring(5));
            }
          }
          if (eventData.length > 0) {
            if (eventType === 'citations') {
              try {
                const citations = JSON.parse(eventData.join('\n'));
                if (onCitations) onCitations(citations);
              } catch (e) {
                console.error('Failed to parse citations', e);
              }
            } else if (eventType === 'visuals') {
              try {
                const visuals = JSON.parse(eventData.join('\n'));
                if (onVisuals) onVisuals(visuals);
              } catch (e) {
                console.error('Failed to parse visuals', e);
              }
            } else if (eventType === 'progress') {
              try {
                const progressData = JSON.parse(eventData.join('\n'));
                if (onProgress) onProgress(progressData);
              } catch (e) {
                console.error('Failed to parse progress', e);
              }
            } else if (eventType === 'scope_expansion') {
              try {
                const expansionData = JSON.parse(eventData.join('\n'));
                if (onProgress) {
                  onProgress({
                    id: crypto.randomUUID(),
                    stage: 'RETRIEVAL',
                    status: 'WARN',
                    message: 'No sufficient evidence found'
                  });
                  onProgress({
                    id: crypto.randomUUID(),
                    stage: 'RETRIEVAL',
                    status: 'INFO',
                    message: 'Expanding to entire knowledge base'
                  });
                }
              } catch (e) {
                console.error('Failed to parse scope expansion', e);
              }
            } else if (eventType === 'done') {
              if (onComplete) onComplete();
              return; // End stream
            } else if (eventType === 'retry') {
              if (onRetry) onRetry();
            } else {
              onChunk(eventData.join('\n'));
            }
          }
        }
        }
        
        if (done) {
          if (buffer.trim()) {
            const lines = buffer.split('\n');
            let eventType = 'message';
            let eventData = [];
            for (const line of lines) {
              if (line.startsWith('event:')) {
                eventType = line.substring(6).trim();
              } else if (line.startsWith('data:')) {
                eventData.push(line.substring(5));
              }
            }
            if (eventData.length > 0) {
              if (eventType === 'done') {
                if (onComplete) onComplete();
                return;
              } else if (eventType !== 'citations' && eventType !== 'visuals' && eventType !== 'progress' && eventType !== 'scope_expansion' && eventType !== 'retry') {
                onChunk(eventData.join('\n'));
              }
            }
          }
          break;
        }
      }

      if (onComplete) onComplete();
    } catch (error) {
      console.error('Error in consumeStream:', error);
      if (onError) onError(error);
      throw error;
    }
  }
};
