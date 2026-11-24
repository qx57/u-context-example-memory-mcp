# Память агента для тестирования REST API

Локальный MCP-сервер хранит опыт одного агента, который создаёт и запускает функциональные тесты REST API. Перед прогоном агент ищет похожие попытки по сервису и окружению. После прогона он сохраняет наблюдение с описанием подхода, ожидаемым и фактическим результатом и счётчиками проверок. Сервер определяет исход `SUCCEEDED`, `FAILED` или `INCONCLUSIVE`. Оператор может пересмотреть оценку, архивировать запись и восстановить её.

Spring Boot предоставляет MCP через `stdio`, REST API и веб-интерфейс. Дочерний Python-адаптер использует Mem0 OSS для записи и семантического поиска. Qdrant хранит наблюдения и векторы между сессиями. OpenAI Responses формирует краткое резюме, а OpenAI Embeddings векторизует записи и поисковые запросы. Если генерация резюме недоступна, адаптер собирает текст по шаблону.

## Быстрый старт

Понадобятся JDK 21 или новее, Python 3.12, Docker с Compose v2, `openssl`, MCP-клиент с поддержкой `stdio` и ключ проекта OpenAI API для записи и поиска. Выполняйте команды из корня репозитория.

### Соберите сервер и установите зависимости адаптера

```bash
./mvnw clean package
python3.12 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

### Запустите Qdrant

```bash
if [ ! -e deployment/.env ]; then
  (
    umask 077
    printf 'QDRANT_API_KEY=%s\nQDRANT_READ_ONLY_API_KEY=%s\n' \
      "$(openssl rand -hex 32)" "$(openssl rand -hex 32)" > deployment/.env
  )
fi

docker compose --env-file deployment/.env -f deployment/compose.yaml up -d
curl --fail --retry 10 --retry-delay 1 --retry-all-errors http://127.0.0.1:6333/healthz
```

Команда проверки использует порт `6333` по умолчанию. Если в `deployment/.env` задан другой `QDRANT_HTTP_PORT`, подставьте его в команду и укажите адрес с этим портом в `MCP_MEMORY_QDRANT_URL` в настройках MCP-клиента.

### Подключите MCP-сервер

Добавьте `test-memory` в локальные настройки клиента. Пример использует формат `mcpServers`. Подставьте абсолютные пути к Java, собранному JAR и Python из `.venv`, а также ключ OpenAI и значение `QDRANT_API_KEY` из `deployment/.env`. Для `MCP_MEMORY_OPERATOR_TOKEN` сгенерируйте отдельный токен командой `openssl rand -hex 32`. Храните ключи и токен в локальной конфигурации вне версионного контроля.

```json
{
  "mcpServers": {
    "test-memory": {
      "command": "/absolute/path/to/java",
      "args": ["-jar", "/absolute/path/to/project/target/test-memory-mcp-0.1.0.jar"],
      "env": {
        "MCP_MEMORY_PYTHON": "/absolute/path/to/project/.venv/bin/python",
        "OPENAI_API_KEY": "OPENAI_PROJECT_KEY",
        "QDRANT_API_KEY": "QDRANT_WRITE_KEY",
        "MCP_MEMORY_OPERATOR_TOKEN": "LOCAL_OPERATOR_TOKEN"
      }
    }
  }
}
```

Адаптер создаёт коллекцию `rest_test_memory_v1` при первом обращении и проверяет схему при подключении к существующей коллекции. По умолчанию для векторизации используется `text-embedding-3-small` с размерностью `1536`. Полный список настроек, включая порт HTTP и модель для генерации резюме, приведён в [документации запуска](docs/003.Implementation.md#настройки-сервера-в-mcp-клиенте).

## Как пользоваться

1. Перед подготовкой теста вызовите `search_test_observations` с кратким вопросом. Поиск ограничен активными записями указанного сервиса и окружения. По умолчанию он включает успешные и неудачные попытки.
2. После прогона вызовите `record_test_observation` с обезличенным описанием и результатами проверок. При повторе записи передайте тот же `observationId` и те же данные. Для новой попытки создайте новый UUID.

Ниже пример аргументов поиска. Полный пример записи и схемы ответов приведены в [контрактах MCP и REST](docs/002.Contracts.md).

```json
{
  "serviceId": "catalog",
  "environment": "local",
  "query": "Как проверить ответ при запросе отсутствующей позиции?",
  "method": "GET",
  "route": "/items/{id}"
}
```

Пока MCP-клиент держит сервер запущенным, откройте [интерфейс оператора](http://127.0.0.1:8085/ui/memories) и введите `MCP_MEMORY_OPERATOR_TOKEN`. Для запросов к `/api/v1/` передайте этот токен в заголовке `Authorization: Bearer <токен>`.

| REST | Операция |
|---|---|
| `PUT /api/v1/memories/{observationId}` | Записать наблюдение. UUID передаётся в пути |
| `POST /api/v1/memories/search` | Найти похожие наблюдения |
| `GET /api/v1/memories` | Получить страницу записей с фильтрами |
| `GET /api/v1/memories/{observationId}` | Прочитать запись и получить `ETag` |
| `PATCH /api/v1/memories/{observationId}` | Изменить оценку или статус записи с заголовком `If-Match` |
| `GET /health` | Проверить Qdrant и последнее известное состояние операций OpenAI без токена |

## Сквозная проверка

Соберите JAR, установите зависимости Python и запустите локальный Qdrant, как описано выше. [Тест](tests/smoke.py) самостоятельно запускает JAR, проверяет MCP, REST и веб-интерфейс, использует временную коллекцию Qdrant и подставляет локальную имитацию OpenAI API. Для этого теста ключ OpenAI не нужен.

```bash
(
  set -a
  . deployment/.env
  set +a
  export MCP_MEMORY_QDRANT_URL="http://127.0.0.1:${QDRANT_HTTP_PORT:-6333}"
  .venv/bin/python tests/smoke.py
)
```

## Документация

- [Исходная идея и ограничения](docs/000.Project_idea.md)
- [Архитектура и модель памяти](docs/001.Architecture.md)
- [Контракты MCP и REST, правила оценки и ошибки](docs/002.Contracts.md)
- [Настройки, проверка и обслуживание](docs/003.Implementation.md)
