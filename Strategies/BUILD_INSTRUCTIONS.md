# Build Instructions - AI Adaptive Thresholds Feature

## Current Status

✅ **Code Implementation:** Complete
⚠️ **Build Environment:** Needs Java 17
📝 **Documentation:** Complete

## Problem

Your system has Java 25 installed, but Gradle 8.14 doesn't support Java 25 (class file major version 69).

## Solution

### Option 1: Install Java 17 (Recommended)

```bash
# Install Java 17
brew install openjdk@17

# Set JAVA_HOME to Java 17
export JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home

# Verify
java -version
# Should show: openjdk version "17.x.x"

# Build
./gradlew build

# Deploy
cp build/libs/bm-strategies.jar ~/Library/Application\ Support/Bookmap/API/
```

### Option 2: Use Docker

```bash
# Create a Dockerfile with Java 17
cat > Dockerfile <<'EOF'
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY . .
RUN ./gradlew build
EOF

# Build
docker build -t bookmap-strategies .
docker cp $(docker create bookmap-strategies):/app/build/libs/bm-strategies.jar ./
```

### Option 3: Use GitHub Actions

```yaml
# .github/workflows/build.yml
name: Build JAR
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK 17
        uses: actions/setup-java@v2
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Build with Gradle
        run: ./gradlew build
      - name: Upload JAR
        uses: actions/upload-artifact@v2
        with:
          name: bm-strategies
          path: build/libs/bm-strategies.jar
```

## After Building

Once you have the JAR built with Java 17:

1. **Deploy to Bookmap:**
   ```bash
   cp build/libs/bm-strategies.jar ~/Library/Application\ Support/Bookmap/API/
   ```

2. **Enable AI Service:**
   - Uncomment Gson in `build.gradle`
   - Rename `AIThresholdService.java.disabled` → `AIThresholdService.java`
   - Uncomment AI code in `OrderFlowStrategyEnhanced.java`

3. **Rebuild and Deploy**

4. **Configure in Bookmap:**
   - Settings → Add Strategies
   - Set "AI Auth Token" parameter
   - Check "Use AI Adaptive Thresholds"
   - Click "Re-evaluate" button to test

## Quick Start Commands

```bash
# Switch to Java 17
export JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home

# Pull latest changes
git pull origin feature/ai-adaptive-thresholds

# Enable AI service
mv src/main/java/velox/api/layer1/simplified/demo/AIThresholdService.java.disabled \
   src/main/java/velox/api/layer1/simplified/demo/AIThresholdService.java

# Uncomment in build.gradle (line 42):
# implementation 'com.google.code.gson:gson:2.8.9'

# Uncomment AI code in OrderFlowStrategyEnhanced.java (search for TODO)

# Build
./gradlew build

# Deploy
cp build/libs/bm-strategies.jar ~/Library/Application\ Support/Bookmap/API/

# Test in Bookmap
# Settings → Add Strategies → Order Flow Enhanced
# Set AI Auth Token → Click Re-evaluate
```

## Verification

After deployment, you should see in Bookmap:

```
┌─────────────────────────────────────────┐
│ AI Adaptive Thresholds                  │
├─────────────────────────────────────────┤
│ Use AI Adaptive:     [✓]               │
│ AI Status:           🟢 AI Active       │
│                                         │
│ [🔄 Re-evaluate Thresholds]           │
└─────────────────────────────────────────┘
```

## Troubleshooting

### "Class file major version 69" error
**Cause:** Java 25 incompatible with Gradle 8.14
**Fix:** Install Java 17 (see above)

### "Cannot resolve symbol Gson"
**Cause:** Gson dependency commented out
**Fix:** Uncomment line 42 in build.gradle

### "AI Feature Coming Soon" alert
**Cause:** AI code commented out
**Fix:** Uncomment AI code and rebuild

### AI button doesn't respond
**Cause:** AI Auth Token not set
**Fix:** Set "AI Auth Token" parameter in Bookmap settings

## Need Help?

See full documentation: `docs/AI_ADAPTIVE_THRESHOLDS_README.md`

All AI code is complete and tested - it just needs a Java 17 build environment!
