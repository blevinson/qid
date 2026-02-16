# Groq Integration Specification

## Overview

Integrate Groq as an alternative AI backend for AI Strategist Mode.

## Configuration

### Environment Variables

From \`Strategies/.env\`:

\`\`\`bash
# Groq (new)
GROQ_TOKEN=PLACEHOLDER_FOR_GROQ_API_KEY
GROQ_MODEL=qwen/qwen3-32b
\`\`\`

## Benefits

| Factor | Claude | Groq |
|---------|--------|-------|
| **Latency** | ~200-500ms | ~10-50ms |
| **Cost** | \$3.00 per 1M tokens | \$0.59 per 1M tokens |

## Implementation Phases

1. Infrastructure (Groq client, provider manager)
2. Integration (dual provider support)
3. Testing (unit, integration, performance)
4. Production (gradual rollout with monitoring)

Full spec available in \`Strategies/docs/SPEC_AI_STRATEGIST_MODE.md\`.

**Version:** 1.0
**Updated:** 2025-02-15
