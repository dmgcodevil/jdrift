# JDrift

Historical JVM performance intelligence. See [product.md](product.md) and [architecture.md](architecture.md).

## License

JDrift's original source code and documentation are licensed under the [MIT License](LICENSE), which permits commercial use, modification, and distribution while retaining the copyright and permission notice. Third-party components retain their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Implementation status

The implementation contains Kotlin/Gradle modules, a JMC-backed diagnostic command, synthetic CPU scenarios, temporal measurements with stack evidence, SQLite persistence, and command-line historical comparisons. It is not the complete v1 product: web imports/UI, general production workload mappings, and evidence-backed fix-verification policies are still pending.

JMC types remain inside `adapter-jmc`. The current diagnostic command evaluates the installed JMC rules for exploration; it does not yet promote those results into verified JDrift findings. Recorded event counts do not prove an event type was enabled throughout a recording. Missing events must not be interpreted as zero activity.

Validation completed for this slice: Gradle build, four history tests, Java 21 burst recording generation, and JMC parsing/rule execution on that recording. Some JMC summaries contain structured placeholders; these are preserved as diagnostic templates and are not yet rendered user-facing explanations. The short burst fixture did not trigger JMC's method-profiling warning, reinforcing the need to validate our temporal extraction separately. Java 17 input compatibility and near-1-GB resource behavior remain untested.

## Build

Use JDK 21 with `JAVA_HOME` configured:

```powershell
.\gradlew.bat build
```

The Gradle wrapper downloads its pinned distribution on first use. Dependencies come from Maven Central; `libs/jmc` is only reference source. Project-local downloaded tools and generated recordings are ignored.

## Generate and inspect a synthetic recording

```powershell
.\gradlew.bat :scenario-harness:run --args="burst ../data/burst.jfr"
.\gradlew.bat :app:run --args="../data/burst.jfr"
```

Gradle runs each program from its module directory, so these relative paths refer to the root `data` folder. Supported scenarios are `healthy`, `sustained`, and `burst`, each lasting approximately ten seconds. A custom phase event records busy/idle intervals and completed work batches. A sidecar manifest records scenario and runtime metadata. These short recordings are smoke-test inputs; they are not sufficient for production thresholds or statistical fix verification.

The recorder uses Java source compatible with Java 17 APIs, but both Java 17 and Java 21 generation must be exercised before claiming compatibility coverage. Do not overwrite user-supplied recordings when generating datasets.

## Next milestones

1. Extend coverage verification and production workload metadata mappings beyond the known synthetic schema.
2. Add allocation, GC, contention, and JIT scenarios and incremental-improvement sequences.
3. Add asynchronous web import lifecycle and temporary-upload cleanup around the existing SQLite report storage.
4. Expose Ktor APIs and a React UI for imports and historical investigations.
5. Extend historical comparisons into detector-specific problem identity and verified fix assessments.

## Persisted history commands

Build the local distribution with `gradlew.bat :app:installDist`. Run the following from the project root with JDK 21 on `JAVA_HOME`:

```powershell
.\app\build\install\app\bin\app.bat import data/history.db data/sustained.jfr
.\app\build\install\app\bin\app.bat list data/history.db synthetic-cpu test
.\app\build\install\app\bin\app.bat show data/history.db REPORT_ID
.\app\build\install\app\bin\app.bat history data/history.db synthetic-cpu test BASELINE_ID --last 5
.\app\build\install\app\bin\app.bat history data/history.db synthetic-cpu test BASELINE_ID --from 2026-01-01T00:00:00Z --until 2027-01-01T00:00:00Z
.\app\build\install\app\bin\app.bat compare data/history.db BASELINE_ID CANDIDATE_ID
```

Outputs are JSON. Imports without recognizable embedded application/environment require two explicit trailing arguments: `import DATABASE RECORDING APPLICATION ENVIRONMENT`. Explicit context takes precedence; original extracted metadata remains in the saved analysis. Files are read in place, never copied into the database or deleted. This synchronous development CLI is not yet the asynchronous REST import workflow or the v2 agent CLI contract.

SQLite schema v1 stores immutable versioned report/evidence JSON with indexed application, environment, capture time, content hash, and analyzer version. This deliberately simple initial schema keeps queries usable without raw JFR; normalized metric tables may follow after measuring query costs. Duplicate imports return the existing analysis. History selects the latest imported analysis per content hash and orders by capture time. The baseline must reference the selected analysis, not a superseded version. Time ranges are start-inclusive/end-exclusive; `--last` cannot be combined with range flags.

