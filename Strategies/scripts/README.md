# Scripts Directory

This directory contains utility scripts for the Qid trading system.

## What to Put Here

### Build Scripts
- Automated build scripts
- Deployment scripts
- Testing scripts

### Data Processing
- Session transcript analysis
- Memory file generation
- Performance analytics

### Utilities
- Memory index maintenance
- Database migrations
- Log file processing

## Example Scripts

### build.sh
```bash
#!/bin/bash
# Build the Qid trading system
cd Strategies
./gradlew clean build
```

### analyze-sessions.py
```python
#!/usr/bin/env python3
# Analyze session transcripts for patterns
import json

# Analyze JSONL session files
# Generate performance reports
# Identify winning/losing patterns
```

### update-memory.sh
```bash
#!/bin/bash
# Sync memory index after changes
cd Strategies
./gradlew run -PmainClass=MemoryIndexer
```

## Making Scripts Executable

```bash
chmod +x scripts/*.sh
```

**Note:** These are optional. The skill works without scripts, but they can automate common tasks.
```