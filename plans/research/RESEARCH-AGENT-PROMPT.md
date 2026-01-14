# Research Agent Prompt: GOAP & Planning Algorithm Literature

**Purpose**: Prompt for research agent to gather comprehensive academic and implementation references for GOAP, A*, and planning algorithms.

---

## Prompt

```
I am researching Goal-Oriented Action Planning (GOAP) and related planning algorithms for a Java implementation project. I need to build a comprehensive reference library with:

1. Academic papers (preferably with LaTeX source from arXiv)
2. Open source implementations (Java preferred, but also C++, C#, Python for algorithm reference)
3. Textbook chapters on planning algorithms

## Specific Research Tasks

### Task 1: Find All Jeff Orkin GOAP Papers

Jeff Orkin created GOAP at Monolith Productions for F.E.A.R. (2005) and later did PhD work at MIT Media Lab. Find:
- "Applying Goal-Oriented Action Planning to Games" (2003)
- "Three States and a Plan: The A.I. of F.E.A.R." (GDC 2006)
- Any MIT Media Lab publications by Orkin on GOAP or agent AI
- Any patents related to GOAP

Search: site:mit.edu orkin GOAP, site:gamasutra.com orkin GOAP, GDC Vault orkin

### Task 2: Find A* Algorithm Variants Papers

A* is the core search algorithm in GOAP. Find papers on:
- Original A* paper: Hart, Nilsson, Raphael (1968) "A Formal Basis for the Heuristic Determination of Minimum Cost Paths"
- Weighted A* (WA*)
- Anytime Repairing A* (ARA*)
- Lifelong Planning A* (LPA*)
- D* and D* Lite (for replanning)
- Hierarchical A* (HPA*)

Search: arxiv.org A* pathfinding variants, semantic scholar A* algorithm survey

### Task 3: Find STRIPS and Classical Planning Papers

GOAP is based on STRIPS planning. Find:
- Fikes & Nilsson (1971) "STRIPS: A New Approach to Theorem Proving"
- PDDL (Planning Domain Definition Language) specification papers
- FastForward planner papers
- GraphPlan papers
- Any surveys of classical planning (2020-2025)

Search: arxiv.org STRIPS planning survey, PDDL specification

### Task 4: Find Hierarchical Task Network (HTN) Papers

HTN is an alternative to GOAP that's sometimes compared. Find:
- Original HTN papers by Erol, Hendler, Nau
- SHOP/SHOP2 planner papers
- Comparison papers: HTN vs GOAP

### Task 5: Find Game AI Planning Survey Papers

Find recent surveys (2020-2025) covering:
- Game AI planning techniques comparison
- GOAP in modern games
- Behavior trees vs GOAP vs utility AI
- Any benchmarks of planning approaches in games

Search: arxiv.org game AI planning survey 2024, IEEE game AI survey

### Task 6: Find LLM Agent Planning Papers

For our taxonomy cross-reference, find papers on:
- LLM agents with explicit planning phases
- ReAct, Reflexion, Tree of Thoughts (already have these)
- Plan-and-Execute patterns in LLM agents
- Any papers comparing classical planning to LLM planning

Search: arxiv.org LLM agent planning 2024 2025

### Task 7: Find Java Planning Implementations

Search GitHub for Java implementations (exclude Embabel):
- GOAP implementations: "GOAP java" language:Java
- A* implementations: "A* pathfinding" language:Java
- PDDL planners: "PDDL" language:Java
- HTN planners: "HTN planner" language:Java

For each, note: repository URL, license, stars, last commit, key classes

### Task 8: Find Textbook Chapters

Find relevant textbook content:
- Russell & Norvig "AI: A Modern Approach" - Chapters 10-11 (Planning)
- Millington & Funge "Artificial Intelligence for Games" - GOAP chapter
- Any other AI textbooks with planning coverage

### Task 9: Find Benchmark Datasets

Find any benchmarks for evaluating planning algorithms:
- International Planning Competition (IPC) domains
- Game AI benchmarks
- SWE-bench (for LLM agent comparison)

## Output Format

For each paper found, provide:
```
Title:
Authors:
Year:
Venue: (journal/conference)
URL: (preferably PDF or arXiv)
LaTeX available: Yes/No
Relevance: (High/Medium/Low)
Key contribution:
```

For each implementation found, provide:
```
Repository:
License:
Language:
Stars:
Last commit:
Key classes:
Algorithm: (A*, GOAP, HTN, etc.)
Quality: (Production/Academic/Demo)
```

## Priority

1. Papers with LaTeX source (arXiv preferred)
2. MIT-licensed Java implementations
3. Apache 2.0 licensed Java implementations
4. Foundational papers (even if no LaTeX)
5. Other language implementations for algorithm study

## Exclusions

- DO NOT include Embabel or any code from embabel.io
- Avoid GPL-licensed code for implementation reference
- Avoid papers behind paywalls unless critical
```

---

## Alternative Search Queries

### For Semantic Scholar
```
GOAP goal-oriented action planning games
A* pathfinding survey
STRIPS planning language models
classical planning artificial intelligence
hierarchical task network games
behavior trees vs GOAP
LLM agents planning reasoning
```

### For arXiv
```
cat:cs.AI goal planning agents
cat:cs.AI A* search heuristic
cat:cs.AI STRIPS PDDL
cat:cs.AI LLM reasoning planning
cat:cs.AI game AI behavior
```

### For GitHub Code Search
```
language:java "class GoapPlanner"
language:java "class AStarPathFinder"
language:java "interface Action" "preconditions"
language:java "PlanningDomain"
language:java "HierarchicalPlanner"
```

### For Google Scholar
```
"goal-oriented action planning" survey
"A* algorithm" variants comparison
"STRIPS" "PDDL" planning survey 2024
"game AI" planning techniques comparison
"LLM" "planning" "agents" 2024
```

---

## Expected Deliverables

After running this research:

1. **Bibliography file** (`bibliography.bib`) with all papers in BibTeX format
2. **Implementation inventory** (markdown table of all repos)
3. **Downloaded papers** in `~/research/papers/goap/`
4. **LaTeX sources** for arXiv papers in `~/research/papers/latex/`
5. **Summary document** ranking papers by relevance to our project

---

*Prompt Version: 1.0 | Date: 2026-01-13*
