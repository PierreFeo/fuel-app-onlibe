FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# dev — с pytest и ruff; prod (docker-compose.prod.yml) собирает с INSTALL_DEV=false
ARG INSTALL_DEV=true

# Сначала только pyproject.toml — слой с зависимостями кэшируется между сборками
COPY pyproject.toml ./
RUN mkdir app && touch app/__init__.py \
    && if [ "$INSTALL_DEV" = "true" ]; then pip install -e ".[dev]"; else pip install -e .; fi

COPY . .

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
