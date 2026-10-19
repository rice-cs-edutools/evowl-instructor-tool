# Configuration Reference

The assignment config is a JSON file that you pass to `evowl assignment` with the `--criteria` flag. It controls the evaluation criteria and rubrics, TA assignments, peer and self evaluation question text, training module pairs, and submission grouping parameters.

---

## Examples

### Minimal valid config

One multiple-choice criterion, no training, empty free-response and self criteria.

```json
{
  "id": "Assignment-1",
  "pairsPerStudent": 3,
  "evalsPerTaPerCriteria": 1,
  "language": "golang",
  "coverageLevel": 1,
  "criteria": [
    {
      "name": "<b>Modularity</b>",
      "peerPreText": "Which design is more modular?",
      "selfPreText": "Rate the modularity of your submission.",
      "postText": "Consider how code is distributed across packages.",
      "tas": ["userid1"],
      "taRubric": [
        { "text": "<b>Package Structure</b>", "isHeader": true, "mutuallyExclusive": true },
        { "text": "All code in one package", "pts": -3.0, "isHeader": false },
        { "text": "Code in multiple packages", "pts": 0.0, "isHeader": false }
      ],
      "weight": 5.0
    }
  ],
  "freeResponse": [],
  "selfCriteria": [],
  "training": []
}
```

### Fully-populated config

One criterion, one training pair, two free-response prompts, and every optional field.

```json
{
  "id": "Assignment-1",
  "pairsPerStudent": 3,
  "evalsPerTaPerCriteria": 1,
  "language": "golang",
  "coverageLevel": 1,
  "criteria": [
    {
      "name": "<b>Clarity: Which implementation is easier to read?</b>",
      "peerPreText": "Which implementation is clearer?",
      "selfPreText": "Rate the clarity of your submission.",
      "postText": "Consider naming, documentation, and code structure.",
      "postTextTa": " Focus on missing or misleading doc comments.",
      "tas": ["userid1", "userid2"],
      "taRubric": [
        { "text": "<b>Missing Comments</b>", "isHeader": true, "mutuallyExclusive": true },
        { "text": "0 functions missing comments", "pts": 0.0,  "isHeader": false },
        { "text": "1–2 functions missing comments", "pts": -1.0, "isHeader": false },
        { "text": "3+ functions missing comments", "pts": -2.0, "isHeader": false },
        { "text": "<b>Repeated Violations</b>", "isHeader": true, "mutuallyExclusive": false },
        { "text": "Same issue appears in multiple files", "pts": -1.0, "isHeader": false }
      ],
      "weight": 10.0
    }
  ],
  "freeResponse": [
    {
      "text": "Describe the single best design choice in each submission.",
      "maxChar": 200,
      "weight": 0.0
    },
    {
      "text": "Describe the most significant way each submission could be improved.",
      "maxChar": 200,
      "weight": 0.0
    }
  ],
  "selfCriteria": [],
  "training": [
    {
      "program1": "path/to/implementation-group09",
      "program2": "path/to/implementation-group10",
      "answers": [2, 1],
      "feedback": [
        "Implementation 2 is better because it splits concerns across packages.",
        "Implementation 1 has clearer naming and complete doc comments."
      ]
    }
  ]
}
```

---

## Field reference

### Top-level fields

---

#### `id`

| Property | Value |
|---|---|
| Type | `string` |
| Required | Yes |

**Description:** An identifier for the assignment, added within tables as unique-ID strings.

**Example:**
```json
"id": "Assignment-1"
```

---

#### `pairsPerStudent`

| Property | Value |
|---|---|
| Type | `number` (integer) |
| Required | Yes |

**Description:** Number of other groups each student evaluates during peer evaluation.

**Example:**
```json
"pairsPerStudent": 3
```

---

#### `evalsPerTaPerCriteria`

| Property | Value |
|---|---|
| Type | `number` (integer) |
| Required | Yes |

**Description:** Number of submissions each TA grades per criterion in round-1 TA grading.

**Example:**
```json
"evalsPerTaPerCriteria": 1
```

---

#### `language`

| Property | Value |
|---|---|
| Type | `string` |
| Required | Yes |

**Description:** Programming language of the student submissions. Determines which coverage and metrics parsers run. Must be exactly `"golang"` or `"typescript"` (case-sensitive).

**Example:**
```json
"language": "golang"
```

---

#### `coverageLevel`

| Property | Value |
|---|---|
| Type | `number` (integer) |
| Required | Yes |

**Description:** Controls how source coverage data is generated for submissions.

