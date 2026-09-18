# Hyindex

Hyindex builds searchable indexes from official Hytale source, game data, documentation, and client UI files, then serves them over MCP.

It is a standalone continuation of the discontinued [Hyve IntelliJ plugin](https://plugins.jetbrains.com/plugin/30299-hyve/versions/stable).

## Quick start

Download `hyindex-knowledge-indexer.jar` and `hyindex-knowledge-mcp.jar` from the [latest release](https://github.com/Xytronix/Hyindex/releases/latest), then run:

```bash
java -jar hyindex-knowledge-indexer.jar init
java -jar hyindex-knowledge-indexer.jar --patchline release
java -jar hyindex-knowledge-mcp.jar
```

Non-interactive OpenAI setup:

```bash
java -jar hyindex-knowledge-indexer.jar init --non-interactive \
  --provider openai \
  --api-key sk-... \
  --code-model text-embedding-3-large \
  --text-model text-embedding-3-large \
  --git-token ghp_... \
  --force
```

Re-running the indexer only processes changed files. Use `--force` for a full rebuild. Hyindex checks out `HypixelStudios/hytale-shared-source` and rejects symlinks that escape the source tree.

## Configuration

Runtime configuration lives at:

```text
~/.hyindex/knowledge/mcp-config.json
```

The `init` command creates it. The complete template is [`mcp-config.example.json`](mcp-config.example.json).

Embeddings use named profiles mapped to corpora:

```json
{
  "embeddingProfiles": {
    "code": {
      "provider": "openai",
      "baseUrl": "https://api.openai.com",
      "apiKey": "",
      "documentModel": "text-embedding-3-large",
      "dimensions": 3072,
      "concurrency": 4
    }
  },
  "corpusEmbeddingProfiles": {
    "code": "code"
  }
}
```

Each profile owns its provider, endpoint, credentials, document/query models, dimensions, and concurrency. Every indexed corpus must have an assignment. Query dimensions and model families must match the stored index provenance.

### Embedding providers

| Provider | Default protocol | Typical models |
| --- | --- | --- |
| `openai` or custom | OpenAI `/v1/embeddings` | `text-embedding-3-large`, `text-embedding-3-small`, compatible custom IDs |
| `voyage` | Voyage embeddings/contextualized embeddings | `voyage-code-4`, `voyage-4-large`, `voyage-context-4` |
| `cohere` | Cohere `/v1/embed` | `embed-v4.0` |
| `gemini` | Native Google embedding API or `/openai` shim | `gemini-embedding-2` |
| `jina` | OpenAI-compatible with retrieval task fields | `jina-embeddings-v3` |
| `mistral` | OpenAI-compatible | `codestral-embed-2505`, `mistral-embed` |
| `mixedbread` | OpenAI-compatible | `mxbai-embed-large` |
| `ollama` | Ollama `/api/embed` | `qwen3-embedding:8b`, `nomic-embed-text-v2-moe` |
| `local` | Optional ONNX plugin | `all-minilm-l6-v2-q` |

OpenAI's current embedding models (`text-embedding-3-large` and `text-embedding-3-small`) are supported directly. The OpenAI-compatible adapter also accepts newer model IDs; set the profile's `dimensions` when the model is not in Hyindex's built-in dimension table.

Visual indexing is opt-in. Map `visual` to a Voyage or native Gemini multimodal profile, set `visualRasterRoot`, and include `visual` in `--corpus`.

### Reranking and routing

Reranking is enabled by adding one independent profile:

```json
{
  "rerankerProfile": {
    "provider": "cohere",
    "baseUrl": "https://api.cohere.com",
    "apiKey": "",
    "model": "rerank-v4.0-pro",
    "topN": 50
  }
}
```

Voyage, Cohere, and Jina protocols are built in. Any provider using one of those wire formats can be configured with a custom `provider`, `protocol`, `baseUrl`, and optional `endpoint`. Reranker credentials never inherit embedding credentials.

Jev corpus routing is optional. Set `jevRoutingEnabled` and provide `jevApiKey` or `TYPESAFE_API_KEY`. The default model is the moving stable alias `jev-latest`.

After changing profiles, rebuild the affected indexes and restart the MCP server.

## MCP client

```json
{
  "mcpServers": {
    "hyindex": {
      "command": "java",
      "args": ["-jar", "/path/to/hyindex-knowledge-mcp.jar"]
    }
  }
}
```

## Build

Requires JDK 21+ and `git` on `PATH`.

```bash
cd hyindex-plugin
./gradlew :indexer-cli:shadowJar :mcp-server:shadowJar
```

Artifacts are written under each module's `build/libs/`. The optional local embedding plugin is built with `./gradlew :embeddings-local:shadowJar`.

## License

Apache License 2.0. See [LICENSE](LICENSE).
