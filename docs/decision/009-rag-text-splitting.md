# ADR-009: Text Splitting Strategy for RAG Ingestion (Phase 4)

**Status:** Accepted

## Context

`docs/architecture.md` Phase 4's "Candidate Technologies" table lists **LangChain** for the
"Text Splitting" step of the RAG pipeline (`documents chunked → embeddings generated → stored in
vector DB → retrieved during chat`). That table was written during early planning, before any
Phase 4 code existed, and the other two undecided rows in it (Embedding Model: "OpenAI / Gemini",
Vector DB: "pgvector / Pinecone") have since been narrowed by what actually got built —
`GeminiEmbeddingProvider` and `PgVectorSearchAdapter` (pgvector) respectively. Text Splitting is
the one row that hadn't been resolved yet.

LangChain itself is a Python (and JS) framework with no first-class Kotlin/JVM port; this project
is pure Kotlin/Spring Boot with no LangChain dependency anywhere in `build.gradle.kts`. Taking the
table entry literally isn't an option — the real choice is between pulling in a JVM library that
plays the same role (`langchain4j`'s `DocumentSplitters`) or implementing the algorithm directly.

As of this ADR, only the retrieval half of RAG exists (`RagService`, `EmbeddingService`,
`VectorSearchPort`/`PgVectorSearchAdapter`) — there is no ingestion pipeline yet (no
upload endpoint, no service that chunks a document and calls `VectorSearchPort.upsertChunk`).
This ADR settles the splitting approach ahead of building that pipeline, so ingestion isn't
designed around a chunking strategy chosen ad hoc later.

## Decision

**Implement chunking directly in Kotlin** — `org.timpeng.chatbot.rag.splitting.TextSplitter`
(interface) + `RecursiveCharacterTextSplitter` (the only implementation) — rather than adding a
dependency for it.

The algorithm mirrors LangChain's own `RecursiveCharacterTextSplitter`: try a sequence of
separators from largest semantic unit to smallest (`\n\n` → `\n` → `。` → `. ` → space), split on
the first one present in the text, and recurse into any resulting piece still longer than
`chunkSize` using the remaining, finer-grained separators. A piece with no matching separator left
(e.g. one long unbroken token) is hard-cut at `chunkSize` as a last resort, which guarantees
termination. The resulting pieces are then greedily packed back into chunks close to `chunkSize`,
carrying `chunkOverlap` characters from the end of one chunk into the start of the next so
retrieval doesn't lose context when the relevant text straddles a chunk boundary.

Both parameters are configurable and character-based, not token-based: `app.rag.splitter.chunk-size`
(default 800, env `RAG_SPLITTER_CHUNK_SIZE`) and `app.rag.splitter.chunk-overlap` (default 100,
env `RAG_SPLITTER_CHUNK_OVERLAP`).

## Options considered

### Option A — `langchain4j`'s `DocumentSplitters.recursive(...)`

| | |
|---|---|
| ✅ | Battle-tested, handles more edge cases (token-aware splitting, more document formats) than a hand-rolled version |
| ✅ | Closest thing to "LangChain" that actually exists on the JVM, if the architecture doc's original intent was taken literally |
| ❌ | A new third-party dependency (and its own dependency tree) for an algorithm that isn't actually complex |
| ❌ | Its embedding/LLM/vector-store abstractions overlap with this project's own `LlmProvider`/`EmbeddingProvider`/`VectorSearchPort` ports — adopting it piecemeal (splitter only) still pulls in the full library |

### Option B — Custom `RecursiveCharacterTextSplitter` in Kotlin (chosen)

| | |
|---|---|
| ✅ | No new dependency — recursive-separator splitting is a small, well-understood algorithm, not something that justifies a framework |
| ✅ | Matches this project's existing idiom of defining a small interface (`TextSplitter`, alongside `LlmProvider`/`EmbeddingProvider`) and owning the implementation, so a future swap (e.g. Markdown-header-aware splitting, semantic splitting) is a new class, not a migration off a library |
| ✅ | Full control over chunk sizing relative to the actual embedding model in use (`text-embedding-004`'s context window), rather than inheriting another library's defaults/assumptions |
| ❌ | Misses more sophisticated behavior a mature library has (token-aware sizing, format-specific splitters for PDF/HTML/code) — acceptable for now since ingestion only needs to handle plain text at this stage |

### Option C — Naive fixed-size character chunker (no separator awareness)

| | |
|---|---|
| ✅ | Simplest possible implementation |
| ❌ | Cuts mid-word/mid-sentence indiscriminately, which measurably hurts embedding quality and retrieval relevance — rejected without much debate |

## Rationale

The splitting algorithm itself isn't the hard part of RAG ingestion — get separators right, recurse,
merge with overlap — so pulling in a dependency (and its transitive footprint) to do it doesn't pay
for itself, especially when this project already has a working pattern (small interface + owned
implementation) for exactly this kind of swappable component. `langchain4j` remains the fallback if
ingestion later needs document-format-specific splitting (PDF layout, HTML structure) that isn't
worth reimplementing by hand.

## Consequences

- `TextSplitter`/`RecursiveCharacterTextSplitter` (`src/main/kotlin/org/timpeng/chatbot/rag/splitting/`)
  exist now, covered by `RecursiveCharacterTextSplitterTest`, but nothing calls them yet — the
  ingestion pipeline (upload endpoint → split → embed → `VectorSearchPort.upsertChunk`) is still
  unbuilt. Wiring this in is the next piece of Phase 4, not part of this ADR.
- `chunk-size: 800` / `chunk-overlap: 100` (characters, not tokens) are initial estimates, not
  measured — revisit once ingestion exists and can be tried against real documents and
  `text-embedding-004`'s actual token limits.
- If retrieval quality later shows chunk boundaries splitting relevant content poorly (e.g. code
  blocks, tables), the fix is a new `TextSplitter` implementation selected the same way
  `LlmProvider`/`EmbeddingProvider` are (`@ConditionalOnProperty` on an `app.rag.splitter.strategy`-
  style key) — not a rewrite of `RecursiveCharacterTextSplitter` into something it isn't.

## Future considerations

| Item | Description |
|---|---|
| Ingestion pipeline | Upload endpoint/service that calls `TextSplitter.split` → `EmbeddingService.embed` → `VectorSearchPort.upsertChunk` per chunk. Not built yet. |
| Token-based sizing | Character count is a rough proxy for the embedding model's actual token budget; switching to a tokenizer-based length function would make `chunk-size` a more accurate knob once ingestion volume justifies the precision. |
| `langchain4j` for format-specific splitting | If ingestion needs to handle PDF/HTML/code documents well, revisit Option A for those formats specifically rather than extending the plain-text recursive splitter to parse structure it wasn't designed for. |
