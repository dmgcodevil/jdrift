# JDrift: Continuous JVM Performance Intelligence

## Main idea

JDrift turns Java Flight Recorder (JFR) recordings into a persistent history of JVM behavior. It automatically ingests recordings, extracts measurable facts, identifies performance problems, remembers recurring findings, and helps teams verify whether their fixes worked.

The core product is historical performance analysis. Single-recording analysis supplies observations, with JMC providing much of that foundation. JDrift must answer three primary questions across a selected history: **Is a problem fixed? Is there a regression? Is there a new problem we have not seen before?** Each answer includes its baseline, supporting evidence, observation coverage, and limitations. A standalone recording viewer or analyzer does not satisfy the product goal.

## Release priorities

- **V1 — historical intelligence:** import through UI/REST, persistent finding identity, time-range and last-N analysis, pairwise comparison, and evidence-backed assessments of fixes, regressions, and new problems. Return insufficient evidence when the selected history cannot support a conclusion.
- **V2 — agent-assisted analysis:** expose history, findings, comparisons, assessments, and evidence through an agent-friendly API and a JDrift CLI. An external agent can investigate by querying the system instead of receiving raw JFR files. Provider SDK/CLI integration may additionally support OpenAI/ChatGPT/Codex and Claude.
- **V3 — finding triage:** attach Jira issues, PRs, and investigation notes to persistent findings.

Source automation such as folder monitoring, scheduling, and S3 remains deferred with no release assignment yet.

Raw JFR recordings live in user-managed storage. In v1, users import recordings through the web UI or REST API. JDrift processes uploaded files and persists analysis results and extracted evidence, without retaining raw JFR dumps after processing. Scheduled ingestion, folder monitoring, and S3 support are deferred.

The central workflow is:

**Record → Analyze → Detect → Track → Fix → Verify**

Instead of repeatedly copying production recordings to a developer's machine and generating isolated Markdown reports, teams get a shared system for investigating performance over time. Every conclusion is connected to measurements and inspectable evidence.

## Problem

The current workflow is manual: collect a JFR dump from production, copy it locally, ask AI to analyze it, save a Markdown report, fix an issue, and repeat.

This creates several problems:

- Recordings get lost or become disconnected from the investigations they supported.
- Reports live on individual machines and are difficult to discover or compare.
- Known problems are investigated repeatedly because findings have no persistent identity.
- Verifying a fix requires manually locating and comparing recordings.
- Differences in workload, recording settings, or observation duration can be mistaken for improvements or regressions.

## Intended users and outcomes

JDrift serves JVM developers and performance engineers who need to understand application behavior and validate changes.

It also serves engineering leads and business owners who need evidence that a performance-improvement program is making progress. A team may resolve five issues without an obvious customer-visible improvement; cumulative benefits may emerge only after many changes. JDrift should show whether resource efficiency and runtime behavior are improving across builds and periods, independently of the number of findings marked resolved.

The historical report should show CPU consumption, estimated allocation, GC pause and pressure indicators, supported G1 humongous-allocation indicators, and JIT deoptimization activity where captured. Compare against an explicit baseline and show both absolute changes and workload-normalized changes when valid denominators exist. Preserve intermediate observations so users can see gradual progress, variability, plateaus, and regressions rather than only favorable endpoints.

Distinguish three outcomes: findings resolved, measured JVM efficiency improved, and customer experience improved. None automatically proves the next. Without latency, throughput, or other customer-facing measurements, report resource-efficiency progress and leave customer impact unverified. Lower resource usage under lower load is not sufficient evidence of better efficiency, and fewer deoptimizations alone do not establish a faster application.

It should answer:

- What performance problems are visible in this recording?
- Which findings are new, already known, recurring, or worsening?
- How has behavior changed over a selected period?
- What evidence supports a finding?
- Did a particular fix improve the problem under comparable conditions?
- Is there enough evidence to reach a conclusion?

## Core concepts

| Concept | Meaning |
| --- | --- |
| Recording | A JFR file loaded from external source storage for analysis. JDrift retains its identity, source reference, provenance, capture configuration, and application context, rather than the raw file. |
| Fact | A measurement extracted from a recording, such as sampled CPU activity, allocation estimates, GC pauses, or contention duration. |
| Evidence | Persisted extracted events or a derived excerpt supporting a fact or finding, traceable to the original recording and inspectable without loading the raw file again. |
| Observation | An occurrence of a finding in a particular recording or time interval. |
| Finding | A persistent performance concern that can have observations across many recordings. |
| Report | A versioned analysis of a recording, comparison, or historical window. |
| Fix verification | An assessment of observations before and after a change, including comparability and confidence. |