| Value | Behavior |
|---|---|
| `0` | No coverage processing |
| `1` | Parse existing Cobertura XML in each submission directory |

**Example:**
```json
"coverageLevel": 1
```

---

#### `criteria`

| Property | Value |
|---|---|
| Type | `array` of criterion objects |
| Required | Yes |

**Description:** Multiple-choice evaluation criteria used to populate the peer evaluation, self evaluation, and TA evaluation pages in the webapp.

The web tool matches each criterion's `name` field against built-in keyword patterns to decide which **interactive metrics page** to display. See Sub-keys below.

**Example:**
```json
"criteria": [ { "name": "...", ... } ]
```

##### Sub-keys

---

##### `name`

| Property | Value |
|---|---|
| Type | `string` |
| Required | Yes (when criterion has no `text` key) |

**Description:** Display name of the criterion. HTML is allowed (e.g., `<b>`, `&amp;`). The webapp strips HTML tags and checks the lowercase name for these built-in keywords: `modularity`, `decoupling`, `encapsulation`, `clarity`, `robustness`. A match renders the corresponding interactive metrics page; any other name renders a generic side-by-side editor.

To supply a custom comparison question, append a colon and the question text to the name: `"Title: Question text?"`. The text before the colon becomes the page title; the text after becomes the comparison question shown to students.

**Example:**
```json
"name": "<b>Clarity: Which implementation is easier to read?</b>"
```

---

##### `peerPreText`

| Property | Value |
|---|---|
| Type | `string` |
| Required | Yes (for peer eval branch) |

**Description:** Question shown to students during peer evaluation for this criterion. Concatenated on the webapp page into the criterion text (`peerPreText + " " + postText`).

**Example:**
```json
"peerPreText": "Which design is more modular and cohesive?"
```

---

##### `selfPreText`

| Property | Value |
|---|---|
| Type | `string` |
| Required | Yes (for self eval branch) |

**Description:** Question shown to students during self evaluation.

**Example:**
```json
"selfPreText": "Rate the modularity of your group's submission."
```

---

##### `postText`

| Property | Value |
|---|---|
| Type | `string` |
| Required | Yes (for peer/self eval branches) |
| Default | none |

**Description:** Additional context appended to both the peer and self criterion text after `peerPreText` / `selfPreText`. Normally to describe what to look for.

**Example:**
```json
"postText": "Consider whether each package does one cohesive thing."
```

---

##### `postTextTa`

| Property | Value |
|---|---|
| Type | `string` |
| Required | No |

**Description:** Optional additional context appended to the criterion text **only** for instructor (TA) grading. Can be used to give TAs evaluation guidance.

**Example:**
```json
"postTextTa": " Pay close attention to error propagation patterns."
```

---

##### `weight`

| Property | Value |
|---|---|
| Type | `number` (decimal) |
| Required | Yes |

**Description:** Point value of this criterion for instructor grading. The final instructor score for this criterion is computed as `max(0, weight + sum(deductions))`, where each selected rubric deduction contributes a negative `pts` value. Weights are **not** normalized across criteria and do not need to sum to any particular total. A value of `0.0` yields a zero-point instructor score. The peer-evaluation Elo ranking does not use this field.

**Example:**
```json
"weight": 5.0
```

---

##### `tas`

| Property | Value |
|---|---|
| Type | `array` of `string` |
| Required | Yes (for instructor-graded criteria) |

**Description:** List of TA `user_id` values assigned to grade this criterion in TA grading. Each ID must appear in the roster loaded with `evowl roster`.

**Example:**
```json
"tas": ["userid1", "userid2"]
```

---

##### `taRubric`

| Property | Value |
|---|---|
| Type | `array` of rubric item objects |
| Required | Yes (for instructor-graded criteria) |

**Description:** Rubric used by TAs to grade this criterion. Can include a mix of headers (`isHeader: true`) and rubric items (`isHeader: false`). Each header groups the rubric items that follow it; the header's `mutuallyExclusive` flag controls whether its rubric items render as radio buttons or checkboxes.

**Example:**
```json
"taRubric": [
  { "text": "<b>Package Structure</b>", "isHeader": true, "mutuallyExclusive": true },
  { "text": "All code in one package", "pts": -3.0, "isHeader": false },
  { "text": "Code in multiple packages", "pts": 0.0, "isHeader": false }
]
```

###### `taRubric[]` sub-keys

---

###### `text`

| Property | Value |
|---|---|
| Type | `string` |
| Required | Yes |

**Description:** Label for this rubric row. HTML is allowed. Displayed as the section header (when `isHeader: true`) or as the grade-row label next to the radio/checkbox (when `isHeader: false`).