Metadata extraction makes a second streaming pass using the JDK consumer API behind the adapter. It records JVM version, observed execution-sample settings, and known `dev.jdrift.ScenarioPhase` metadata. Custom schema mapping is not generalized yet. Missing or conflicting metadata is unavailable; a list of observed settings does not prove continuous sampling coverage.

History shows recorded CPU observation means, execution samples per second, and instrumented worker-thread CPU nanoseconds per completed batch when the known workload events provide valid, non-overlapping observations. It aggregates the numerator and denominator before dividing. CPU load and sample rate remain descriptive changes and cannot establish efficiency gains. CPU cost comparisons require the same analyzer, JVM, workload, declared load profile, worker scope, and at least 100 operations per recording. Hardware, deployment effects, and statistical significance are not established by these checks. Saturated and throttled profiles are deliberately incomparable: the recording experiment showed that throttling overhead can change per-batch CPU cost substantially even with unchanged computational work.

A provisional 10% effect threshold labels instrumented cost changes as measured improvement or potential regression. Lower traffic alone is not an efficiency improvement. No result currently claims a verified fix. Historical method activity can be first observed, continuing, not observed, or reobserved after a gap. These are sampled-activity labels, not diagnoses of new problems. Novelty consults all earlier retained history, even outside the selected range.

The harness now also supports `improved` (less computation per batch), `regressed` (original computation), `new-method` (a different stack), and `lower-load` (same computation with throttling). These experiments validate measurement mechanics, not real application semantics or customer benefit. Run the repeatable full recording/import/history acceptance check after building:

```powershell
.\scripts\verify-history.ps1
```

The script creates a unique dataset directory and database, generates six recordings, and checks duplicate import, improvement, regression, novelty, reappearance, last-N selection, time-range selection, and conservative lower-load interpretation. It leaves recordings in the dataset folder for inspection; the database contains only derived data. Unit tests also validate source-independent persistence after reopening, low-context uncertainty, zero baselines, and overlapping workload rejection.

## Temporal measurements and evidence

The analyzer emits one-second windows relative to the first observed event, including partial final windows. It preserves execution-sample counts, samples without stacks, deduplicated structured stack frames, and recorded JVM CPU-load observation means. CPU values are fractions from 0 to 1; the mean is an arithmetic observation mean, not time-weighted utilization or method CPU time. Windows with no CPU observation have a null mean.

Optional JSON export creates a new file and never overwrites existing output:

```powershell
.\gradlew.bat :app:run --args="../data/burst.jfr ../data/burst.evidence.json"
```

Evidence includes the recording hash, schema/policy versions, window timestamps, stack references, and limitations. No raw JFR or JMC object is embedded. Stack IDs are stable hashes of structured frame identity, positions, and truncation state. Inclusive per-method counts count recursion once per sample; totals across methods must not be summed as independent execution time.

The provisional pattern policy describes observed samples: at least three samples in an active full window, ten total samples, and five full windows are required. Activity in at least 70% of full windows is sustained; activity in at most 30% is localized; intermediate occupancy is intermittent. These labels do not independently assert a performance problem, prove idle execution, or establish a fix. Sampling configuration and gaps are not yet verified. Event-envelope bounds are explicitly labeled rather than claimed as full recording coverage.

Resource guards currently reject more than 100,000 windows or unique sampled stacks instead of silently truncating evidence. Limits and extraction memory behavior still need validation near the expected 1-GB input size.

Validated on generated Java 21 recordings: the same hot-loop method was localized in the burst fixture (46 samples, 2 of 10 full windows) and sustained in the sustained fixture (635 samples, 10 of 10 full windows); it was absent from the healthy fixture. Counts vary by run and machine. Unit tests separately verify equal totals with different temporal distributions, low-sample uncertainty, partial windows, and recursive attribution. A JFR integration test verifies repeatable extraction and unavailable CPU data.

Generate and export all three scenarios, then run the dataset acceptance check:

```powershell
.\scripts\verify-temporal.ps1
```

This checks pattern expectations, sample accounting, evidence references, and missing CPU values. A failed expectation means the recorded fixture needs investigation; intended workload alone does not establish ground truth.