A finding persists across reports. Its absence from one recording does not, by itself, establish that it has been fixed.

## Main features

### 1. Recording import

- Import a JFR recording through the web UI or REST API in v1.
- Accept the complete upload before queuing analysis, then process it asynchronously.
- Detect duplicates and safely retry failed imports.
- Track ingestion and analysis status, including actionable failure details.
- Associate recordings with available application, environment, instance, build, JDK, and capture metadata.
- Release temporary raw-file copies after processing. Reanalysis or retry after cleanup requires uploading the recording again.

Source storage owns raw-file retention. Processing does not delete or modify the source recording. Automating production capture and transfer can be added separately.

### 2. Durable analysis results and evidence

- Store extracted facts, reports, findings, and evidence centrally with searchable recording metadata and source references.
- Preserve the relationship between recording identities, facts, observations, and findings without retaining raw JFR dumps.
- Store evidence for each finding so users can drill down into relevant stacks, threads, events, and time intervals.
- Support compact evidence excerpts where practical. A binary excerpt format is a design option to evaluate, rather than a prerequisite.
- Make retention of analysis results and evidence explicit. Previously generated reports and retained evidence remain usable if the original recording is no longer available.

Reanalysis requiring original events requires another upload in v1. Future source integrations may reload recordings directly. Retained evidence is not assumed to replace the complete recording for arbitrary future analysis.

### 3. Fact extraction and deterministic analysis

- Parse JFR events and extract facts about CPU activity, allocations, GC, thread states, contention, and JIT behavior when the recording supports them.
- Apply versioned analysis rules to generate reproducible findings.
- Produce a report containing a configurable top N findings, while retaining access to the complete set of detected findings.
- Include severity, affected code or resources, measurements, time intervals, supporting evidence, and analysis limitations.
- Record the analyzer version and configuration so results can be reproduced and changes in analysis distinguished from changes in application behavior.

For identical input, analyzer version, and configuration, the extracted facts and ranked findings should be consistent. Optional AI explanations must remain grounded in those results.

### 4. Time-aware hotspot detection

- Distinguish sustained activity from short bursts and isolated spikes.
- Analyze intervals within a recording instead of relying only on whole-recording averages.
- Distinguish active execution from blocking, waiting, and parked threads.
- Account for warmup and idle periods where identifiable.
- Describe both intensity and duration, and incorporate workload context when available.

A frequently observed stack is not automatically a performance problem. Findings should explain why the observed behavior matters and what the recording can actually establish.

### 5. Persistent finding identity and automatic labels

- Recognize the same underlying concern across recordings using stable identifying information.
- Label observations as new, known, or recurring, and identify improvement or deterioration relative to a stated baseline.
- Preserve first-seen and last-seen timestamps and an observation history.
- Track candidate resolution, verified resolution, and recurrence without treating missing evidence as proof of a fix.
- Keep user triage decisions distinct from automatically observed behavior.

Identity matching should tolerate ordinary code changes where possible and expose uncertainty when continuity cannot be established reliably.

### 6. Report comparison

- Compare the stored analysis results of two selected recordings or reports without requiring their raw JFR files.
- Summarize newly observed, continuing, improved, worsened, and no-longer-observed findings.
- Show absolute and relative changes in supported measurements, including CPU, allocation, memory, GC, and contention signals.
- Normalize by duration or workload where appropriate and supported.
- Highlight differences in recording configuration, application version, environment, and workload that limit comparison.

“No issues detected” means no findings were produced within the available coverage and analysis rules; it is not a guarantee that the application has no performance problems.

### 7. Historical analytics

- Analyze a selected time range or the last N reports for an application or comparable group of instances.
- Display trend graphs for measurements and finding observations.
- Generate a summary of new issues, persistent problems, improvements, regressions, and verified fixes.
- Allow drill-down from a trend to a report and its underlying evidence.
- Keep unrelated applications, environments, and incompatible captures from being silently combined.

### 8. Triage and engineering context

- Let users attach Jira issues and pull requests to persistent findings.
- Preserve investigation notes, ownership, and triage status.
- Connect a proposed fix to the relevant application revision or deployment when that information is available.
- Keep engineering context attached across repeated observations of the same problem.

