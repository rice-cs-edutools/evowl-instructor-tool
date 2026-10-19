# evowl-instructor-tool

For the full first-time setup flow, see the [End-to-End Setup guide](https://github.com/rice-cs-edutools/evowl/blob/main/end-to-end.md) which sequences the instructor tool and webapp together. This README documents the CLI: configuration keys, every subcommand and its flags, and how to extend the tool for a new language.

## Prerequisites

- Java 11+
- Maven
- **MySQL Server** running locally or remotely.
  - Download / install MySQL Community Server (Version >= 9.0.0): https://dev.mysql.com/downloads/mysql/
    (installation guide: https://dev.mysql.com/doc/refman/8.0/en/installing.html)
  - Optional but recommended — **MySQL Workbench**, a GUI for browsing and verifying the data loaded by this tool: https://dev.mysql.com/downloads/workbench/
- Go (only for `parse`/`metrics` with Golang submissions):
  ```
  go install github.com/praisem1/gocover-cobertura@v1.0.0
  ```

## Configuration Reference

Edit `application.properties` before running any commands:

| Key | Description |
|-----|-------------|
| `evowl.db.url.template` | Replace `localhost:3306` if MySQL is remote. |
| `evowl.db.username` | MySQL username. |
| `evowl.db.password` | MySQL password. |

Set `EVOWL_CONFIG` to the absolute path of `application.properties`, or run all commands from the directory that contains it:

```bash
export EVOWL_CONFIG=/absolute/path/to/evowl-instructor-tool/application.properties
```

## Subcommand Reference

In the commands below, `evowl` is an alias for `java -jar target/evowl-1.0-SNAPSHOT-jar-with-dependencies.jar`. Run `evowl <subcommand> --help` for flag details. Sample input files are in `src/main/resources/instructor-files`.

### `roster`

Load students and TAs from a CSV file. Creates the database if it does not exist.

```
evowl roster --db=<db> --file=<csv>
```

CSV columns: `last_name`, `first_name`, `user_id`, `role`. `user_id` must match the user's LMS account ID. `role`: `student`, `ta`, or `professor`.

### `groups`

Load group membership from a JSON file. Every user ID must already appear in the roster.

```
evowl groups --db=<db> --file=<json>
```

JSON format: object mapping group names to arrays of user IDs.

### `assignment`

Create a peer-evaluation assignment.

```
evowl assignment --db=<db> --name=<name> --deadline=<"YYYY-MM-DD HH:MM:SS"> \
  --criteria=<config.json> --submissions=<dir> [--overwrite]
```

`--overwrite` replaces an existing assignment with the same name. See [CONFIG.md](CONFIG.md) for the full criteria config reference.

### `parse`

Extract covered source lines from Cobertura XML reports in the submissions directory. Required before `metrics` if any criteria use metrics-based interactive tasks.

```
evowl parse --lang=<lang> --submissions=<dir>
```

### `metrics`

Compute code metrics for each parsed submission. Must run after `parse`.

```
evowl metrics --lang=<lang> --submissions=<dir>
```

### `analyze`

Rank groups with ELO from peer eval responses and select which submissions each TA grades in round 2. Outputs one CSV file per criterion into `--out`.

```
evowl analyze --db=<db> --assign=<id> --tiers=<n> --location=<placement> --out=<dir>
```

`--tiers`: number of ELO tiers. `--location`: placement strategy (e.g. `strict-midpoint`).

### `assign-tas`

Read the per-criterion CSV files produced by `analyze` and populate the TA grading queue. TAs will then see their assigned submissions in the webapp.

```
evowl assign-tas --db=<db> --assign=<id> --in=<dir>
```

### `grades`

Export evaluation and grading data to a file. Run once per `--kind` that applies to the assignment.

```
evowl grades --db=<db> --assign=<id> --kind=<kind> --out=<file>
```

| Kind | Description |
|------|-------------|
| `mc` | Peer eval multiple-choice responses |
| `fr` | Peer eval free-response |
| `pe` | Peer eval completion records |
| `semc` | Self eval multiple-choice |
| `sefr` | Self eval free-response |
| `inmc` | Instructor eval multiple-choice |

### `grade`

Interpolate final scores using the ELO ranking from `analyze` and TA anchor grades.

```
evowl grade --db=<db> --assign=<id> --in=<dir> --out=<dir>
```

**Variants:**
- `grade-wo-tas` — compute scores without TA grades.
- `grade-instr` — use instructor grades as anchors instead of TA grades.

### `list-assignments`

Print all assignments in a database.

```
evowl list-assignments --db=<db>
```

### `extension`

Give a student a deadline extension.

```
evowl extension --db=<db> --assign=<id> --userId=<userId> --deadline=<"YYYY-MM-DD HH:MM:SS">
```

### `export`

Export raw evaluation data as CSV.

```
evowl export --db=<db> --assign=<id> --kind=<kind>
```

---

## All Subcommands

Run `evowl --help` for the full list, or `evowl <subcommand> --help` for flag details.

| Subcommand            | Description                                      |
|-----------------------|--------------------------------------------------|
| `roster`              | Load student roster from CSV                     |
| `groups`              | Load group membership from JSON                  |
| `assignment`          | Create an assignment                             |
| `list-assignments`    | Print all assignments in a database              |
| `grades`              | Load a grade CSV into the database               |
| `parse`               | Extract covered source from Cobertura XML        |
| `metrics`             | Compute code metrics for parsed submissions      |
| `analyze`             | Rank groups with ELO and select grading targets  |
| `grade`               | Interpolate final scores from analyze output     |
| `grade-wo-tas`        | Interpolate without TA anchor grades             |
| `extension`           | Give a student a deadline extension              |
| `export`              | Export raw eval data as CSV                      |
| `grade-instr`         | Grade using instructor grades as anchors         |
| `assign-tas`          | Create round-2 grading assignments               |

---

## Adding a New Language

See [EXTRACTORS.md](EXTRACTORS.md) for how to add coverage parsing and metrics support for a new programming language.

## Assignment Configuration

Assignments are configured via a JSON file passed to `evowl assignment` with the `--criteria` flag. The file controls grouping parameters (pairs per student, TA workload), the evaluation criteria array (rubrics, TA assignments, peer and self question text, and point values), free-response prompts shown after the MC ranking page via `freeResponse`, and an optional `training` array of submission pairs for the student training module.

The latest version of EvOwl determines which peer grading page to show for each criterion by matching the criterion's `name` against the following built-in keywords (`modularity`, `decoupling`, `encapsulation`, `clarity`, `robustness`); any criterion whose name does not match a keyword gets a generic side-by-side editor page instead. To add a new language metrics parser, see [METRICS.md](METRICS.md).

For full documentation of every config field, see [CONFIG.md](CONFIG.md).

# Credit

This project owes its success to the following contributors:

[Praise Mayor](https://github.com/praisem1) \
[Adam Tillou](https://github.com/benzanol) \
[Scott Rixner](https://github.com/rixner) \
[Rebecca Schreib](https://github.com/rebeccasmith) \
[Luisa Martinez](https://github.com/luisa0martinez) \
[Lily Gao](https://github.com/Moongram) \
[Arantxa Pacheco](https://github.com/celesteeeeeeee)
