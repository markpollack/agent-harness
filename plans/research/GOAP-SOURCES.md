# GOAP Research Sources

**Purpose**: Collect academic papers, reference implementations, and foundational literature for clean-room GOAP implementation.

**Date**: 2026-01-13

---

## Part 1: Academic Papers (Priority Download List)

### Primary GOAP Papers

| Paper | Author | Year | Source | Format |
|-------|--------|------|--------|--------|
| **Applying Goal-Oriented Action Planning to Games** | Jeff Orkin | 2003 | [CiteSeerX PDF](https://citeseerx.ist.psu.edu/document?repid=rep1&type=pdf&doi=0c35d00a015c93bac68475e8e1283b02701ff46b) | PDF |
| **Three States and a Plan: The A.I. of F.E.A.R.** | Jeff Orkin | 2006 | GDC Vault | PDF |
| **Goal Oriented Action Planning Report** | Univ. of Groningen | 2010s | [AI.rug.nl PDF](https://www.ai.rug.nl/gwenniger/Finished_Projects/GOAP-Report.pdf) | PDF |
| **Tactical Planning in Space Game using GOAP** | ResearchGate | 2019 | [ResearchGate](https://www.researchgate.net/publication/334320575_Tactical_Planning_in_Space_Game_using_Goal-Oriented_Action_Planning) | PDF |
| **Multi-Agent Performance Using GOAP** | DiVA Portal | 2024 | [DiVA PDF](http://www.diva-portal.org/smash/get/diva2:1972169/FULLTEXT01.pdf) | PDF |

### Foundational Planning Papers (STRIPS, A*, HTN)

| Paper | Author | Year | Source | Format |
|-------|--------|------|--------|--------|
| **STRIPS: A New Approach to the Application of Theorem Proving to Problem Solving** | Fikes & Nilsson | 1971 | [Stanford AI Lab](http://ai.stanford.edu/~nilsson/OnlinePubs-Nils/PublishedPapers/strips.pdf) | PDF |
| **A Formal Basis for the Heuristic Determination of Minimum Cost Paths** | Hart, Nilsson, Raphael | 1968 | IEEE | PDF (A* original) |
| **Hierarchical Task Network Planning** | Erol, Hendler, Nau | 1994 | AAAI | PDF |

### Agent Loop / LLM Agent Papers (Cross-Reference)

| Paper | Author | Year | Source |
|-------|--------|------|--------|
| **ReAct: Synergizing Reasoning and Acting in Language Models** | Yao et al. | 2022 | [arXiv:2210.03629](https://arxiv.org/abs/2210.03629) |
| **Reflexion: Language Agents with Verbal Reinforcement Learning** | Shinn et al. | 2023 | [arXiv:2303.11366](https://arxiv.org/abs/2303.11366) |
| **Tree of Thoughts: Deliberate Problem Solving with LLMs** | Yao et al. | 2023 | [arXiv:2305.10601](https://arxiv.org/abs/2305.10601) |
| **Agentic Large Language Models: A Survey** | Plaat et al. | 2025 | [arXiv:2503.23037](https://arxiv.org/abs/2503.23037) |
| **The Landscape of Emerging AI Agent Architectures** | Masterman et al. | 2024 | [arXiv:2404.11584](https://arxiv.org/abs/2404.11584) |

---

## Part 2: Java Implementations (Clean-Room Reference)

### Full GOAP Implementations (NOT Embabel)

| Repository | License | Stars | Last Active | Notes |
|------------|---------|-------|-------------|-------|
| **[JavaGOAP](https://github.com/ph1387/JavaGOAP)** | MIT | 27 | Dec 2020 | Most complete, well-documented, based on Orkin |
| **[jgoap](https://github.com/fdefelici/jgoap)** | MIT | 7 | Oct 2021 | Clean, minimal, Maven-based |
| **[JavaGOAPDemo](https://github.com/MrSanchez/JavaGOAPDemo)** | MIT | 5 | Mar 2020 | JavaFX visual demo, backward A* |

### A* / Pathfinding Libraries

| Repository | License | Stars | Last Active | Notes |
|------------|---------|-------|-------------|-------|
| **[gdx-ai](https://github.com/libgdx/gdx-ai)** | Apache 2.0 | 1,278 | Oct 2024 | Production quality, hierarchical A*, behavior trees |
| **[xaguzman/pathfinding](https://github.com/xaguzman/pathfinding)** | Apache 2.0 | 100 | Jan 2022 | A*, JPS, Theta* variants |
| **[ytcoode/pathfinding](https://github.com/ytcoode/pathfinding)** | MIT | 2 | Dec 2019 | Zero-GC, performance-focused |

### Classical Planning Libraries

| Repository | License | Stars | Last Active | Notes |
|------------|---------|-------|-------------|-------|
| **[PDDL4J](https://github.com/pellierd/pddl4j)** | LGPL-3.0 | 161 | Sep 2024 | Full PDDL 3.1, academic quality, FastForward |
| **[JBT](https://github.com/gaia-ucm/jbt)** | Apache 2.0 | 261 | Jul 2014 | Behavior Trees, Eclipse editor |

### Other Language References (Algorithm Study)

| Repository | Language | License | Notes |
|------------|----------|---------|-------|
| **[GPGOAP](https://github.com/stolk/GPGOAP)** | C | Public Domain | General Purpose GOAP, minimal |
| **[cppGOAP](https://github.com/cpowell/cppGOAP)** | C++ | MIT | Clean C++ reference |
| **[ReGoap](https://github.com/luxkun/ReGoap)** | C# | MIT | Unity GOAP, generic planner |

---

## Part 3: Key Web Resources

### Jeff Orkin's Archive
- **MIT Media Lab Archive**: http://alumni.media.mit.edu/~jorkin/goap.html
- **GDC 2006 Presentation**: Available via GDC Vault

### Tutorials and Explanations
- [Goal Oriented Action Planning - Medium (Vedant Chaudhari)](https://medium.com/@vedantchaudhari/goal-oriented-action-planning-34035ed40d0b)
- [NPC AI Planning with GOAP - Excalibur.js](https://excaliburjs.com/blog/goal-oriented-action-planning/)
- [Building the AI of F.E.A.R. - Game Developer](https://www.gamedeveloper.com/design/building-the-ai-of-f-e-a-r-with-goal-oriented-action-planning)

---

## Part 4: Recommended Study Order

### Phase 1: Understand GOAP Theory
1. Read Orkin (2003) "Applying Goal-Oriented Action Planning to Games"
2. Read University of Groningen GOAP Report
3. Study STRIPS paper for historical context

### Phase 2: Study Java Implementations
1. **JavaGOAP** - Full implementation study
   - `GoapAgent.java` - Agent abstraction
   - `GoapPlanner.java` - A* planning
   - `GoapState.java` - World state representation
   - `GoapAction.java` - Action with preconditions/effects
2. **gdx-ai** - A* implementation quality
   - `IndexedAStarPathFinder.java`
   - `GraphPath.java`
   - `Heuristic.java`

### Phase 3: Implement Clean-Room
1. Define `PlanAction<S>` interface
2. Define `Goal<S>` as Judge specialization
3. Implement `AStarPlanner<S>` from algorithm description
4. Create `GoapLoop<S>` showing Judge subsumption

---

## Part 5: Download Commands

```bash
# Create papers directory
mkdir -p ~/research/papers/goap

# Download available PDFs
cd ~/research/papers/goap

# Orkin (2003) - CiteSeerX
curl -L -o orkin-2003-goap.pdf "https://citeseerx.ist.psu.edu/document?repid=rep1&type=pdf&doi=0c35d00a015c93bac68475e8e1283b02701ff46b"

# University of Groningen Report
curl -L -o groningen-goap-report.pdf "https://www.ai.rug.nl/gwenniger/Finished_Projects/GOAP-Report.pdf"

# DiVA Multi-Agent GOAP
curl -L -o diva-multiagent-goap-2024.pdf "http://www.diva-portal.org/smash/get/diva2:1972169/FULLTEXT01.pdf"

# STRIPS original
curl -L -o fikes-nilsson-1971-strips.pdf "http://ai.stanford.edu/~nilsson/OnlinePubs-Nils/PublishedPapers/strips.pdf"

# arXiv papers (LaTeX source available)
# ReAct
curl -L -o react-2022.pdf "https://arxiv.org/pdf/2210.03629.pdf"
# To get LaTeX source: https://arxiv.org/e-print/2210.03629

# Reflexion
curl -L -o reflexion-2023.pdf "https://arxiv.org/pdf/2303.11366.pdf"

# Tree of Thoughts
curl -L -o tree-of-thoughts-2023.pdf "https://arxiv.org/pdf/2305.10601.pdf"

# Agent Survey
curl -L -o plaat-agent-survey-2025.pdf "https://arxiv.org/pdf/2503.23037.pdf"

# Masterman Landscape
curl -L -o masterman-landscape-2024.pdf "https://arxiv.org/pdf/2404.11584.pdf"
```

### arXiv LaTeX Source Download

```bash
# arXiv provides LaTeX source via e-print endpoint
mkdir -p ~/research/papers/latex

# ReAct LaTeX source
curl -L -o ~/research/papers/latex/react-2022.tar.gz "https://arxiv.org/e-print/2210.03629"
tar -xzf ~/research/papers/latex/react-2022.tar.gz -C ~/research/papers/latex/react-2022/

# Reflexion LaTeX source
curl -L -o ~/research/papers/latex/reflexion-2023.tar.gz "https://arxiv.org/e-print/2303.11366"

# Tree of Thoughts LaTeX source
curl -L -o ~/research/papers/latex/tot-2023.tar.gz "https://arxiv.org/e-print/2305.10601"

# Plaat Survey LaTeX source
curl -L -o ~/research/papers/latex/plaat-2025.tar.gz "https://arxiv.org/e-print/2503.23037"
```

---

## Part 6: License Compatibility Summary

For clean-room implementation, prefer these licenses:

| License | Safe for Study? | Safe for Implementation? |
|---------|-----------------|-------------------------|
| **MIT** | Yes | Yes |
| **Apache 2.0** | Yes | Yes |
| **Public Domain** | Yes | Yes |
| **LGPL-3.0** | Yes (read) | Caution (linking rules) |
| **GPL-3.0** | Yes (read) | No (copyleft) |

**Recommended primary sources for implementation**:
- JavaGOAP (MIT) - for GOAP patterns
- gdx-ai (Apache 2.0) - for A* quality
- Academic papers - for algorithm correctness

---

*Last Updated: 2026-01-13*