**Example:**
```json
"text": "<b>Missing Error Checks</b>"
```

---

###### `isHeader`

| Property | Value |
|---|---|
| Type | `boolean` |
| Required | Yes |

**Description:** When `true`, this row is a section header that groups the rubric items following it until the next header (or end of array). When `false`, this row is a selectable grade option.

**Example:**
```json
"isHeader": true
```

---

###### `mutuallyExclusive`

| Property | Value |
|---|---|
| Type | `boolean` |
| Required | Yes when `isHeader: true` |

**Description:** Should be present only on header rows. When `true`, the rubric items in this section render as radio buttons (one selection required). When `false`, they render as independent checkboxes (zero or more may be selected).

**Example:**
```json
"mutuallyExclusive": true
```

---

###### `pts`

| Property | Value |
|---|---|
| Type | `number` (decimal) |
| Required | Yes when `isHeader: false` |

**Description:** Point deduction applied when this grade row is selected. All selected deductions in a criterion are summed and added to the criterion's `weight` to produce the instructor score (clamped at zero). This should be negative if you intend for it to be a deduction. However, this can also be used for positive grading.

**Example:**
```json
"pts": -2.0
```

---

#### `freeResponse`

| Property | Value |
|---|---|
| Type | `array` of free-response prompt objects |
| Required | Yes (empty array `[]` is valid) |

**Description:** Free-response prompts shown to students on the post-ranking submission page, after they have completed all `criteria` MC questions. Every entry in this array is a textarea prompt. The webapp draft editor always generates exactly two of these (best design choice + improvement suggestion), but any number is valid.

##### Sub-keys

---

##### `text`

| Property | Value |
|---|---|
| Type | `string` |
| Required | Yes |

**Description:** Question text shown to students. Used directly as the criterion text.

**Example:**
```json
"text": "Describe the single best design choice of each submission."
```

---

##### `maxChar`

| Property | Value |
|---|---|
| Type | `number` (integer) |
| Required | Yes |

**Description:** Maximum character length for the free-response textarea.

**Example:**
```json
"maxChar": 200
```

---

##### `weight`

| Property | Value |
|---|---|
| Type | `number` (decimal) |
| Required | Yes |

**Description:** Same semantics as [`criteria[].weight`](#weight), but the peer-evaluation Elo ranking path does not use this value. Set to `0.0` for questions that do not contribute to instructor scoring.

**Example:**
```json
"weight": 0.0
```

---

#### `selfCriteria`

| Property | Value |
|---|---|
| Type | `array` of criterion objects |
| Required | Yes (empty array `[]` is valid) |

**Description:** Additional MC questions appended for the self-evaluation flow (pre and post). Uses the same sub-key structure as `criteria` (without `peerPreText`/`postText`/`tas`/`taRubric`).

---

#### `training`

| Property | Value |
|---|---|
| Type | `array` of training pair objects |
| Required | No |

**Description:** Pairs of implementations used in the training module. Students compare each pair and answer the multiple-choice questions; their accuracy affects their reliability weight in the Elo scoring, if the instructor chooses to use an algorithm that incorporates reliability. These could be past student submissions or instructor-written implementations. Set it to `[]` to skip training.

##### Sub-keys

---

##### `program1` / `program2`

| Property | Value |
|---|---|
| Type | `string` |
| Required | Yes |

**Description:** Paths to two implementation directories shown side-by-side in training. Use absolute paths.

**Example:**
```json
"program1": "path/to/implementation-group09",
"program2": "path/to/implementation-group10"
```

---

##### `answers`

| Property | Value |
|---|---|
| Type | `array` of `number` (integer) |
| Required | Yes |

**Description:** Correct answer for each criterion in training order (`criteria` items). The webapp uses these to evaluate students' training responses and compute reliability weights. Scores use the 0–3 scale: 3 = Implementation 1 much better, 2 = Implementation 1 slightly better, 1 = Implementation 2 slightly better, 0 = Implementation 2 much better.

**Example:**
```json
"answers": [2, 2, 1, 3, 0, 1] (assuming 6 criteria items exist)
```

---

##### `feedback`

| Property | Value |
|---|---|
| Type | `array` of `string` |
| Required | Yes |

**Description:** Explanation shown to students after each training question. Must be the same length as `answers`. HTML is allowed.

**Example:**
```json
"feedback": [
  "Implementation 2 is better because it splits concerns across packages.",
  "Implementation 1 has clearer naming and complete doc comments."
]
```

---
