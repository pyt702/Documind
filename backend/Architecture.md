# DocuMind System Architecture

This document provides a high-level overview of the core architectural pipelines that power DocuMind's intelligence, specifically focusing on how data is ingested, how the AI remembers context, and how it retrieves information.

---

## 1. Data Ingestion Architecture

The ingestion pipeline is responsible for taking raw user uploads and turning them into searchable, AI-ready data.

1. **File Parsing**: When a user uploads a document (e.g., a PDF), the backend uses `Apache PDFBox` to extract not just the raw text, but also the geometric coordinates (`boundingBoxes`) of every word. For HTML/Markdown, `Jsoup` and `CommonMark` are used.
2. **Chunking**: The extracted text is split into smaller, overlapping segments (chunks). This is crucial because LLMs have context limits, and we only want to feed them the most relevant paragraphs, not a 500-page book all at once.
3. **Embedding Generation**: Each chunk is sent to the Google Gemini API to generate a "vector embedding"—a high-dimensional mathematical representation of the chunk's semantic meaning.
4. **Vector Storage**: These vectors, along with metadata (like the source filename, page number, and bounding boxes), are stored in the primary **Pinecone Vector Database**.

---

## 2. Memory Architecture (Short-Term vs. Long-Term)

To make DocuMind feel like a natural conversational partner, it utilizes a dual-memory system so it never loses context of what you are talking about.

### Short-Term Memory
- Powered by the primary **PostgreSQL** database.
- The system automatically pulls the last ~10 messages of the current chat session and feeds them to the LLM. This handles immediate follow-up questions like *"Can you summarize that last point?"*

### Long-Term Semantic Memory (Topic Episodes)
- Powered by a **secondary Pinecone Index**.
- **Topic Shift Detection**: As you chat, a background worker monitors the conversation. If you suddenly change the subject (e.g., you were talking about "Financial Q3 results" and suddenly ask about "Employee Onboarding"), the system detects a *Topic Shift*.
- **Episode Summarization**: When a shift is detected, the previous topic's messages are bundled into an "Episode", summarized by the LLM, embedded, and stored in the secondary Pinecone index.
- If you ever bring up "Financial Q3 results" again weeks later, the system semantically retrieves that old episode from Pinecone and injects it into the prompt, giving the AI "long-term memory" of your past discussions.

---

## 3. Retrieval-Augmented Generation (RAG) Architecture

When a user asks a question, how does DocuMind find the answer? It uses an advanced RAG pipeline with a "Retrieve-then-Rerank" strategy.

1. **Intent Routing**: The query is first analyzed. Is the user just saying "hello"? Are they asking about a specific file, or searching the entire knowledge base? The system dynamically alters its search strategy based on intent.
2. **Dense Retrieval (Pinecone)**: The user's question is converted into an embedding. Pinecone is queried to find the top `K` document chunks that are mathematically closest in meaning to the question.
3. **Cross-Encoder Re-ranking (ONNX)**: Vector search is fast but sometimes misses nuances. The initial results from Pinecone are passed to a local `ms-marco-MiniLM-L-6-v2` ONNX model. This model scores exactly how well each chunk answers the specific question, re-ordering the results from most-to-least relevant.
4. **Final Generation**: The top re-ranked chunks are injected into a highly engineered system prompt. The Google Gemini LLM reads these chunks and generates the final natural language answer, automatically citing which chunk provided which piece of information.
