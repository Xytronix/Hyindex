# Hyindex

Hyindex builds searchable indexes from Hytale source code and documentation, then serves them over MCP for agent workflows.

Public fork of the discontinued [Hyve IntelliJ plugin](https://plugins.jetbrains.com/plugin/30299-hyve/versions/stable), maintained as a standalone indexer and MCP server.

## Quick Start

1. Download `hyve-knowledge-indexer.jar` and `hyve-knowledge-mcp.jar` from the [latest release](https://github.com/Xytronix/Hyindex/releases/latest).
2. Run interactive setup:

```bash
java -jar hyve-knowledge-indexer.jar init
```

3. Index and serve:

```bash
java -jar hyve-knowledge-indexer.jar --patchline release
java -jar hyve-knowledge-mcp.jar
```

Non-interactive examples:

```bash
# OpenAI
java -jar hyve-knowledge-indexer.jar init --non-interactive \
  --provider openai \
  --embedding-url https://api.openai.com \
  --api-key sk-... \
  --code-model text-embedding-3-large \
  --text-model text-embedding-3-large \
  --git-token ghp_... \
  --force

# Voyage (direct API or any Voyage-protocol endpoint)
java -jar hyve-knowledge-indexer.jar init --non-interactive \
  --provider voyage \
  --embedding-url https://api.voyageai.com \
  --api-key pa-... \
  --code-model voyage-code-3 \
  --text-model voyage-4-large \
  --force

# OpenAI-compatible custom endpoint
java -jar hyve-knowledge-indexer.jar init --non-interactive \
  --provider openai \
  --embedding-url http://localhost:8000 \
  --code-model Qwen/Qwen3-Embedding-8B \
  --text-model Qwen/Qwen3-Embedding-8B \
  --force
```

## Changing config and PAT

All runtime settings live in one file:

```text
~/.hyve/knowledge/mcp-config.json
```

Created by `init`. Indexes live under `~/.hyve/knowledge/versions/{slug}/`.

After you edit the config re-run the indexer and restart the MCP server:

| Want to change | Edit field |
| --- | --- |
| GitHub PAT | `gitToken` |
| Source repo URL | `gitRepoUrl` |
| Embedding provider | `embeddingProvider` |
| Embedding API URL | `embeddingBaseUrl` |
| Embedding API key | `embeddingApiKey` |
| Code embeddings | `embeddingCodeModel` |
| Docs / gamedata / UI chunks | `embeddingTextModel` |
| Optional output dims | `embeddingDimensions` |
| Enable reranker | `rerankerEnabled` |
| Reranker model | `rerankerModel` |
| Reranker candidate pool | `rerankerTopN` |

Or re-run setup and overwrite:

```bash
java -jar hyve-knowledge-indexer.jar init --force
```

Optional env overrides: `HYVE_EMBEDDING_PROVIDER`, `HYVE_EMBEDDING_BASE_URL`, `HYVE_EMBEDDING_API_KEY`, `HYVE_EMBEDDING_CODE_MODEL`, `HYVE_EMBEDDING_TEXT_MODEL`, `HYVE_EMBEDDING_DIMENSIONS`, `HYVE_ACTIVE_VERSION`, `HYVE_INDEX_PATH`.

## Embedding models

Hyindex uses **two** embedding models:

- `embeddingCodeModel` — source-code corpus
- `embeddingTextModel` — documentation, gamedata, client UI, and other non-code chunks

Optional Voyage-compatible rerank (off by default): set `rerankerEnabled` to `true`. Uses `rerankerModel` (default `rerank-2.5`) over the top `rerankerTopN` candidates via the same `embeddingBaseUrl` / `embeddingApiKey`.

### Provider matrix

| Provider | Protocol | Recommended models |
| --- | --- | --- |
| `openai` (default) | OpenAI `POST {base}/v1/embeddings` | `text-embedding-3-large`, `text-embedding-3-small` |
| `voyage` | Voyage `/v1/embeddings` (+ `/v1/contextualizedembeddings` for `voyage-context-*`) | code: `voyage-code-3`; text: `voyage-4-large` (alts: `voyage-4`, `voyage-4-lite`, `voyage-context-3`, `voyage-context-4`, `voyage-finance-2`, `voyage-law-2`) |
| `cohere` | Cohere `POST {base}/v1/embed` + `input_type` | `embed-v4.0` (alt: `embed-multilingual-v3.0`) |
| `gemini` | Native Google `:embedContent` / `:batchEmbedContents` | `gemini-embedding-001` (alt: `gemini-embedding-2`, `text-embedding-004`) |
| `jina` | OpenAI-compatible + `task` + `late_chunking` | `jina-embeddings-v3` |
| `mistral` | OpenAI-compatible `/v1/embeddings` | code: `codestral-embed-2505`; text: `mistral-embed` |
| `mixedbread` / `mxbai` | OpenAI-compatible `/v1/embeddings` | `mxbai-embed-large` |
| `ollama` | Ollama `/api/embed` | code: `qwen3-embedding:8b`; text: `nomic-embed-text-v2-moe` |
| any other name | OpenAI-compatible `/v1/embeddings` | e.g. `Qwen/Qwen3-Embedding-8B`, `BAAI/bge-m3` |
| `local` | Optional ONNX plugin jar | `all-minilm-l6-v2-q` |

Notes:

- OpenAI / Gemini / Voyage / Jina support optional `embeddingDimensions` (Matryoshka / output dims).
- Jina documents use `task=retrieval.passage` + `late_chunking=true`; queries use `task=retrieval.query`.
- Voyage `voyage-context-*` models call `/v1/contextualizedembeddings` (each text treated as a one-chunk document unless you group chunks upstream).
- For Google’s OpenAI-compatible shim, set `embeddingProvider=gemini` and `embeddingBaseUrl` to `https://generativelanguage.googleapis.com/v1beta/openai`.
- Local ONNX is **not** bundled and can be included with the following command:

```bash
java -cp hyve-knowledge-indexer.jar:hyve-embeddings-local.jar \
  com.hyve.knowledge.cli.IndexerMainKt --patchline release
```

## MCP Config

Example (`~/.hyve/knowledge/mcp-config.json`):

```json
{
  "embeddingProvider": "voyage",
  "embeddingBaseUrl": "https://api.voyageai.com",
  "embeddingApiKey": "",
  "embeddingCodeModel": "voyage-code-3",
  "embeddingTextModel": "voyage-4-large",
  "embeddingDimensions": null,
  "rerankerEnabled": false,
  "rerankerModel": "rerank-2.5",
  "rerankerTopN": 50,
  "gitRepoUrl": "https://github.com/HypixelStudios/hytale-shared-source.git",
  "gitToken": null,
  "activeVersion": null,
  "retentionCount": 3
}
```

Template: [`mcp-config.example.json`](mcp-config.example.json).

Wire the MCP server into a client (stdio):

```json
{
  "mcpServers": {
    "hyindex": {
      "command": "java",
      "args": ["-jar", "/path/to/hyve-knowledge-mcp.jar"]
    }
  }
}
```

## Build

Requires JDK 21+.

```bash
cd hyve-plugin
./gradlew :indexer-cli:shadowJar :mcp-server:shadowJar
```

Outputs:

- `indexer-cli/build/libs/hyve-knowledge-indexer.jar`
- `mcp-server/build/libs/hyve-knowledge-mcp.jar`

Optional in-process ONNX embeddings:

```bash
./gradlew :embeddings-local:shadowJar
```

## Requirements

- JDK 21+
- `git` on `PATH`
- An embedding backend (OpenAI, Voyage, Cohere, Gemini, Jina, Mistral, Mixedbread, Ollama, or any compatible endpoint for the chosen protocol)

## License

Apache License 2.0. See [LICENSE](LICENSE).
