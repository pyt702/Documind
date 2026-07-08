# DocuMind

Welcome to **DocuMind**!

🔗 **Repository Link:** [https://github.com/pyt702/Documind](https://github.com/pyt702/Documind)

## What is DocuMind?
DocuMind is an intelligent, AI-powered document analysis and Q&A platform designed to transform how you interact with large text files. Instead of manually scrolling through hundreds of pages of PDFs, reports, or research papers, DocuMind allows you to simply upload your documents and have a conversational interface with them. 

You can ask questions, extract specific data points, summarize entire sections, and get instant answers. But DocuMind doesn't just generate text—it backs up its answers by highlighting the exact source paragraphs and pages within the original document, giving you complete confidence in the accuracy of the information.

### Key Features
- **Conversational Document Analysis:** Chat naturally with your uploaded documents.
- **Accurate Citations & Highlighting:** Every answer comes with a direct link to the source document, automatically opening the PDF and highlighting exactly where the AI found the answer.
- **Contextual Memory:** The AI remembers the context of your ongoing conversation, allowing for natural, multi-turn follow-up questions.
- **Secure & Fast:** Built on a robust backend architecture utilizing vector databases for ultra-fast semantic search and retrieval.

---

## Technical Overview

This project is divided into two main components:
1. **Frontend**: A sleek, modern React and Vite-based web application.
2. **Backend**: A robust Java Spring Boot application handling document processing and AI integration.

### Project Structure & Documentation

To easily navigate and run the project, please refer to the following documentation files:
- **Root README** (`README.md`): This file, providing a high-level overview of the project.
- **Frontend Setup** (`frontend/Setup.md`): Contains instructions on how to set up, build, and run the React frontend.
- **Backend Setup** (`backend/Setup.md`): Contains instructions on how to set up, configure, and run the Spring Boot backend.
- **Services Info** (`frontend/Services_Info.md` & `backend/Services_Info.md`): Explains why each third-party tool and library was chosen for this project.

## Environment Variables & Third-Party Services

Before running the backend, you must configure the `.env` file located in the `backend/` directory. We have provided a template with empty values in `backend/.env`. You will need to connect the following third-party services and provide your own API keys/credentials:

1. **PostgreSQL Database** (e.g., Supabase, RDS) for relational data storage (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`).
2. **Google Gemini API** for generative AI features (`GEMINI_API_KEY`).
3. **SMTP Mail Server** (e.g., Gmail, SendGrid) for email notifications (`MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`).
4. **Pinecone** for vector database storage (`PINECONE_MEMORY_KEY`, `PINECONE_MEMORY_INDEX_NAME`, `PINECONE_API_KEY`).
5. **Cloudinary** for media and file storage (`CLOUDINARY_URL`).
6. **Redis** for caching (`REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`).
7. **JWT Secret** - Please generate a secure random string for JWT authentication (`JWT_SECRET`).

---

Once the `.env` values are configured, please proceed to `backend/Setup.md` and `frontend/Setup.md` to start the respective servers.
