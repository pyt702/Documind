# DocuMind Backend

The backend for **DocuMind**, a robust Spring Boot application providing AI capabilities and document processing for the platform.

## 🚀 Technologies Used
- **Java 21**
- **Spring Boot 3.5.6** (WebFlux, Data JPA, Security)
- **Spring AI** (Google GenAI integration, Pinecone Store)
- **PostgreSQL** (Relational Database)
- **Redis** (Caching)
- **JWT** (Authentication)
- **PDFBox** (PDF processing)
- **ONNX Runtime & HuggingFace Tokenizers** (Local processing/embeddings)
- **Cloudinary** (Media storage)
- **Jsoup & CommonMark** (HTML and Markdown processing)

## 📦 Getting Started

### Prerequisites
- **Java 21 JDK** installed.
- **Maven** (optional, you can use the provided wrapper `mvnw`).
- **PostgreSQL** and **Redis** instances running locally or remotely.

### Configuration
Create an `.env` file in the root of the `backend` folder and populate it with your environment variables (e.g., Database credentials, JWT Secret, Gemini API Keys, Pinecone API Keys). 
*Note: `.env` and `gemini_api_keys.txt` are excluded via `.gitignore`.*

### Pre-downloading Dependencies (Faster Startup)
Unlike Python which uses `requirements.txt`, Java uses Maven (`pom.xml`) to manage dependencies. To download all dependencies in advance (so your application starts up faster later), you can run the provided scripts:
- **Windows**: Double click `download_dependencies.bat` or run `.\download_dependencies.bat`
- **Mac/Linux**: Run `./download_dependencies.sh` (you may need to run `chmod +x download_dependencies.sh` first)
Alternatively, you can just run `.\mvnw.cmd dependency:go-offline`.

### Running the Application

1. Navigate to the backend directory:
   ```bash
   cd backend
   ```
2. Run the Spring Boot application using the Maven wrapper:
   ```bash
   .\mvnw.cmd spring-boot:run
   ```
   *(On Unix/Mac use `./mvnw spring-boot:run`)*

The API will typically start on `http://localhost:8080`.

## 📚 API Documentation
This project includes OpenAPI integration. Once the application is running, you can access the API documentation UI at:
- `http://localhost:8080/webjars/swagger-ui/index.html` (or the configured swagger-ui endpoint)

## 🧪 Testing
To run the test suite:
```bash
.\mvnw.cmd test
```