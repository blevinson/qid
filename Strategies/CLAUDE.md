# Claude Code Assistant - Bookmap Trading Project

## Project Overview

This is a Bookmap trading strategy project implementing order flow analysis with AI-driven signal detection and automated order execution.

**Key Technologies:**
- Bookmap Layer1 API (v7.6.0.20)
- Java 17
- Gradle build system
- Claude AI API integration
- Order flow analysis

## Available Skills

This project has several specialized capabilities documented in `skills.md`:

1. **Order Flow Philosophy** - Understanding order flow trading concepts
2. **Bookmap Indicators** - Developing Bookmap API indicators
3. **Real Order Execution** - Production order management via Bookmap API
4. **AI Integration** - AI-driven signal analysis and chat interface

**Before making changes, always consult `skills.md` to understand:**
- Available capabilities
- API usage patterns
- Common pitfalls
- Integration examples

## Important Context

### Order Execution
- ✅ **Real implementation:** `BookmapOrderExecutor` uses actual Bookmap trading API
- ⚠️ **Legacy simulation:** `SimpleOrderExecutor` exists for testing only
- 🔄 **Active:** `OrderFlowStrategyEnhanced` currently uses `BookmapOrderExecutor`

### API Specifics
- Bookmap API uses **constructors** (not builders) for order parameters
- Market orders use `Double.NaN` for price (not 0)
- Field names: `filled`, `unfilled` (not `size`, `unfilledSize`)
- `ExecutionInfo` lacks `isBuy` field - must look up order

### Risk Management
- Always test in **paper trading mode first**
- Daily loss limits enforced via `AIOrderManager`
- Break-even automation available
- Maximum position limits configurable

## Code Structure

```
src/main/java/velox/api/layer1/simplified/demo/
├── OrderFlowStrategyEnhanced.java    # Main strategy
├── BookmapOrderExecutor.java         # Real order execution (NEW)
├── SimpleOrderExecutor.java          # Simulation (testing only)
├── OrderExecutor.java               # Interface
├── AIOrderManager.java              # AI order management
├── AIIntegrationLayer.java          # Claude API integration
├── AIThresholdService.java          # AI chat interface
├── AbsorptionDetector.java           # Absorption detection
├── LiquidityTracker.java            # Liquidity tracking
└── ConfluenceSignal.java             # Signal scoring

docs/
├── ORDER_FLOW_TRADING_PLAN.md        # Strategy documentation
└── ORDER_FLOW_STRATEGY_README.md     # Overview
```

## Development Guidelines

1. **Reference skills.md** before implementing order-related features
2. **Test thoroughly** in paper trading before live deployment
3. **Use BookmapOrderExecutor** for production, SimpleOrderExecutor for unit tests only
4. **Check API version** compatibility (currently 7.6.0.20)
5. **Handle errors gracefully** - orders can be rejected, partially filled, or cancelled

## Testing Commands

```bash
# Build project
./gradlew build

# Run tests
./gradlew test

# Create JAR for Bookmap
./gradlew jar
# Output: build/libs/bm-strategies.jar
```

## Recent Changes

### 2025-02-11: Real Order Execution Implementation
- ✅ Created `BookmapOrderExecutor.java` with Bookmap API integration
- ✅ Updated `OrderFlowStrategyEnhanced.java` to use real executor
- ✅ Implements `OrdersListener` for order status tracking
- ✅ Supports MARKET, LIMIT, STOP_MARKET, STOP_LIMIT orders
- ✅ Order modification and cancellation
- ✅ Position and balance tracking
- ✅ BUILD SUCCESSFUL

### Git Status
- Branch: `develop`
- Modified: `OrderFlowStrategyEnhanced.java`
- New: `BookmapOrderExecutor.java`
- **Not yet committed** - ready for commit

## Documentation References

- **Skills Overview:** `skills.md` (START HERE!)
- **Strategy Plan:** `/docs/ORDER_FLOW_TRADING_PLAN.md`
- **Bookmap API:** https://bookmap.com/knowledgebase/docs/API
- **ExchangePort Demo:** https://github.com/BookmapAPI/ExchangePortDemo

## Support

For questions about:
- **Order execution:** See skills.md section 3
- **Order flow concepts:** See skills.md section 1
- **Bookmap indicators:** See skills.md section 2
- **AI integration:** See skills.md section 4

---

**Remember:** This is a trading system with real financial implications. Always:
1. Test in paper trading first
2. Use proper risk management
3. Monitor logs for errors
4. Start with small position sizes
5. Have a clear exit strategy
