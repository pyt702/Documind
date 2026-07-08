# DocuMind Frontend

The frontend for **DocuMind**, a React and Vite-based web application. It serves as the user interface for an AI-powered document analysis and Q&A system.

## 🚀 Technologies Used
- **React 19**
- **Vite** (Build tool)
- **TailwindCSS** (Styling)
- **React Router** (Navigation)
- **Mermaid & KaTeX** (Diagrams and math rendering)
- **React PDF** (PDF viewing capabilities)
- **Axios** (API communication)

## 📦 Getting Started

### Prerequisites
Make sure you have Node.js (v18+) and npm installed.

### Installation
1. Navigate to the frontend directory:
   ```bash
   cd frontend
   ```
2. Install the dependencies:
   ```bash
   npm install
   ```

### Running the Development Server
```bash
npm run dev
# or
npm start
```
The application will be accessible at the port specified in your console (usually `http://localhost:5173`).

## 🛠️ Scripts
- `npm start` - Starts the Vite development server.
- `npm run build` - Builds the application for production.
- `npm run lint` - Runs ESLint to check for code issues.
- `npm run preview` - Locally previews the production build.

## ⚙️ Environment Variables
Create a `.env` file in the root of the `frontend` folder based on your backend configuration. Ensure that any sensitive keys or local overrides are kept out of source control.
