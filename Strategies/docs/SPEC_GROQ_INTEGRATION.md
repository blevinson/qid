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

## UI Settings

### Provider Selection

Add dropdown control to Bookmap settings panel for selecting AI provider:

```java
// OrderFlowStrategyEnhanced.java
public class AISettings {

    // Provider preference
    public AIProviderPreference providerPreference = AIProviderPreference.AUTO;

    public enum AIProviderPreference {
        CLAUDE_ONLY,      // Only use Claude
        GROQ_ONLY,       // Only use Groq
        AUTO,             // Automatically select based on signal type
        PREFER_CLAUDE,   // Prefer Claude, fallback to Groq
        PREFER_GROQ      // Prefer Groq, fallback to Claude
    }

    // Fallback settings
    public boolean enableFallback = true;
    public long fallbackTimeoutMs = 30000;
    public int maxRetries = 2;

    // Display settings
    public boolean showLatencyMetrics = true;
    public boolean showCostMetrics = true;
    public boolean showDecisionQuality = true;
}
```

**Provider Selection UI:**

```
┌─────────────────────────────────────┐
│ AI Provider:                     │
│ ┌────────────────────────────┐    │
│ │ (o) Claude Only          │    │
│ │ (o) Groq Only           │    │
│ │ (o) Auto (Smart Select)  │    │
│ └────────────────────────────┘    │
│                                    │
│ ☐ Enable Fallback               │
│ ☐ Show Latency Metrics            │
│ ☐ Show Cost Metrics               │
│                                    │
│ Current: Claude                   │
│ Avg Latency: 250ms              │
│ Cost: $3.00/1M tokens           │
└─────────────────────────────────────┘
```

**Selection Logic:**

| User Selection | Behavior |
|---------------|----------|
| **Claude Only** | Always use Claude |
| **Groq Only** | Always use Groq |
| **Auto** | Select based on signal type |
| **Prefer Claude** | Claude first, fallback to Groq |
| **Prefer Groq** | Groq first, fallback to Claude |

### Benefits

1. User control over provider selection
2. Runtime flexibility - switch without restart
3. Transparency - see active provider & metrics
4. Easy A/B testing of different configurations

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

**Version:** 1.1
**Updated:** 2025-02-15
