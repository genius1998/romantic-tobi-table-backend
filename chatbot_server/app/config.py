import os
from pathlib import Path
from dotenv import load_dotenv

load_dotenv(override=True)

# ── 경로 ──────────────────────────────────────────────
BASE_DIR = Path(__file__).resolve().parent.parent          # chatbot_server/
CHATBOT_DATA_DIR = BASE_DIR.parent / "chatbot_data"        # ../chatbot_data/
DOCS_DIR = CHATBOT_DATA_DIR / "docs"
JSON_DIR = CHATBOT_DATA_DIR / "json"
VECTORSTORE_DIR = BASE_DIR / "data" / "vectorstore"

# ── Spring Boot ───────────────────────────────────────
SPRING_BASE_URL: str = os.getenv("SPRING_BASE_URL", "http://localhost:8080")

# ── LLM ───────────────────────────────────────────────
LLM_PROVIDER: str = os.getenv("LLM_PROVIDER", "local")

# Local LLM
LOCAL_LLM_URL: str = os.getenv("LOCAL_LLM_URL", "http://169.213.5.157:3000/api/chat")
LOCAL_LLM_MODEL: str = os.getenv("LOCAL_LLM_MODEL", "gpt-oss:20b")
LOCAL_LLM_TIMEOUT: int = int(os.getenv("LOCAL_LLM_TIMEOUT", "120"))
LOCAL_LLM_REFERER: str = os.getenv("LOCAL_LLM_REFERER", "http://169.213.5.157:3000/")
LOCAL_LLM_USER_AGENT: str = os.getenv("LOCAL_LLM_USER_AGENT", "Mozilla/5.0")

# Anthropic (선택)
ANTHROPIC_API_KEY: str = os.getenv("ANTHROPIC_API_KEY", "")
ANTHROPIC_MODEL: str = os.getenv("ANTHROPIC_MODEL", "claude-haiku-4-5-20251001")

# OpenAI (선택)
OPENAI_API_KEY: str = os.getenv("OPENAI_API_KEY", "")
OPENAI_MODEL: str = os.getenv("OPENAI_MODEL", "gpt-4o-mini")

# ── RAG / 임베딩 ──────────────────────────────────────
EMBEDDING_MODEL: str = os.getenv(
    "EMBEDDING_MODEL",
    "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
)
CHUNK_SIZE: int = int(os.getenv("CHUNK_SIZE", "800"))
CHUNK_OVERLAP: int = int(os.getenv("CHUNK_OVERLAP", "120"))
TOP_K: int = int(os.getenv("TOP_K", "5"))