### 9. Fix verification

- Select or identify observations before and after a fix.
- Assess whether recording coverage, workload, and environment are sufficiently comparable.
- Evaluate whether the targeted behavior improved, disappeared under adequate coverage, persisted, or regressed.
- Present the supporting measurements and the basis for the conclusion.
- Return “insufficient evidence” when a reliable conclusion is not possible.

Verification supports engineering judgment. A before/after improvement alone does not prove that a specific code change caused it.

### 10. Optional metrics and log correlation

Future integrations may correlate recordings with Micrometer metrics and log systems such as Humio. These signals can supply workload context, request rates, latency, deployment markers, and related errors.

The core workflow must remain useful with JFR recordings alone, while clearly identifying conclusions that require additional context.

## Role of AI

AI may explain findings, summarize historical changes, and suggest investigations. Measurements, evidence references, finding identity rules, and verification criteria must remain inspectable and reproducible.

AI-generated explanations must not invent missing measurements or turn uncertain observations into confirmed diagnoses.

## Initial scope and validation

V1 runs locally and supports JFR recordings from Java 17 and 21, with individual files smaller than 1 GB and an expected volume of approximately one recording per day. Both two-report comparison and historical analysis over a selected capture-time range or last N reports are required in v1.

Application context may be embedded in application-specific JFR metadata, including service, host, environment, and build version. Preserve discovered metadata and its provenance, allow explicit user context, and avoid assuming one universal schema. Configurable metadata schemas can follow later.

Testing and verification initially rely on our generated dataset; production recordings are not currently available. V2 should add AI provider/agent integration for OpenAI/ChatGPT/Codex and Claude using supported SDK or CLI interfaces. Provider output remains separate from deterministic facts and links back to retained evidence.

The first milestone should prove the core analysis loop using a small JVM test application and a reproducible dataset before committing to the full platform architecture.

The dataset should cover:

- CPU hotspots with sustained load, bursts, and idle intervals.
- High temporary allocation and a reduced-allocation variant.
- Growing versus stable memory retention.
- Lock contention and a corrected variant.
- JIT warmup, stable execution, and deliberately triggered deoptimization where observable.
- Healthy baselines at several load levels.

Where applicable, capture before-fix, after-fix, and regressed variants with repeated runs. Include deliberately incomparable recordings to validate uncertainty handling.

Each recording should have a manifest containing the application revision, JDK version, JVM flags, JFR configuration, scenario parameters, load profile, recording duration, timestamped phases, and independently measured outcomes. Expected behavior must be confirmed in the actual recording before it becomes reference evidence.

The initial product slice should accept recordings through the web UI or REST API, preserve their extracted facts and evidence, produce deterministic findings, recognize repeated findings, explain a before/after comparison, and show trends and summaries over a time range or the last N reports. Raw dataset recordings remain in separately managed source storage. Scheduled ingestion, folder monitoring, S3 ingestion, AI provider integration, Jira and PR integrations, and external telemetry correlation can follow this validated foundation. See [architecture.md](architecture.md) for the proposed v1 design.

## Success criteria

- Stored analysis results and evidence can be retrieved without relying on a developer's machine or continued access to the raw JFR file.
- Web UI and REST imports process recordings without retaining raw dumps after processing or modifying the user's original files.
- Every reported finding links to supporting measurements and evidence.
- Reprocessing identical inputs with the same analyzer configuration produces consistent results.
- Repeated observations of a known issue retain a common finding identity.
- Controlled burst, sustained-load, and idle scenarios receive appropriately different interpretations.
- Controlled fixes and regressions are detected under comparable conditions.
- Missing events, insufficient coverage, and incompatible workloads produce explicit limitations rather than unsupported conclusions.
- Users can move from a historical change to the recording metadata, reports, and retained evidence that explain it.

## Decisions to resolve during architecture work

- Upload handling, recording identity, retry behavior, and temporary-file cleanup; source discovery is deferred.
- Storage, retention, and access control for extracted facts, reports, and evidence, including sensitive data derived from JFR.
- The self-contained evidence excerpt format and how it references externally stored original recordings.
- Finding identity rules across code revisions and analyzer versions.
- Baseline selection, comparison thresholds, and verification requirements.
- Supported JDK versions, JFR event configurations, and analysis coverage.
- How workload and deployment context enter the system when JFR alone is insufficient.

These decisions should be informed by the reference dataset and initial analysis results.
